package spec

import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.conversation.{Conversation, ProgressContext, ProgressTaskSelector}
import sigil.db.Model
import sigil.event.{Message, MessageRole}
import sigil.participant.DefaultAgentParticipant
import sigil.provider.{GenerationSettings, Instructions}
import sigil.signal.EventState
import sigil.tool.core.CoreTools
import sigil.tool.model.ResponseContent

/**
 * #320 — progress reflection judges the substantive objective, never a
 * bare continuation ("Proceed"). #321 — the cancel-capable reflection
 * resolves the agent's own model, not the cheapest routed candidate.
 */
class ProgressReflectionTaskSpec extends AnyWordSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val convId = Conversation.id("progress-reflection")
  private def userMsg(text: String, ts: Long): Message = Message(
    participantId = TestUser,
    conversationId = convId,
    topicId = TestTopicEntry.id,
    role = MessageRole.Standard,
    content = Vector(ResponseContent.Text(text)),
    state = EventState.Complete,
    timestamp = Timestamp(ts)
  )
  private def textOf(m: Message): String = m.content.collect { case ResponseContent.Text(t) => t }.mkString

  "ProgressTaskSelector (#320)" should {
    "recognise bare continuations" in {
      ProgressTaskSelector.isContinuation("Proceed") shouldBe true
      ProgressTaskSelector.isContinuation("proceed.") shouldBe true
      ProgressTaskSelector.isContinuation("  YES ") shouldBe true
      ProgressTaskSelector.isContinuation("go on") shouldBe true
      ProgressTaskSelector.isContinuation("ok!") shouldBe true
    }

    "not flag a substantive request as a continuation" in {
      ProgressTaskSelector.isContinuation("remove all references to bugs") shouldBe false
      ProgressTaskSelector.isContinuation("proceed with deleting the bug files") shouldBe false
    }

    "select the substantive task and surface the latest continuation as the directive" in {
      val task = userMsg("remove all references to bugs", ts = 100)
      val cont = userMsg("Proceed", ts = 200)
      val (substantive, directive) = ProgressTaskSelector.select(List(task, cont), textOf)
      substantive.map(_._id) shouldBe Some(task._id)
      directive.map(_._id) shouldBe Some(cont._id)
    }

    "fall back to the latest message when every message is a continuation" in {
      val a = userMsg("yes", 100)
      val b = userMsg("ok", 200)
      val (substantive, directive) = ProgressTaskSelector.select(List(a, b), textOf)
      substantive.map(_._id) shouldBe Some(b._id)
      directive shouldBe None
    }
  }

  "renderCheckpointPrompt (#320)" should {
    "render the substantive objective as the request, not the continuation" in {
      val ctx = ProgressContext(
        userTask = Some("remove all references to bugs"),
        toolHistory = List("grep → OK", "dispatch_workers → OK"),
        latestDirective = Some("Proceed")
      )
      val prompt = TestSigil.renderCheckpointPrompt(ctx, priorStatus = None, iteration = 5)
      prompt should include("The user's request:\n\"remove all references to bugs\"")
      prompt should not include "The user's request:\n\"Proceed\""
      prompt should include("Proceed") // present only as the continuation line
    }
  }

  "progressReflectionModelFor (#321)" should {
    "resolve the agent's own model, not a routed cheaper candidate" in {
      val modelId = Model.id("test", "reflection-agent")
      TestSigil.testModel(modelId)
      val agent = DefaultAgentParticipant(
        id = TestAgent,
        modelId = modelId,
        toolNames = CoreTools.coreToolNames,
        instructions = Instructions(),
        generationSettings = GenerationSettings()
      )
      TestSigil.progressReflectionModelFor(agent) shouldBe modelId
    }
  }
}
