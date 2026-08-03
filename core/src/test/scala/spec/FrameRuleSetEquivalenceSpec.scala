package spec

import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.conversation.{ContextFrame, Conversation, FrameBuilder}
import sigil.event.*
import sigil.signal.EventState
import sigil.tool.ToolName
import sigil.tool.model.ResponseContent
import spice.net.URL

/**
 * `FrameBuilder` has one event→frame rule set. The live settle path
 * (inline `computeFrame` + cross-event pairing) and the fold path
 * (`FrameBuilder.build`) must project an identical frame vector for the
 * same event sequence — a divergence between them is how a rebuilt or
 * imported conversation silently stops matching what production sees.
 */
class FrameRuleSetEquivalenceSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val convId: Id[Conversation] = Conversation.id(s"frame-equiv-${rapid.Unique()}")

  private def at(i: Int): Timestamp = Timestamp(5_000_000L + i)

  private val userMessage = Message(
    participantId = TestUser,
    conversationId = convId,
    topicId = TestTopicId,
    content = Vector(ResponseContent.Text("find the config")),
    state = EventState.Complete
  ).copy(timestamp = at(1))

  private val imageMessage = Message(
    participantId = TestUser,
    conversationId = convId,
    topicId = TestTopicId,
    content = Vector(
      ResponseContent.Text("here's a screenshot"),
      ResponseContent.Image(URL.parse("https://example.com/shot.png"))
    ),
    state = EventState.Complete
  ).copy(timestamp = at(2))

  private val invoke = ToolInvoke(
    toolName = ToolName.parse("grep").fold(sys.error, identity),
    participantId = TestAgent,
    conversationId = convId,
    topicId = TestTopicId,
    state = EventState.Complete
  ).copy(timestamp = at(3))

  private val toolResult = Message(
    participantId = TestAgent,
    conversationId = convId,
    topicId = TestTopicId,
    role = MessageRole.Tool,
    content = Vector(ResponseContent.Text("config.scala:12")),
    state = EventState.Complete,
    origin = Some(invoke._id)
  ).copy(timestamp = at(4))

  private val directiveInvoke = sigil.orchestrator.SyntheticDiagnostic
    .invoke(sigil.orchestrator.Directive.RefusalChallenge, TestAgent, convId, TestTopicId)
    .copy(timestamp = at(5))

  private val directiveResult = Message(
    participantId = TestAgent,
    conversationId = convId,
    topicId = TestTopicId,
    role = MessageRole.Tool,
    content = Vector(ResponseContent.Text(sigil.orchestrator.Directive.RefusalChallenge.render)),
    state = EventState.Complete,
    visibility = MessageVisibility.Agents,
    origin = Some(directiveInvoke._id)
  ).copy(timestamp = at(6))

  // The shape production actually persists for a synthetic diagnostic:
  // `SyntheticDiagnostic.apply` stamps the reason onto the invoke's own
  // outcome + summary, so the invoke is SETTLED (outcome = Failure)
  // before its paired Tool-role Message arrives.
  private val settledDirective: List[Event] =
    sigil.orchestrator.SyntheticDiagnostic(
      sigil.orchestrator.Directive.PlainTextReply("here is my answer in prose"),
      TestAgent, convId, TestTopicId,
      disposition = MessageDisposition.Failure(recoverable = true)
    ).collect { case e: Event => e }

  private val settledDirectiveInvoke: ToolInvoke =
    settledDirective.collectFirst { case ti: ToolInvoke => ti }.get.copy(timestamp = at(8))

  private val settledDirectiveResult: Message =
    settledDirective.collectFirst { case m: Message => m }.get.copy(timestamp = at(9))

  private val agentReply = Message(
    participantId = TestAgent,
    conversationId = convId,
    topicId = TestTopicId,
    content = Vector(ResponseContent.Text("found it")),
    state = EventState.Complete
  ).copy(timestamp = at(10))

  private val sequence: List[Event] =
    List(userMessage, imageMessage, invoke, toolResult, directiveInvoke, directiveResult,
      settledDirectiveInvoke, settledDirectiveResult, agentReply)

  "The one event→frame rule set" should {
    "project the same frames through the live path and a from-scratch fold" in {
      for {
        _    <- TestSigil.publishHistorical(sequence, convId)
        live <- TestSigil.framesFor(convId)
      } yield {
        val folded = FrameBuilder.build(sequence)
        live shouldBe folded
      }
    }

    "lift message images onto the frame in the fold path" in {
      val folded = FrameBuilder.build(sequence)
      val withImages = folded.collect { case t: ContextFrame.Text if t.images.nonEmpty => t }
      withImages should have size 1
      withImages.head.images.map(_.toString) shouldBe List("https://example.com/shot.png")
    }

    "fold a tool result into its parent call rather than appending a frame" in {
      val folded = FrameBuilder.build(sequence)
      val calls = folded.collect { case tc: ContextFrame.ToolCall => tc }
      calls should have size 3
      calls.foreach(_.state shouldBe a[sigil.conversation.ToolCallState.Complete])
      folded.collect { case t: ContextFrame.Text => t.content } should not contain
        "[framework: orphan tool result for callId=" + invoke._id.value + "]"
    }

    "leave a SETTLED synthetic diagnostic's frame alone rather than orphaning its paired message" in {
      // The invoke already carries the directive prose on its own
      // outcome + summary. Its paired Tool-role Message must fold (or
      // no-op) — never append a `[framework: orphan tool result …]`
      // frame restating the same directive.
      val folded = FrameBuilder.build(List(settledDirectiveInvoke, settledDirectiveResult))
      folded should have size 1
      val tc = folded.head.asInstanceOf[ContextFrame.ToolCall]
      tc.callId shouldBe settledDirectiveInvoke._id
      tc.state shouldBe a[sigil.conversation.ToolCallState.Complete]
      folded.collect { case t: ContextFrame.Text => t.content } shouldBe empty
    }

    "produce no orphan-result frames anywhere in the sequence" in {
      val folded = FrameBuilder.build(sequence)
      folded.collect { case t: ContextFrame.Text => t.content }
        .filter(_.contains("orphan tool result")) shouldBe empty
    }

    "still surface a GENUINE orphan — a result whose call has no frame at all" in {
      val stray = Message(
        participantId = TestAgent,
        conversationId = convId,
        topicId = TestTopicId,
        role = MessageRole.Tool,
        content = Vector(ResponseContent.Text("late result")),
        state = EventState.Complete,
        origin = Some(Id[Event]("never-invoked"))
      )
      val folded = FrameBuilder.build(List(stray))
      folded should have size 1
      folded.head.asInstanceOf[ContextFrame.Text].content should include ("orphan tool result")
      folded.head.visibility shouldBe MessageVisibility.Agents
    }

    "degrade rather than throw on a Tool-role event with no origin" in {
      val malformed = Message(
        participantId = TestAgent,
        conversationId = convId,
        topicId = TestTopicId,
        role = MessageRole.Tool,
        content = Vector(ResponseContent.Text("orphaned")),
        state = EventState.Complete
      )
      val folded = FrameBuilder.build(List(malformed))
      folded should have size 1
      folded.head shouldBe a[ContextFrame.Text]
      folded.head.visibility shouldBe MessageVisibility.Agents
    }
  }
}
