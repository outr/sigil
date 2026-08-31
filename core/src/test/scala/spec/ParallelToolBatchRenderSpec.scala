package spec

import fabric.Json
import fabric.io.JsonParser
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.conversation.{ContextFrame, Conversation, ToolCallState, TurnInput}
import sigil.db.Model
import sigil.event.Event
import sigil.provider.anthropic.AnthropicProvider
import sigil.provider.deepseek.DeepSeekProvider
import sigil.provider.{ConversationMode, ConversationRequest, GenerationSettings, Instructions, Provider}
import sigil.tool.ToolName
import sigil.tool.core.CoreTools

/**
 * A completion that emits several tool calls at once must reach the wire
 * intact: every call present exactly once, every call answered by a
 * result carrying its own id and its own content, and no call left
 * without its answer immediately behind it.
 *
 * Each call replays as its own assistant turn followed by its own
 * result. Grouping the batch into a single assistant turn carrying every
 * call was tried and withdrawn — it measurably worsened re-issue
 * behavior in the field — so these cases pin the pairing and attribution
 * invariants, which hold whatever the grouping.
 *
 * Covered for both wire families that ship: Anthropic's content-block
 * messages and the OpenAI chat-completions `tool_calls` / `role: "tool"`
 * shape, since the decision is made upstream of either in the shared
 * frame renderer.
 */
class ParallelToolBatchRenderSpec extends AnyWordSpec with Matchers {

  TestSigil.initFor(getClass.getSimpleName)

  private val conversationId: Id[Conversation] = Conversation.id("parallel-batch-conv")

  private val anthropicModelId: Id[Model] = Model.id("anthropic", "claude-haiku-4-5")
  TestSigil.testModel(anthropicModelId)
  private val deepSeekModelId: Id[Model] = Model.id("deepseek", "deepseek-chat")
  TestSigil.testModel(deepSeekModelId)

  private val anthropic: Provider = AnthropicProvider(apiKey = "sk-ant-test-placeholder", sigilRef = TestSigil)
  private val deepSeek: Provider = DeepSeekProvider(apiKey = "sk-test-placeholder", sigilRef = TestSigil)

  /**
   * The three calls of the batch: wire id -> (args marker, result marker).
   */
  private val batch: List[(String, String, String)] = List(
    ("call_alpha", "ARGS_MARKER_ALPHA", "RESULT_MARKER_ALPHA"),
    ("call_bravo", "ARGS_MARKER_BRAVO", "RESULT_MARKER_BRAVO"),
    ("call_charlie", "ARGS_MARKER_CHARLIE", "RESULT_MARKER_CHARLIE")
  )

  private def batchFrames: Vector[ContextFrame] = {
    val calls = batch.map { case (wireId, argsMarker, resultMarker) =>
      val callId = Id[Event](s"invoke-$wireId")
      ContextFrame.ToolCall(
        toolName = ToolName("search_items"),
        argsJson = s"""{"query":"$argsMarker"}""",
        callId = callId,
        participantId = TestAgent,
        sourceEventId = callId,
        wireCallId = Some(wireId),
        state = ToolCallState.Complete(resultMarker)
      )
    }
    ContextFrame.Text(
      content = "find the three items",
      participantId = TestUser,
      sourceEventId = Id[Event]("batch-seed")
    ) +: calls.toVector
  }

  private def bodyOf(provider: Provider, modelId: Id[Model]): String = {
    val request = ConversationRequest(
      conversationId = conversationId,
      model = TestSigil.testModel(modelId),
      instructions = Instructions(),
      turnInput = TurnInput(conversationId = conversationId, frames = batchFrames),
      currentMode = ConversationMode,
      currentTopic = TestTopicEntry,
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0)),
      tools = CoreTools.all,
      chain = List(TestUser, TestAgent)
    )
    provider.requestConverter(request).sync().content match {
      case Some(c: spice.http.content.StringContent) => c.value
      case _ => ""
    }
  }

  private def messagesOf(body: String): Vector[Json] =
    JsonParser(body).get("messages").map(_.asVector).getOrElse(Vector.empty)

  private def roleOf(message: Json): String = message.get("role").map(_.asString).getOrElse("")

  /**
   * Anthropic content blocks of the given `type` on one message.
   */
  private def blocksOf(message: Json, blockType: String): Vector[Json] =
    message.get("content").toVector
      .flatMap(c => scala.util.Try(c.asVector).getOrElse(Vector.empty))
      .filter(_.get("type").map(_.asString).contains(blockType))

  "Anthropic rendering of a parallel tool-call batch" should {
    lazy val messages = messagesOf(bodyOf(anthropic, anthropicModelId))

    "answer every tool_use id with a tool_result carrying that exact id" in {
      val useIds = messages.flatMap(m => blocksOf(m, "tool_use")).flatMap(_.get("id").map(_.asString))
      val resultIds = messages.flatMap(m => blocksOf(m, "tool_result")).flatMap(_.get("tool_use_id").map(_.asString))
      useIds should contain theSameElementsAs batch.map(_._1)
      withClue(s"tool_use ids $useIds unanswered by tool_result ids $resultIds: ") {
        useIds.toSet.diff(resultIds.toSet) shouldBe empty
      }
    }

    "carry every call of the batch exactly once" in {
      val useIds = messages.flatMap(m => blocksOf(m, "tool_use")).flatMap(_.get("id").map(_.asString))
      withClue(s"a call was rendered more than once: $useIds ") {
        useIds.distinct.size shouldBe useIds.size
      }
    }

    "answer each assistant turn's tool_use ids in the IMMEDIATELY following user message" in
      messages.zipWithIndex.foreach { case (m, idx) =>
        val useIds = blocksOf(m, "tool_use").flatMap(_.get("id").map(_.asString)).toSet
        if (useIds.nonEmpty) {
          val next = messages.lift(idx + 1)
          withClue(s"assistant message $idx ($useIds) is followed by ${next.map(roleOf).getOrElse("nothing")}: ") {
            next.map(roleOf) shouldBe Some("user")
            val answered = blocksOf(next.get, "tool_result").flatMap(_.get("tool_use_id").map(_.asString)).toSet
            useIds.diff(answered) shouldBe empty
            answered.diff(useIds) shouldBe empty
          }
        }
      }

    "keep each result attributed to its own call" in {
      val byId = messages.flatMap(m => blocksOf(m, "tool_result")).flatMap { b =>
        for {
          id <- b.get("tool_use_id").map(_.asString)
          content <- b.get("content").map(_.toString)
        } yield id -> content
      }.toMap
      batch.foreach { case (wireId, _, resultMarker) =>
        withClue(s"result for $wireId did not carry its own marker: ") {
          byId.get(wireId).exists(_.contains(resultMarker)) shouldBe true
        }
      }
      // No cross-attribution: a call's result carries no other call's marker.
      batch.foreach { case (wireId, _, resultMarker) =>
        val others = batch.map(_._3).filterNot(_ == resultMarker)
        others.foreach { foreign =>
          withClue(s"result for $wireId leaked $foreign: ") {
            byId.get(wireId).exists(_.contains(foreign)) shouldBe false
          }
        }
      }
    }
  }

  "chat-completions rendering of a parallel tool-call batch" should {
    lazy val messages = messagesOf(bodyOf(deepSeek, deepSeekModelId))

    "answer every tool_call id with a role:\"tool\" message carrying that exact id" in {
      val callIds = messages.flatMap { m =>
        m.get("tool_calls").toVector.flatMap(_.asVector).flatMap(_.get("id").map(_.asString))
      }
      val resultIds = messages.filter(roleOf(_) == "tool").flatMap(_.get("tool_call_id").map(_.asString))
      callIds should contain theSameElementsAs batch.map(_._1)
      withClue(s"tool_call ids $callIds unanswered by $resultIds: ") {
        callIds.toSet.diff(resultIds.toSet) shouldBe empty
      }
    }

    "carry every call of the batch exactly once" in {
      val callIds = messages.flatMap { m =>
        m.get("tool_calls").toVector.flatMap(_.asVector).flatMap(_.get("id").map(_.asString))
      }
      withClue(s"a call was rendered more than once: $callIds ") {
        callIds.distinct.size shouldBe callIds.size
      }
    }

    "follow each assistant turn with the results of exactly its own calls" in
      messages.zipWithIndex.foreach { case (m, idx) =>
        val callIds = m.get("tool_calls").toVector.flatMap(_.asVector).flatMap(_.get("id").map(_.asString))
        if (callIds.nonEmpty) {
          val following = messages.slice(idx + 1, idx + 1 + callIds.size)
          withClue(s"assistant message $idx ($callIds) is followed by roles ${following.map(roleOf)}: ") {
            following.map(roleOf) shouldBe Vector.fill(callIds.size)("tool")
            following.flatMap(_.get("tool_call_id").map(_.asString)) shouldBe callIds
          }
        }
      }

    "keep each result attributed to its own call" in {
      val byId = messages.filter(roleOf(_) == "tool").flatMap { m =>
        for {
          id <- m.get("tool_call_id").map(_.asString)
          content <- m.get("content").map(_.asString)
        } yield id -> content
      }.toMap
      batch.foreach { case (wireId, _, resultMarker) =>
        withClue(s"result for $wireId did not carry its own marker: ") {
          byId.get(wireId).exists(_.contains(resultMarker)) shouldBe true
        }
        batch.map(_._3).filterNot(_ == resultMarker).foreach { foreign =>
          withClue(s"result for $wireId leaked $foreign: ") {
            byId.get(wireId).exists(_.contains(foreign)) shouldBe false
          }
        }
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
