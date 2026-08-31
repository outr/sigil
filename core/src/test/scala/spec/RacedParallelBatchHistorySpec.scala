package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import rapid.{Stream, Task}
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.Message
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.provider.{
  CallId, GenerationSettings, Instructions, Provider, ProviderCall, ProviderEvent, ProviderMessage,
  ProviderType, StopReason, TokenUsage
}
import sigil.tool.Tool
import sigil.tool.core.{CoreTools, RespondTool}
import sigil.tool.model.{RespondInput, ResponseContent}
import spice.http.HttpRequest

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * A parallel batch of live reads, settling at staggered times, must
 * reach the model whole — every sibling paired with its own result,
 * on every iteration that follows, for as long as the turn runs.
 *
 * The field shape this pins is a batch of same-named calls with
 * different arguments over a tool that declares
 * [[sigil.tool.Freshness.Volatile]] (an ERP search, an inventory
 * level — anything whose rows move under the caller) and takes long
 * enough that siblings settle apart. Two distinct corruptions
 * produced one symptom, a model rationally re-asking for results it
 * had already been given:
 *
 *   - siblings VANISHED: the per-turn frame pass collapsed the pairs
 *     to one-per-tool-name, because the boundary it uses to protect
 *     the current turn was read off the chain's head — the agent
 *     itself on every iteration after the first;
 *   - the surviving pair's result was REPLACED by the "result did not
 *     reach this turn" placeholder: a usage fold rewrote the invoke's
 *     frame from the invoke row alone, discarding the payload the
 *     pairing path had delivered.
 *
 * Everything runs through the real agent loop; the prompt handed to
 * the provider on every iteration is captured and inspected.
 */
class RacedParallelBatchHistorySpec extends AnyWordSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "raced-batch")
  TestSigil.testModel(modelId)

  /**
   * The parallel batch emitted on iteration 1, slowest last.
   */
  private val batchProbes = List("alpha", "bravo", "charlie")

  /**
   * Staggered so at least one sibling settles well after the first —
   * the timing an instant-settling fixture never produces.
   */
  private val batchDelays: Map[String, FiniteDuration] =
    Map("bravo" -> 250.millis, "charlie" -> 600.millis)

  private val followProbes = List("delta", "epsilon", "zeta")

  /**
   * The framework's marker for a result that has not landed yet.
   */
  private val RacePlaceholder = "result did not reach this turn"

  private trait Script {

    /**
     * Calls emitted on iteration `n` (1-based); empty means respond.
     */
    def emit(n: Int): List[(CallId, String)]
  }

  /**
   * Batch once, then a fresh single call per iteration. The turn keeps
   * running, so the settled batch has to survive several more rounds.
   */
  private object FreshFollowUps extends Script {
    def emit(n: Int): List[(CallId, String)] =
      if (n == 1) batchProbes.zipWithIndex.map { case (p, i) => CallId(s"batch-$i") -> p }
      else if (n - 1 <= followProbes.length) List(CallId(s"follow-${followProbes(n - 2)}") -> followProbes(n - 2))
      else Nil
  }

  /**
   * Batch, then the SAME batch again, iteration after iteration —
   * every repeat runs into the duplicate-call cap, and the last call
   * of each response is the one the usage fold lands on.
   */
  private object ReissuedBatch extends Script {
    def emit(n: Int): List[(CallId, String)] =
      if (n <= 4) batchProbes.zipWithIndex.map { case (p, i) => CallId(s"batch-$n-$i") -> p } else Nil
  }

  private class ScriptedProvider(script: Script, recorded: ConcurrentLinkedQueue[ProviderCall]) extends Provider {
    private val iterations = new AtomicInteger(0)

    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))

    override def call(input: ProviderCall): Stream[ProviderEvent] =
      if (!input.roster.tools.exists(_.name == LiveProbeReadTool.name)) {
        // A framework consult (memory extraction, reply suggestion) —
        // not an agent iteration; answer it with nothing.
        Stream.emits(List(ProviderEvent.Done(StopReason.Complete)))
      } else {
        recorded.add(input)
        script.emit(iterations.incrementAndGet()) match {
          case Nil =>
            val cid = CallId(s"respond-${recorded.size}")
            Stream.emits(List(
              ProviderEvent.ToolCallStart(cid, RespondTool.schema.name.value),
              ProviderEvent.toolCall(cid, RespondTool)(RespondInput(
                topicLabel = "Batch",
                topicSummary = "Raced parallel batch",
                content = "Done.",
                endsTurn = true)),
              ProviderEvent.Done(StopReason.Complete)
            ))
          case calls =>
            // ONE completion carrying every call — the parallel batch —
            // closed by the usage report every shipped provider sends.
            Stream.emits(calls.flatMap { case (cid, probe) =>
              List(
                ProviderEvent.ToolCallStart(cid, LiveProbeReadTool.name.value),
                ProviderEvent.toolCall(cid, LiveProbeReadTool)(ProbeReadInput(probe = probe)))
            } ::: List(
              ProviderEvent.Usage(TokenUsage(promptTokens = 120, completionTokens = 24, totalTokens = 144)),
              ProviderEvent.Done(StopReason.ToolCall)))
        }
      }
  }

  private def agent: AgentParticipant =
    DefaultAgentParticipant(
      id = TestAgent,
      modelId = modelId,
      toolNames = LiveProbeReadTool.name :: CoreTools.coreToolNames,
      instructions = Instructions(),
      generationSettings = GenerationSettings(maxOutputTokens = Some(64), temperature = Some(0.0))
    )

  private def runTurn(script: Script, label: String): List[ProviderCall] = {
    val recorded = new ConcurrentLinkedQueue[ProviderCall]()
    val provider = new ScriptedProvider(script, recorded)
    LiveProbeReadTool.reset()
    LiveProbeReadTool.delays = batchDelays
    TestSigil.setProvider(Task.pure(provider))
    TestSigil.setTurnGovernors(Nil)
    TestSigil.setMaxAgentIterations(12)

    val convId = Conversation.id(s"$label-${rapid.Unique()}")
    val conv = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId)
    val task = for {
      _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      _ <- TestSigil.publish(Message(
        participantId = TestUser,
        conversationId = convId,
        topicId = TestTopicEntry.id,
        content = Vector(ResponseContent.Text("Read every probe and report back.")),
        state = sigil.signal.EventState.Complete
      ))
      _ <- TestSigil.awaitSettled(convId, timeout = 120.seconds)
    } yield recorded.iterator().asScala.toList
    task.sync()
  }

  private def one(s: String): String = s.replace("\n", "\\n")

  /**
   * Readable dump of one iteration's rendered prompt — the diagnosis
   * when an assertion below fails.
   */
  private def dump(call: ProviderCall): String =
    call.messages.zipWithIndex.map {
      case (ProviderMessage.System(c), i) => f"$i%2d system     ${one(c).take(110)}"
      case (ProviderMessage.User(blocks), i) => f"$i%2d user       ${one(blocks.mkString(" ")).take(110)}"
      case (ProviderMessage.Assistant(c, tcs), i) =>
        f"$i%2d assistant  text='${one(c).take(40)}' calls=[${tcs.map(t => s"${t.id} ${t.name}${t.argsJson}").mkString(" | ")}]"
      case (ProviderMessage.ToolResult(id, c), i) => f"$i%2d result    $id -> ${one(c).take(110)}"
      case (other, i) => f"$i%2d ${one(other.toString).take(110)}"
    }.mkString("\n")

  private def dumpAll(calls: List[ProviderCall]): String =
    calls.zipWithIndex.map { case (c, i) => s"--- iteration ${i + 1} ---\n${dump(c)}" }.mkString("\n")

  /**
   * Every wire call id issued in this prompt, in order.
   */
  private def issuedIds(call: ProviderCall): Vector[String] =
    call.messages.collect { case a: ProviderMessage.Assistant => a.toolCalls.map(_.id) }.flatten

  private def resultsById(call: ProviderCall): Map[String, String] =
    call.messages.collect { case ProviderMessage.ToolResult(id, content) => id -> content }.toMap

  /**
   * `[from, until)` over `call.messages` covering `ids` — from the
   * message issuing the first of them through the last of their
   * results. `None` when the batch isn't in this prompt.
   */
  private def batchWindow(call: ProviderCall, ids: List[String]): Option[(Int, Int)] = {
    val issuing = call.messages.zipWithIndex.collect {
      case (a: ProviderMessage.Assistant, at) if a.toolCalls.exists(t => ids.contains(t.id)) => at
    }
    val answering = call.messages.zipWithIndex.collect {
      case (ProviderMessage.ToolResult(id, _), at) if ids.contains(id) => at
    }
    if (issuing.isEmpty || answering.isEmpty) None else Some(issuing.min -> (answering.max + 1))
  }

  private def scenario(name: String, script: Script, label: String): Unit = {
    lazy val calls: List[ProviderCall] = runTurn(script, label)

    s"$name" should {

      "run the scripted turn to completion" in
        withClue(s"iterations recorded: ${calls.size}\n${dumpAll(calls)}\n") {
          calls.size should be >= 3
        }

      "carry EVERY sibling of the batch on the first iteration that follows it" in {
        val batchIds = script.emit(1).map(_._1.value)
        val second = calls(1)
        withClue(s"iteration 2 issued ${issuedIds(second)}, expected all of $batchIds\n${dump(second)}\n") {
          issuedIds(second).take(batchIds.size) shouldBe batchIds.toVector
        }
        val byId = resultsById(second)
        batchIds.zip(batchProbes).foreach { case (id, probe) =>
          withClue(s"iteration 2: call $id ($probe) -> '${byId.getOrElse(id, "<absent>")}'\n${dump(second)}\n") {
            byId.get(id).map(_.contains(LiveProbeReadTool.resultTextFor(probe))) shouldBe Some(true)
          }
        }
      }

      "answer a call that settled with its own real result, never the race placeholder" in
        calls.zipWithIndex.drop(1).foreach { case (call, idx) =>
          val args = call.messages.collect { case a: ProviderMessage.Assistant => a.toolCalls }.flatten
            .map(t => t.id -> t.argsJson).toMap
          val byId = resultsById(call)
          args.foreach { case (id, argsJson) =>
            (batchProbes ++ followProbes).find(p => argsJson.contains(s""""$p"""")).foreach { probe =>
              val content = byId.getOrElse(id, "")
              withClue(s"iteration ${idx + 1}: call $id ($argsJson) -> '${one(content)}'\n${dump(call)}\n") {
                // A repeat the framework refused carries its corrective
                // note; every other call carries its own probe's result.
                // Neither may degrade into the "hasn't landed" marker —
                // the result landed, and saying otherwise is what makes
                // a model re-ask for what it already has.
                content should not include RacePlaceholder
                if (!content.startsWith("Refused")) content should include(LiveProbeReadTool.resultTextFor(probe))
                (batchProbes ++ followProbes).filterNot(_ == probe).foreach(other =>
                  content should not include LiveProbeReadTool.resultTextFor(other))
              }
            }
          }
        }

      "keep the settled batch byte-identical and in position however many iterations follow" in {
        val batchIds = script.emit(1).map(_._1.value)
        val reference = calls(1)
        val refWindow = batchWindow(reference, batchIds)
        withClue(s"iteration 2 does not carry the settled batch\n${dump(reference)}\n") {
          refWindow shouldBe defined
        }
        val (refFrom, refUntil) = refWindow.get
        val refSlice = reference.messages.slice(refFrom, refUntil)

        calls.zipWithIndex.drop(2).foreach { case (call, idx) =>
          withClue(s"iteration ${idx + 1}: the batch window moved, shrank or changed\n" +
            s"iteration 2:\n${dump(reference)}\niteration ${idx + 1}:\n${dump(call)}\n") {
            batchWindow(call, batchIds) shouldBe refWindow
            call.messages.slice(refFrom, refUntil) shouldBe refSlice
          }
        }
      }

      "never drop a pair it has already rendered" in
        calls.zipWithIndex.drop(1).sliding(2).foreach {
          case Seq((earlier, ei), (later, li)) =>
            withClue(s"iteration ${li + 1} lost calls present in iteration ${ei + 1}\n" +
              s"${dump(earlier)}\n--- then ---\n${dump(later)}\n") {
              issuedIds(later) should contain allElementsOf issuedIds(earlier)
            }
          case _ => ()
        }
    }
  }

  scenario("A staggered parallel batch followed by fresh calls", FreshFollowUps, "raced-fresh")
  scenario("A staggered parallel batch the model re-issues verbatim", ReissuedBatch, "raced-reissued")

  "tear down" should {
    "dispose TestSigil" in {
      TestSigil.resetMaxAgentIterations()
      TestSigil.resetTurnGovernors()
      TestSigil.reset()
      LiveProbeReadTool.reset()
      TestSigil.shutdown.sync()
      succeed
    }
  }
}
