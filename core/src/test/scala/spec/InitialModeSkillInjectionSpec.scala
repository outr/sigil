package spec

import fabric.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.conversation.{ActiveSkillSlot, Conversation, ContextFrame, ParticipantProjection, TurnInput}
import sigil.db.Model
import sigil.event.Event
import sigil.provider.{ConversationRequest, GenerationSettings, Instructions, Mode, ProviderRequest}
import sigil.provider.anthropic.AnthropicProvider
import sigil.tool.core.CoreTools

/**
 * Regression for sigil bug #358 — a mode's `skill` body reaches the
 * system prompt only when a `ModeChange` event has fired and settled.
 * A conversation created already in its working mode never publishes a
 * `ModeChange`, so `activeSkills[Mode]` stays empty and the skill body
 * was silently absent — only the `Current mode:` line (which reads
 * `currentMode` directly) rendered, so the prompt *looked* wired.
 *
 * Fix: `renderSystem` folds `currentMode.skill` into the active-skills
 * list directly, state-coupled rather than event-coupled. This spec
 * exercises the no-ModeChange path: a request whose `currentMode`
 * carries a skill but whose participant projection has NO active skills.
 */
class InitialModeSkillInjectionSpec extends AnyWordSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val convId = Conversation.id("initial-mode-skill-conv")
  private val modelId = Model.id("anthropic", "claude-haiku-4-5")

  private val SkillBody =
    "OPERATING INSTRUCTIONS: for multi-step work, compose a typed workflow up front rather than improvising."

  /**
   * A mode whose skill body must reach the prompt however the
   * conversation entered the mode. Used in-memory only (rendering
   * reads `currentMode.skill` directly — no persistence/RW round-trip).
   */
  private case object SkilledMode extends Mode {
    val name = "skilled"
    val description = "test mode carrying an operating-instructions skill"
    override val skill = Some(ActiveSkillSlot("skilled-mode-skill", SkillBody))
  }

  /**
   * TurnInput with an EMPTY participant projection — no activeSkills.
   * This is the "created directly in the mode, no ModeChange" shape.
   */
  private val turn: TurnInput = TurnInput(
    conversationId = convId,
    frames = Vector(
      ContextFrame.Text("user message", TestUser, Id[Event]("seed-1"))
    ),
    participantProjections = Map(TestAgent -> ParticipantProjection.empty(TestAgent, convId))
  )

  private def requestFor(mode: Mode): ProviderRequest =
    ConversationRequest(
      conversationId = convId,
      model = TestSigil.testModel(modelId),
      instructions = Instructions(),
      turnInput = turn,
      currentMode = mode,
      currentTopic = TestTopicEntry,
      generationSettings = GenerationSettings(maxOutputTokens = Some(50)),
      tools = CoreTools.all,
      chain = List(TestUser, TestAgent)
    )

  /**
   * Concatenate every `system` segment's text — the skill lives in the
   * stable cacheable prefix; we don't care which segment carries it.
   */
  private def systemText(provider: AnthropicProvider, mode: Mode): String = {
    val httpReq = provider.requestConverter(requestFor(mode)).sync()
    val body = httpReq.content match {
      case Some(c: spice.http.content.StringContent) => fabric.io.JsonParser(c.value)
      case _ => obj()
    }
    val sys = body("system")
    if (sys.isArr) sys.asVector.map(_("text").asString).mkString("\n")
    else if (sys.isStr) sys.asString
    else ""
  }

  "renderSystem (sigil #358)" should {
    val provider = AnthropicProvider(apiKey = "sk-ant-test", sigilRef = TestSigil)

    "inject the current mode's skill body when no ModeChange ever fired" in {
      val text = systemText(provider, SkilledMode)
      // The Current mode: line always rendered (reads currentMode directly).
      text should include(SkilledMode.description)
      // The bug: the skill BODY was silently absent. It must now appear.
      text should include("== Active skills ==")
      text should include("skilled-mode-skill")
      text should include(SkillBody)
    }
  }
}
