package spec

import fabric.Json
import fabric.io.JsonParser
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import rapid.{Stream, Task}
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.Message
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.provider.anthropic.AnthropicProvider
import sigil.provider.{
  CallId, GenerationSettings, Instructions, Provider, ProviderCall, ProviderEvent, ProviderMessage,
  ProviderType, StopReason
}
import sigil.tool.Tool
import sigil.tool.core.{CoreTools, RespondTool}
import sigil.tool.model.{ResponseContent, RespondInput}
import spice.http.HttpRequest

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * A settled parallel batch must replay once, unchanged, for as long as
 * the turn runs — every call paired with its own result, nothing
 * growing, duplicating or moving as iterations accumulate.
 *
 * Everything here goes through the real agent loop: a scripted provider
 * emits genuine parallel pairs, the tools settle through the normal
 * dispatch path, and the prompt handed to the provider on every
 * iteration is captured and inspected. The frames under test are the
 * ones the pipeline actually produced — settle timing, the recent-event
 * overlay, duplicate serving and multi-iteration accumulation included.
 *
 * Two shapes run the same assertions:
 *   - a batch that settles once and is followed by fresh single calls;
 *   - a batch the model re-issues verbatim on the iterations that
 *     follow, which is what the field produces and what routes the
 *     repeats through the turn-scoped read cache and the duplicate-call
 *     cap.
 */
class ParallelBatchTurnReplaySpec extends AnyWordSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "batch-replay")
  TestSigil.testModel(modelId)

  private val anthropic: Provider =
    AnthropicProvider(apiKey = "sk-ant-test-placeholder", sigilRef = TestSigil)

  /** The parallel pair emitted on iteration 1. */
  private val batchProbes = List("alpha", "bravo")

  /** How many iterations follow the batch before the agent responds. */
  private val followIterations = 3

  /** A turn's script: what the model emits on iteration `n` (1-based). */
  private trait Script {
    def tool: Tool
    def resultTextFor(probe: String): String
    def emit(n: Int): List[(CallId, String)]
    def callEvent(cid: CallId, probe: String): ProviderEvent
  }

  /** Batch once, then single fresh calls. */
  private object FreshFollowUps extends Script {
    val tool: Tool = ProbeReadTool
    def resultTextFor(probe: String): String = ProbeReadTool.resultTextFor(probe)
    def callEvent(cid: CallId, probe: String): ProviderEvent =
      ProviderEvent.toolCall(cid, ProbeReadTool)(ProbeReadInput(probe = probe))
    private val follow = List("gamma", "delta", "epsilon")
    def emit(n: Int): List[(CallId, String)] =
      if (n == 1) batchProbes.zipWithIndex.map { case (p, i) => CallId(s"batch-$i") -> p }
      else if (n - 1 <= follow.length) List(CallId(s"follow-${follow(n - 2)}") -> follow(n - 2))
      else Nil
  }

  /** Batch, then the SAME batch again, iteration after iteration. */
  private object ReissuedBatch extends Script {
    val tool: Tool = CachedProbeReadTool
    def resultTextFor(probe: String): String = CachedProbeReadTool.resultTextFor(probe)
    def callEvent(cid: CallId, probe: String): ProviderEvent =
      ProviderEvent.toolCall(cid, CachedProbeReadTool)(ProbeReadInput(probe = probe))
    def emit(n: Int): List[(CallId, String)] =
      if (n <= followIterations + 1) batchProbes.zipWithIndex.map { case (p, i) => CallId(s"batch-$n-$i") -> p }
      else Nil
  }

  private class ScriptedProvider(script: Script, recorded: ConcurrentLinkedQueue[ProviderCall]) extends Provider {
    private val iterations = new AtomicInteger(0)

    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))

    override def call(input: ProviderCall): Stream[ProviderEvent] =
      if (!input.roster.tools.exists(_.name == script.tool.name)) {
        // A framework consult (memory extraction, reply suggestion) —
        // not an agent iteration; answer it with nothing.
        Stream.emits(List(ProviderEvent.Done(StopReason.Complete)))
      } else {
        recorded.add(input)
        val n = iterations.incrementAndGet()
        script.emit(n) match {
          case Nil =>
            val cid = CallId(s"respond-$n")
            Stream.emits(List(
              ProviderEvent.ToolCallStart(cid, RespondTool.schema.name.value),
              ProviderEvent.toolCall(cid, RespondTool)(RespondInput(
                topicLabel = "Batch", topicSummary = "Parallel batch replay", content = "Done.", endsTurn = true)),
              ProviderEvent.Done(StopReason.Complete)
            ))
          case calls =>
            // ONE completion carrying every call — the parallel batch.
            Stream.emits(calls.flatMap { case (cid, probe) =>
              List(ProviderEvent.ToolCallStart(cid, script.tool.name.value), script.callEvent(cid, probe))
            } :+ ProviderEvent.Done(StopReason.ToolCall))
        }
      }
  }

  private def makeAgent(tool: Tool): AgentParticipant =
    DefaultAgentParticipant(
      id = TestAgent,
      modelId = modelId,
      toolNames = tool.name :: CoreTools.coreToolNames,
      instructions = Instructions(),
      generationSettings = GenerationSettings(maxOutputTokens = Some(64), temperature = Some(0.0))
    )

  private def runTurn(script: Script, label: String): List[ProviderCall] = {
    val recorded = new ConcurrentLinkedQueue[ProviderCall]()
    val provider = new ScriptedProvider(script, recorded)
    TestSigil.setProvider(Task.pure(provider))
    TestSigil.setTurnGovernors(Nil)
    TestSigil.setMaxAgentIterations(followIterations + 6)

    val convId = Conversation.id(s"$label-${rapid.Unique()}")
    val conv = Conversation(topics = TestTopicStack, participants = List(makeAgent(script.tool)), _id = convId)
    val task = for {
      _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      _ <- TestSigil.publish(Message(
             participantId = TestUser,
             conversationId = convId,
             topicId = TestTopicEntry.id,
             content = Vector(ResponseContent.Text("Run the probes and report back.")),
             state = sigil.signal.EventState.Complete
           ))
      _ <- TestSigil.awaitSettled(convId, timeout = 90.seconds)
    } yield recorded.iterator().asScala.toList
    task.sync()
  }

  /** Readable dump of one iteration's rendered prompt — the diagnosis
    * when an assertion below fails. */
  private def dump(call: ProviderCall): String =
    call.messages.zipWithIndex.map {
      case (ProviderMessage.System(c), i)         => f"$i%2d system     ${one(c).take(110)}"
      case (ProviderMessage.User(blocks), i)      => f"$i%2d user       ${one(blocks.mkString(" ")).take(110)}"
      case (ProviderMessage.Assistant(c, tcs), i) =>
        f"$i%2d assistant  text='${one(c).take(40)}' calls=[${tcs.map(t => s"${t.id} ${t.name}${t.argsJson}").mkString(" | ")}]"
      case (ProviderMessage.ToolResult(id, c), i) => f"$i%2d result    $id -> ${one(c).take(110)}"
      case (other, i)                             => f"$i%2d ${one(other.toString).take(110)}"
    }.mkString("\n")

  private def one(s: String): String = s.replace("\n", "\\n")

  private def dumpAll(calls: List[ProviderCall]): String =
    calls.zipWithIndex.map { case (c, i) => s"--- iteration ${i + 1} ---\n${dump(c)}" }.mkString("\n")

  /** Every (assistantIndex, callIds) pair carrying tool calls. */
  private def callGroups(call: ProviderCall): Vector[(Int, List[String])] =
    call.messages.zipWithIndex.collect {
      case (a: ProviderMessage.Assistant, i) if a.toolCalls.nonEmpty => i -> a.toolCalls.map(_.id)
    }

  private def resultsAt(call: ProviderCall, from: Int, count: Int): Vector[String] =
    call.messages.slice(from, from + count).collect { case ProviderMessage.ToolResult(id, _) => id }

  /** `[from, until)` over `call.messages` covering `ids` — from the
    * message issuing the first of them through the last of their
    * results. `None` when the batch isn't in this prompt. */
  private def batchWindow(call: ProviderCall, ids: List[String]): Option[(Int, Int)] = {
    val issuing = callGroups(call).collect { case (at, called) if called.exists(ids.contains) => at }
    val answering = call.messages.zipWithIndex.collect {
      case (ProviderMessage.ToolResult(id, _), at) if ids.contains(id) => at
    }
    if (issuing.isEmpty || answering.isEmpty) None
    else Some(issuing.min -> (answering.max + 1))
  }

  /** The pipeline's own messages put through the strictest shipped wire
    * renderer, so a shape only that contract rejects still surfaces. */
  private def anthropicMessages(call: ProviderCall): Vector[Json] = {
    val body = anthropic.httpRequestFor(call).sync().content match {
      case Some(c: spice.http.content.StringContent) => c.value
      case _                                         => ""
    }
    JsonParser(body).get("messages").map(_.asVector).getOrElse(Vector.empty)
  }

  private def roleOf(message: Json): String = message.get("role").map(_.asString).getOrElse("")

  private def blocksOf(message: Json, blockType: String): Vector[Json] =
    message.get("content").toVector
      .flatMap(c => scala.util.Try(c.asVector).getOrElse(Vector.empty))
      .filter(_.get("type").map(_.asString).contains(blockType))

  private def dumpWire(messages: Vector[Json]): String =
    messages.zipWithIndex.map { case (m, i) =>
      val kinds = m.get("content").toVector
        .flatMap(c => scala.util.Try(c.asVector).getOrElse(Vector.empty))
        .map { b =>
          val t = b.get("type").map(_.asString).getOrElse("?")
          val id = b.get("id").orElse(b.get("tool_use_id")).map(_.asString).getOrElse("")
          if (id.isEmpty) t else s"$t($id)"
        }
      f"$i%2d ${roleOf(m)}%-9s ${kinds.mkString(", ").take(110)}"
    }.mkString("\n")

  private def resultsById(call: ProviderCall): Map[String, String] =
    call.messages.collect { case ProviderMessage.ToolResult(id, content) => id -> content }.toMap

  private def scenario(name: String, script: Script, label: String): Unit = {
    lazy val calls: List[ProviderCall] = runTurn(script, label)

    s"$name" should {

      "run the scripted turn to completion" in {
        withClue(s"iterations recorded: ${calls.size}\n${dumpAll(calls)}\n") {
          calls.size should be >= (followIterations + 2)
        }
      }

      "pair every tool call with its result in the immediately-following messages, every iteration" in {
        calls.zipWithIndex.foreach { case (call, idx) =>
          callGroups(call).foreach { case (at, ids) =>
            val answered = resultsAt(call, at + 1, ids.size)
            withClue(s"iteration ${idx + 1}: calls $ids at message $at answered by $answered\n${dump(call)}\n") {
              answered shouldBe ids.toVector
            }
          }
        }
      }

      "replay every call of the batch exactly once, in emission order" in {
        calls.zipWithIndex.drop(1).foreach { case (call, idx) =>
          val issued = callGroups(call).flatMap(_._2)
          withClue(s"iteration ${idx + 1}: a call was replayed more than once — $issued\n${dump(call)}\n") {
            issued.distinct.size shouldBe issued.size
          }
          val expected = (1 to idx).flatMap(script.emit(_).map(_._1.value))
          withClue(s"iteration ${idx + 1}: replayed $issued, emitted $expected\n${dump(call)}\n") {
            issued shouldBe expected
          }
        }
      }

      "answer every call with its own probe's result and nothing framework-shaped" in {
        calls.zipWithIndex.drop(1).foreach { case (call, idx) =>
          val byId = resultsById(call)
          val args = call.messages.collect { case a: ProviderMessage.Assistant => a.toolCalls }.flatten
            .map(t => t.id -> t.argsJson).toMap
          args.foreach { case (id, argsJson) =>
            batchProbes.find(p => argsJson.contains(s""""$p"""")).foreach { probe =>
              val content = byId.getOrElse(id, "")
              withClue(s"iteration ${idx + 1}: call $id ($argsJson) -> '$content'\n${dump(call)}\n") {
                // A refused repeat carries the framework's corrective note
                // instead of a result; every other call carries its own.
                if (!content.startsWith("Refused")) content should include(script.resultTextFor(probe))
                batchProbes.filterNot(_ == probe).foreach(other =>
                  content should not include script.resultTextFor(other))
              }
            }
          }
        }
      }

      "answer every tool_use in the immediately-following message on the Anthropic wire" in {
        // The pipeline's own frames, rendered by the strictest wire
        // contract that ships: Anthropic rejects an assistant turn whose
        // `tool_use` ids are not all answered by the very next message.
        calls.zipWithIndex.drop(1).foreach { case (call, idx) =>
          val wire = anthropicMessages(call)
          wire.zipWithIndex.foreach { case (m, at) =>
            val useIds = blocksOf(m, "tool_use").flatMap(_.get("id").map(_.asString)).toSet
            if (useIds.nonEmpty) {
              val next = wire.lift(at + 1)
              withClue(s"iteration ${idx + 1}: assistant message $at ($useIds) is followed by " +
                s"${next.map(roleOf).getOrElse("nothing")}\n${dumpWire(wire)}\n") {
                next.map(roleOf) shouldBe Some("user")
                val answered = blocksOf(next.get, "tool_result").flatMap(_.get("tool_use_id").map(_.asString)).toSet
                useIds.diff(answered) shouldBe empty
                answered.diff(useIds) shouldBe empty
              }
            }
          }
          val allUseIds = wire.flatMap(m => blocksOf(m, "tool_use")).flatMap(_.get("id").map(_.asString))
          withClue(s"iteration ${idx + 1}: a tool_use id appears more than once\n${dumpWire(wire)}\n") {
            allUseIds.distinct.size shouldBe allUseIds.size
          }
        }
      }

      "serve a re-issued call the original's own result rather than re-running it" in {
        if (script == ReissuedBatch) {
          withClue(s"the tool ran ${CachedProbeReadTool.executions.get()} times for " +
            s"${calls.size} iterations of the same pair\n${dumpAll(calls)}\n") {
            CachedProbeReadTool.executions.get() shouldBe batchProbes.size
          }
        }
        succeed
      }

      "keep the first batch byte-identical and in position however many iterations follow" in {
        // Iteration 2 is the first prompt carrying the settled batch. Every
        // later iteration must carry that same window unchanged: nothing about
        // a settled batch may grow, duplicate, or move as the turn goes on.
        val firstBatchIds = script.emit(1).map(_._1.value)
        val reference = calls(1)
        val refWindow = batchWindow(reference, firstBatchIds)
        withClue(s"iteration 2 does not carry the settled batch\n${dump(reference)}\n") {
          refWindow shouldBe defined
        }
        val (refFrom, refUntil) = refWindow.get
        val refSlice = reference.messages.slice(refFrom, refUntil)

        calls.zipWithIndex.drop(2).foreach { case (call, idx) =>
          withClue(s"iteration ${idx + 1}: batch window moved or changed\n" +
            s"iteration 2:\n${dump(reference)}\niteration ${idx + 1}:\n${dump(call)}\n") {
            batchWindow(call, firstBatchIds) shouldBe refWindow
            call.messages.slice(refFrom, refUntil) shouldBe refSlice
          }
        }
      }
    }
  }

  scenario("A parallel batch followed by fresh calls", FreshFollowUps, "batch-fresh")
  scenario("A parallel batch the model re-issues verbatim", ReissuedBatch, "batch-reissued")

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
