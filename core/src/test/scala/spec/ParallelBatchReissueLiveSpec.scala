package spec

import fabric.*
import fabric.io.{JsonFormatter, JsonParser}
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{Args, Status}
import rapid.{Stream, Task}
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.Message
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.provider.anthropic.{Anthropic, AnthropicProvider}
import sigil.provider.{
  CallId, GenerationSettings, Instructions, Provider, ProviderCall, ProviderEvent,
  ProviderType, StopReason
}
import sigil.tool.core.{CoreTools, RespondTool}
import sigil.tool.model.{ResponseContent, RespondInput}
import spice.http.client.HttpClient
import spice.http.content.StringContent
import spice.http.{HttpMethod, HttpRequest}
import spice.net.*

import java.nio.file.{Files, Paths}
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * A bench for attributing batch re-issue to a single prompt artifact.
 * Iteration 1 is scripted so a genuine parallel batch settles through
 * the real pipeline; only the decision that follows it is taken live.
 * Every arm sends that one captured prompt with exactly one artifact
 * manipulated, so a change in how often the model re-issues attributes
 * to that artifact and nothing else. Add an arm to test a suspect.
 */
class ParallelBatchReissueLiveSpec extends AnyWordSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  override def run(testName: Option[String], args: Args): Status =
    AnthropicLiveSupport.runGatedForModel(this, testName, args, LiveModelSlug)(super.run(testName, args))

  private val LiveModelSlug = sys.env.getOrElse("ANTHROPIC_TEST_MODEL", "claude-sonnet-5")
  private val trials = sys.env.get("SIGIL_REISSUE_TRIALS").flatMap(_.toIntOption).getOrElse(6)

  private val modelId: Id[Model] = Model.id("test", "reissue-live")
  TestSigil.testModel(modelId)

  private val anthropic: Provider =
    AnthropicProvider(apiKey = AnthropicLiveSupport.apiKey.getOrElse("none"), sigilRef = TestSigil)

  private val outDir = Paths.get("target", "reissue-live")

  private val probes = List("alpha", "bravo")

  private class ScriptedProvider(recorded: ConcurrentLinkedQueue[ProviderCall]) extends Provider {
    private val iterations = new AtomicInteger(0)

    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))

    override def call(input: ProviderCall): Stream[ProviderEvent] =
      if (!input.roster.tools.exists(_.name == ProbeReadTool.name))
        Stream.emits(List(ProviderEvent.Done(StopReason.Complete)))
      else {
        recorded.add(input)
        iterations.incrementAndGet() match {
          case 1 =>
            Stream.emits(probes.zipWithIndex.flatMap { case (p, i) =>
              val cid = CallId(s"toolu_batch$i")
              List(ProviderEvent.ToolCallStart(cid, ProbeReadTool.name.value),
                ProviderEvent.toolCall(cid, ProbeReadTool)(ProbeReadInput(probe = p)))
            } :+ ProviderEvent.Done(StopReason.ToolCall))
          case n =>
            val cid = CallId(s"toolu_respond$n")
            Stream.emits(List(
              ProviderEvent.ToolCallStart(cid, RespondTool.schema.name.value),
              ProviderEvent.toolCall(cid, RespondTool)(RespondInput(
                topicLabel = "Probes", topicSummary = "Probe lookup", content = "Done.", endsTurn = true)),
              ProviderEvent.Done(StopReason.Complete)
            ))
        }
      }
  }

  /** Iteration 2's prompt, produced by the real loop after a settled
    * two-call parallel batch. */
  private lazy val captured: ProviderCall = {
    val recorded = new ConcurrentLinkedQueue[ProviderCall]()
    val provider = new ScriptedProvider(recorded)
    TestSigil.setProvider(Task.pure(provider))
    TestSigil.setTurnGovernors(Nil)
    TestSigil.setMaxAgentIterations(6)

    val convId = Conversation.id(s"reissue-${rapid.Unique()}")
    val agent = DefaultAgentParticipant(
      id = TestAgent,
      modelId = modelId,
      toolNames = ProbeReadTool.name :: CoreTools.coreToolNames,
      instructions = Instructions(),
      generationSettings = GenerationSettings(maxOutputTokens = Some(1024), temperature = Some(0.0))
    )
    val conv = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId)
    val task = for {
      _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      _ <- TestSigil.publish(Message(
             participantId = TestUser,
             conversationId = convId,
             topicId = TestTopicEntry.id,
             content = Vector(ResponseContent.Text(
               "Read the probes 'alpha' and 'bravo' and tell me both values.")),
             state = sigil.signal.EventState.Complete
           ))
      _ <- TestSigil.awaitSettled(convId, timeout = 90.seconds)
    } yield recorded.iterator().asScala.toList
    task.sync()(1)
  }

  /** The pipeline's own Anthropic body, retargeted at the live model
    * and made non-streaming so one response is one JSON object. */
  private def liveBody(call: ProviderCall): Json = {
    val body = anthropic.httpRequestFor(call).sync().content match {
      case Some(c: StringContent) => JsonParser(c.value)
      case _                      => throw new IllegalStateException("no body rendered")
    }
    // `temperature` is rejected outright by this model generation.
    val retargeted = body.merge(obj(
      "model" -> str(LiveModelSlug),
      "stream" -> bool(false),
      "max_tokens" -> num(1024)
    ))
    obj(retargeted.asObj.value.filterNot(_._1 == "temperature").toList*)
  }

  /** Collapse each maximal run of `[assistant with one tool_use]
    * [user with one tool_result]` pairs whose ids all belong to
    * `batchIds` into the single assistant turn plus single user turn
    * the model actually emitted and would have been answered with. */
  private def groupBatch(messages: Vector[Json], batchIds: Set[String]): Vector[Json] = {
    def useIds(m: Json): Vector[String] =
      blocks(m, "tool_use").flatMap(_.get("id").map(_.asString))
    def resultIds(m: Json): Vector[String] =
      blocks(m, "tool_result").flatMap(_.get("tool_use_id").map(_.asString))

    val out = Vector.newBuilder[Json]
    var i = 0
    while (i < messages.length) {
      val uses = useIds(messages(i))
      if (uses.size == 1 && batchIds.contains(uses.head) &&
        i + 1 < messages.length && resultIds(messages(i + 1)) == uses) {
        var n = 0
        val useBlocks = Vector.newBuilder[Json]
        val resultBlocks = Vector.newBuilder[Json]
        var textBlocks = Vector.empty[Json]
        while (i + 2 * n + 1 < messages.length && {
          val u = useIds(messages(i + 2 * n))
          u.size == 1 && batchIds.contains(u.head) && resultIds(messages(i + 2 * n + 1)) == u
        }) {
          textBlocks ++= blocks(messages(i + 2 * n), "text")
          useBlocks ++= blocks(messages(i + 2 * n), "tool_use")
          resultBlocks ++= blocks(messages(i + 2 * n + 1), "tool_result")
          n += 1
        }
        out += obj("role" -> str("assistant"), "content" -> arr((textBlocks ++ useBlocks.result())*))
        out += obj("role" -> str("user"), "content" -> arr(resultBlocks.result()*))
        i += 2 * n
      } else {
        out += messages(i)
        i += 1
      }
    }
    out.result()
  }

  private def blocks(message: Json, blockType: String): Vector[Json] =
    message.get("content").toVector
      .flatMap(c => scala.util.Try(c.asVector).getOrElse(Vector.empty))
      .filter(_.get("type").map(_.asString).contains(blockType))

  /** What the model chose: the names of the tools it called, or
    * `text` when it answered in prose. */
  private def decide(body: Json): (List[String], String) = {
    val req = HttpRequest(
      method = HttpMethod.Post,
      url = url"https://api.anthropic.com/v1/messages",
      content = Some(StringContent(JsonFormatter.Compact(body), ContentType.`application/json`))
    )
      .withHeader("x-api-key", AnthropicLiveSupport.apiKey.getOrElse(""))
      .withHeader("anthropic-version", Anthropic.ApiVersion)
    val raw = HttpClient.modify(_ => req).noFailOnHttpStatus.timeout(120.seconds).send().flatMap { resp =>
      resp.content match {
        case Some(c) => c.asString
        case None    => Task.pure("")
      }
    }.sync()
    val parsed = JsonParser(raw)
    parsed.get("error") match {
      case Some(e) => (List("ERROR"), JsonFormatter.Compact(e))
      case None =>
        val content = parsed.get("content").map(_.asVector).getOrElse(Vector.empty)
        val calls = content.filter(_.get("type").map(_.asString).contains("tool_use"))
          .map(b => b.get("name").map(_.asString).getOrElse("?") +
            b.get("input").map(JsonFormatter.Compact.apply).getOrElse("")).toList
        val text = content.filter(_.get("type").map(_.asString).contains("text"))
          .flatMap(_.get("text").map(_.asString)).mkString(" ")
        (calls, text)
    }
  }

  private def runArm(name: String, body: Json): Unit = {
    Files.createDirectories(outDir)
    Files.writeString(outDir.resolve(s"$name-request.json"), JsonFormatter.Default(body))
    val results = (1 to trials).map { _ =>
      val (calls, text) = decide(body)
      calls -> text
    }
    val reissues = results.count { case (calls, _) => calls.exists(_.startsWith(ProbeReadTool.name.value)) }
    val log = results.zipWithIndex.map { case ((calls, text), i) =>
      s"trial ${i + 1}: calls=${calls.mkString(" | ")} text=${text.take(200)}"
    }.mkString("\n")
    Files.writeString(outDir.resolve(s"$name-trials.txt"), log)
    info(s"[$name] re-issued the probe tool in $reissues/$trials trials")
    log.linesIterator.foreach(info(_))
    withClue(s"$name: the upstream rejected the request\n$log\n") {
      results.count(_._1.contains("ERROR")) shouldBe 0
    }
  }

  private lazy val batchIds: Set[String] = Set("toolu_batch0", "toolu_batch1")

  "the field model, answering the prompt that follows a settled parallel batch" should {

    "A: split rendering (shipped)" in {
      runArm("A-split", liveBody(captured))
    }

    "B: grouped rendering (one assistant turn, one result turn)" in {
      val base = liveBody(captured)
      val grouped = groupBatch(base.get("messages").map(_.asVector).getOrElse(Vector.empty), batchIds)
      runArm("B-grouped", base.merge(obj("messages" -> arr(grouped*))))
    }

    "C: split rendering with the recent-tools digest removed" in {
      runArm("C-no-recent-tools", stripEverywhere(liveBody(captured), "== Recently used tools =="))
    }
  }

  /** Removes a prompt section wherever it landed. The stable prefix
    * rides `system`; the volatile tail rides a trailing `[system]`
    * user message, so both have to be rewritten. */
  private def stripEverywhere(body: Json, header: String): Json = {
    val messages = body.get("messages").map(_.asVector).getOrElse(Vector.empty).map { m =>
      val content = m.get("content").toVector
        .flatMap(c => scala.util.Try(c.asVector).getOrElse(Vector.empty))
        .map { b =>
          b.get("text").map(_.asString) match {
            case Some(t) => b.merge(obj("text" -> str(stripSection(t, header))))
            case None    => b
          }
        }
      m.merge(obj("content" -> arr(content*)))
    }
    body.merge(obj(
      "system" -> str(stripSection(body.get("system").map(_.asString).getOrElse(""), header)),
      "messages" -> arr(messages*)
    ))
  }

  /** Drops one `== Header ==` section, up to the next section header. */
  private def stripSection(text: String, header: String): String = {
    val lines = text.linesIterator.toVector
    val start = lines.indexWhere(_.trim == header.trim)
    if (start < 0) text
    else {
      val end = lines.indexWhere(l => l.trim.startsWith("== ") && l.trim.endsWith(" =="), start + 1)
      val until = if (end < 0) lines.length else end
      (lines.take(start) ++ lines.drop(until)).mkString("\n")
    }
  }

  "tear down" should {
    "dispose TestSigil" in {
      TestSigil.resetMaxAgentIterations()
      TestSigil.resetTurnGovernors()
      TestSigil.reset()
      TestSigil.shutdown.sync()
      succeed
    }
  }
}
