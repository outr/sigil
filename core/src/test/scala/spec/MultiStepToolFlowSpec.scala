package spec

import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.{AgentState, Event, Message, ToolInvoke}
import sigil.participant.DefaultAgentParticipant
import sigil.provider.{GenerationSettings, Instructions}
import sigil.provider.llamacpp.LlamaCppProvider
import sigil.signal.EventState
import sigil.tool.ToolName
import sigil.tool.core.CoreTools
import sigil.tool.model.ResponseContent

import scala.concurrent.duration.*

/**
 * Demonstrates the multi-step tool-flow gap. RED on purpose.
 *
 * Setup:
 *   - One data-returning tool ([[GetMagicNumberTool]]) emitting
 *     `Message(participantId = caller)` — the same shape the
 *     existing core tools (`LookupTool`, `RespondTool`)
 *     use.
 *   - A user prompt that requires the agent to call
 *     `get_magic_number`, *read* its result from context, and *then*
 *     call `respond` with the number — i.e. a real multi-step flow
 *     that can't fit in a single LLM round (the model has to see the
 *     tool's output before it can compose the respond text).
 *
 * Expected (passing) behavior: the agent's reply contains "42".
 *
 * Once-failing demonstration of the multi-step gap. Closed by the
 * `Event.role` refactor: tools that should re-trigger the agent set
 * `role = MessageRole.Tool` on their emitted events; `TriggerFilter` and
 * `FrameBuilder` route on the role; the loop advances iteration by
 * iteration as required.
 */
class MultiStepToolFlowSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  override implicit protected val testTimeout: FiniteDuration = 2.minutes

  TestSigil.initFor(getClass.getSimpleName)
  TestSigil.setProvider(LlamaCppProvider(TestSigil, TestSigil.llamaCppHost).singleton)

  private val modelId: Id[Model] = Model.id("qwen3.5-9b-q4_k_m")

  "Sigil multi-step tool flow" should {
    "fire a data-returning tool, read its result from context, and respond with the value" in {
      val agent = DefaultAgentParticipant(
        id = TestAgent,
        modelId = modelId,
        toolNames = List(ToolName("get_magic_number")) ++ CoreTools.coreToolNames,
        instructions = Instructions(),
        generationSettings = GenerationSettings(maxOutputTokens = Some(2000), temperature = Some(0.0))
      )
      val convId = Conversation.id(s"multistep-${rapid.Unique()}")
      val conv = Conversation(
        topics = List(TestTopicEntry),
        _id = convId,
        participants = List(agent)
      )
      val now = Timestamp()
      val userMsg = Message(
        participantId = TestUser,
        conversationId = convId,
        topicId = TestTopicId,
        content = Vector(ResponseContent.Text(
          "Call the `get_magic_number` tool to find out the magic number, then call `respond` to tell me what number you got."
        )),
        state = EventState.Complete,
        timestamp = now
      )

      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _ <- TestSigil.publish(userMsg)
        _ <- waitForAgentTurn(convId, after = now.value, timeout = 90.seconds)
        all <- TestSigil.withDB(_.events.transaction(_.list))
      } yield {
        val window = all.filter(e =>
          e.conversationId == convId
            && e.timestamp.value >= now.value
            && e.state == EventState.Complete
        ).sortBy(_.timestamp.value)
        val toolInvokes = window.collect { case ti: ToolInvoke => ti }
        val toolNames = toolInvokes.map(_.toolName.value).toSet
        val respondInvokes = toolInvokes.filter(_.toolName.value == "respond")
        // The respond tool MUST have been called for this to be a real
        // multi-step flow. Currently fails: the agent fires
        // `get_magic_number`, the tool's Message-from-self doesn't
        // re-trigger the loop, the agent never gets the chance to call
        // `respond`, and the user never receives an answer.
        toolNames should contain("get_magic_number")
        respondInvokes should not be empty
      }
    }
  }

  /** Poll `SigilDB.events` until either an `AgentState(Complete)` for
    * `convId` newer than `after` exists, or the deadline passes. We
    * don't fail on no-AgentState — for the bug-demonstrating case the
    * agent loop SHOULD release a Complete AgentState even when it
    * fails to respond, so this just bounds the wait. */
  private def waitForAgentTurn(convId: Id[Conversation], after: Long, timeout: FiniteDuration): Task[Unit] = {
    val deadline = System.currentTimeMillis() + timeout.toMillis
    def loop: Task[Unit] = TestSigil.withDB(_.events.transaction(_.list)).flatMap { all =>
      val settled = all.exists {
        case a: AgentState if a.conversationId == convId && a.timestamp.value >= after && a.state == EventState.Complete => true
        case _ => false
      }
      if (settled) Task.unit
      else if (System.currentTimeMillis() < deadline) Task.sleep(200.millis).flatMap(_ => loop)
      else Task.unit   // bound the wait; the assertion inspects what's there.
    }
    loop
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
