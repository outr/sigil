package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import rapid.{Stream, Task}
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.Message
import sigil.participant.DefaultAgentParticipant
import sigil.provider.{
  CallId, GenerationSettings, Instructions, Provider, ProviderCall, ProviderEvent, ProviderMessage,
  ProviderType, StopReason
}
import sigil.tool.core.{CoreTools, RespondTool}
import sigil.tool.model.{ResponseContent, RespondInput}
import spice.http.HttpRequest

import java.nio.file.{Files, Paths}
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * How a set of tool calls ARRIVED must not survive into the prompt that
 * replays them. Three calls the model fired at once and the same three
 * fired one per iteration have to leave the next prompt in exactly the
 * same state — same system prefix, same volatile tail, same roster,
 * same message trail once wire ids are normalized away.
 *
 * The comparison is exhaustive by construction: whole rendered strings,
 * not a chosen list of fields, so any parallel-specific artifact a
 * future section or renderer introduces fails here rather than being
 * discovered in the field.
 */
class ParallelBatchPromptEquivalenceSpec extends AnyWordSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "prompt-equivalence")
  TestSigil.testModel(modelId)

  private val probes = List("alpha", "bravo", "charlie")
  private val outDir = Paths.get("target", "prompt-equivalence")

  private class ScriptedProvider(emit: Int => List[(CallId, String)],
                                 recorded: ConcurrentLinkedQueue[ProviderCall]) extends Provider {
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
        emit(iterations.incrementAndGet()) match {
          case Nil =>
            val cid = CallId("respond")
            Stream.emits(List(
              ProviderEvent.ToolCallStart(cid, RespondTool.schema.name.value),
              ProviderEvent.toolCall(cid, RespondTool)(RespondInput(
                topicLabel = "Probes", topicSummary = "Probe lookup", content = "Done.", endsTurn = true)),
              ProviderEvent.Done(StopReason.Complete)
            ))
          case calls =>
            val body = calls.flatMap { case (cid, probe) =>
              List(ProviderEvent.ToolCallStart(cid, ProbeReadTool.name.value),
                ProviderEvent.toolCall(cid, ProbeReadTool)(ProbeReadInput(probe = probe)))
            }
            Stream.emits(body ::: List(ProviderEvent.Done(StopReason.ToolCall)))
        }
      }
  }

  private def runTurn(label: String, emit: Int => List[(CallId, String)]): List[ProviderCall] = {
    val recorded = new ConcurrentLinkedQueue[ProviderCall]()
    val provider = new ScriptedProvider(emit, recorded)
    TestSigil.setProvider(Task.pure(provider))
    TestSigil.setTurnGovernors(Nil)
    TestSigil.setMaxAgentIterations(probes.size + 4)

    val convId = Conversation.id(s"$label-${rapid.Unique()}")
    val agent = DefaultAgentParticipant(
      id = TestAgent,
      modelId = modelId,
      toolNames = ProbeReadTool.name :: CoreTools.coreToolNames,
      instructions = Instructions(),
      generationSettings = GenerationSettings(maxOutputTokens = Some(64), temperature = Some(0.0))
    )
    val conv = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId)
    val task = for {
      _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      _ <- TestSigil.publish(Message(
             participantId = TestUser,
             conversationId = convId,
             topicId = TestTopicEntry.id,
             content = Vector(ResponseContent.Text("Read the probes and report back.")),
             state = sigil.signal.EventState.Complete
           ))
      _ <- TestSigil.awaitSettled(convId, timeout = 90.seconds)
    } yield recorded.iterator().asScala.toList
    task.sync()
  }

  /** Whole prompt as text, with wire call ids replaced by the position
    * they occupy so two turns that minted different ids compare. */
  private def render(call: ProviderCall): String = {
    val ids = call.messages.collect {
      case a: ProviderMessage.Assistant => a.toolCalls.map(_.id)
    }.flatten.distinct
    val normalize: String => String = text =>
      ids.zipWithIndex.foldLeft(text) { case (acc, (id, i)) => acc.replace(id, s"<call-$i>") }

    val trail = call.messages.map {
      case ProviderMessage.System(c)         => s"SYSTEM\n$c"
      case ProviderMessage.User(blocks)      => s"USER\n${blocks.mkString("\n")}"
      case ProviderMessage.Assistant(c, tcs) =>
        s"ASSISTANT text=<$c> calls=${tcs.map(t => s"${t.id}:${t.name}${t.argsJson}").mkString(" | ")}"
      case ProviderMessage.ToolResult(id, c) => s"TOOLRESULT $id\n$c"
      case other                             => other.toString
    }.mkString("\n----\n")

    normalize(
      s"##### SYSTEM #####\n${call.system}\n" +
        s"##### VOLATILE TAIL #####\n${call.systemVolatile}\n" +
        s"##### ROSTER #####\n${call.roster.tools.map(_.name.value).mkString(", ")}\n" +
        s"##### MESSAGES #####\n$trail")
  }

  private def dump(name: String, text: String): Unit = {
    Files.createDirectories(outDir)
    Files.writeString(outDir.resolve(name), text)
  }

  /** Every probe called so far answered by its own result, in order.
    * Equivalence alone is satisfied by two prompts corrupted the same
    * way; completeness is what says the prompt is right. */
  private def assertComplete(label: String, call: ProviderCall, rendered: String): Unit = {
    val calls = call.messages.collect { case a: ProviderMessage.Assistant => a.toolCalls }.flatten
    val byId = call.messages.collect { case ProviderMessage.ToolResult(id, content) => id -> content }.toMap
    withClue(s"$label issued ${calls.map(_.argsJson)}, expected one call per probe\n$rendered\n") {
      calls.count(_.name == ProbeReadTool.name.value) shouldBe probes.size
    }
    probes.foreach { probe =>
      val id = calls.find(_.argsJson.contains(s""""$probe"""")).map(_.id)
      withClue(s"$label has no call for '$probe'\n$rendered\n") {
        id shouldBe defined
      }
      val content = byId.getOrElse(id.get, "")
      withClue(s"$label answered '$probe' with '$content'\n$rendered\n") {
        content should include(ProbeReadTool.resultTextFor(probe))
        content should not include "result did not reach this turn"
      }
    }
  }

  "the prompt replaying settled tool calls" should {
    "carry every call with its own result, however the calls arrived" in {
      val batched = runTurn("equiv-batched", n =>
        if (n == 1) probes.zipWithIndex.map { case (p, i) => CallId(s"batch-$i") -> p } else Nil)
      val serial = runTurn("equiv-serial", n =>
        if (n <= probes.size) List(CallId(s"serial-${n - 1}") -> probes(n - 1)) else Nil)

      // The first prompt carrying every settled call: right after the
      // batch, and right after the last of the singles.
      val afterBatch = render(batched(1))
      val afterSerial = render(serial(probes.size))
      dump("after-batch.txt", afterBatch)
      dump("after-serial.txt", afterSerial)

      assertComplete("the batched turn", batched(1), afterBatch)
      assertComplete("the serial turn", serial(probes.size), afterSerial)

      withClue(s"dumps written under ${outDir.toAbsolutePath}\n") {
        afterBatch shouldBe afterSerial
      }
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
