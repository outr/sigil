package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.db.Model
import sigil.provider.{
  GenerationSettings, MessageContent, ProviderCall, ProviderEvent,
  ProviderMessage, ReasoningMode, ToolChoice
}
import sigil.provider.digitalocean.DigitalOceanProvider
import sigil.tool.Tool
import sigil.tool.core.{FindCapabilityTool, NoResponseTool, RespondTool}
import sigil.tool.model.RespondInput

/**
 * Live reliability characterization for Kimi-K2.5 hosted on
 * DigitalOcean Inference (`https://inference.do-ai.run`). Drives the
 * provider directly with [[ProviderCall]] payloads and inspects the
 * emitted [[ProviderEvent]] stream — no agent loop, no orchestrator,
 * no DB.
 *
 * **Self-skips** when `DO_ACCESS_KEY` is not set in the environment,
 * so CI passes cleanly without the credential.
 *
 * Bypasses [[DigitalOceanLiveSupport.runGated]] (currently
 * unconditionally disabled with the comment "pending DO/Kimi-K2.5
 * stability fixes"). The purpose of this spec is to surface exactly
 * the reliability gaps that gate was hiding so we can fix them.
 *
 * Each scenario exercises one axis: tool-call compliance under each
 * [[ReasoningMode]], a zero-parameter tool call (sigil #260 territory),
 * and strict-mode arg shaping. A scenario failing is signal, not noise.
 */
class DigitalOceanKimiLiveSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val apiKeyOpt: Option[String] = sys.env.get("DO_ACCESS_KEY").filter(_.nonEmpty)

  // Constructed lazily so the spec doesn't NPE if the key is absent —
  // every test gates on `skipUnlessLive()` before touching `provider`.
  private lazy val provider: DigitalOceanProvider =
    DigitalOceanProvider(apiKeyOpt.get, TestSigil)

  private val modelId: Id[Model] = Model.id("digitalocean", "kimi-k2.5")

  private def skipUnlessLive(): Unit =
    if (apiKeyOpt.isEmpty)
      cancel("DO_ACCESS_KEY not set — skipping live DigitalOcean Kimi-K2.5 spec")

  /** Build a minimal single-turn ProviderCall — one user message,
    * supplied tools and reasoning mode, deterministic settings. */
  private def call(
    system: String,
    userMessage: String,
    tools: Vector[Tool],
    toolChoice: ToolChoice,
    reasoning: ReasoningMode,
    maxTokens: Int = 400
  ): ProviderCall = ProviderCall(
    modelId      = modelId,
    system       = system,
    messages     = Vector(ProviderMessage.User(Vector(MessageContent.Text(userMessage)))),
    tools        = tools,
    builtInTools = Set.empty,
    toolChoice   = toolChoice,
    generationSettings = GenerationSettings(
      maxOutputTokens = Some(maxTokens),
      temperature     = Some(0.0),
      reasoningMode   = reasoning
    )
  )

  /** Drain the provider's event stream and return everything emitted —
    * tests inspect the full event sequence so a `ProviderEvent.Error`
    * surfaces as a clear assertion failure rather than a silent gap. */
  private def runScenario(pc: ProviderCall) =
    provider.call(pc).toList

  "DigitalOcean Kimi-K2.5 live reliability" should {

    "complete a respond tool call with ReasoningMode.Auto" in {
      skipUnlessLive()
      val pc = call(
        system      = "Reply to the user via the `respond` tool. Keep it brief.",
        userMessage = "Say hello.",
        tools       = Vector(RespondTool),
        toolChoice  = ToolChoice.Required,
        reasoning   = ReasoningMode.Auto
      )
      runScenario(pc).map { events =>
        val errors    = events.collect { case e: ProviderEvent.Error => e }
        val completes = events.collect { case c: ProviderEvent.ToolCallComplete => c }
        withClue(s"errors: ${errors.mkString("; ")}: ") { errors shouldBe empty }
        completes should not be empty
        completes.head.input shouldBe a[RespondInput]
      }
    }

    "complete a respond tool call with ReasoningMode.On (/think)" in {
      skipUnlessLive()
      val pc = call(
        system      = "Reply to the user via the `respond` tool. Keep it brief.",
        userMessage = "What is 2+2?",
        tools       = Vector(RespondTool),
        toolChoice  = ToolChoice.Required,
        reasoning   = ReasoningMode.On,
        maxTokens   = 600
      )
      runScenario(pc).map { events =>
        val errors    = events.collect { case e: ProviderEvent.Error => e }
        val completes = events.collect { case c: ProviderEvent.ToolCallComplete => c }
        withClue(s"errors: ${errors.mkString("; ")}: ") { errors shouldBe empty }
        completes should not be empty
        completes.head.input shouldBe a[RespondInput]
      }
    }

    "complete a respond tool call with ReasoningMode.Off (/no_think)" in {
      skipUnlessLive()
      val pc = call(
        system      = "Reply to the user via the `respond` tool. Keep it brief.",
        userMessage = "Say hello.",
        tools       = Vector(RespondTool),
        toolChoice  = ToolChoice.Required,
        reasoning   = ReasoningMode.Off
      )
      runScenario(pc).map { events =>
        val errors    = events.collect { case e: ProviderEvent.Error => e }
        val completes = events.collect { case c: ProviderEvent.ToolCallComplete => c }
        withClue(s"errors: ${errors.mkString("; ")}: ") { errors shouldBe empty }
        completes should not be empty
        completes.head.input shouldBe a[RespondInput]
      }
    }

    "decode a tool call whose Input has all-optional fields (no_response)" in {
      // Exercises the #260 path on the live DO wire — the model can
      // legitimately call `no_response` with no args, which Anthropic
      // delivered as null and OpenAI as `"{}"`. DO Kimi's behavior
      // here is what we want to confirm.
      skipUnlessLive()
      val pc = call(
        system      = "If the user has nothing to discuss, call `no_response` (no arguments needed). Otherwise call `respond`.",
        userMessage = "Nothing for now.",
        tools       = Vector(NoResponseTool, RespondTool),
        toolChoice  = ToolChoice.Required,
        reasoning   = ReasoningMode.Auto
      )
      runScenario(pc).map { events =>
        val errors    = events.collect { case e: ProviderEvent.Error => e }
        val completes = events.collect { case c: ProviderEvent.ToolCallComplete => c }
        // Either tool is acceptable — the assertion is just that the
        // call round-trips without a decode error.
        withClue(s"errors: ${errors.mkString("; ")}: ") { errors shouldBe empty }
        completes should not be empty
      }
    }

    "complete a respond call exercising every RespondInput field (multi-arg strict-mode stress)" in {
      // RespondInput has 4 required string/enum fields + 2 optional
      // (endsTurn, keywords). Strict-mode shaping has to handle the
      // mix — required-string, required-enum (`ResponseDisposition`),
      // optional-bool, optional-array-of-strings. If the model emits a
      // JSON array (sigil #171) or misses a required field, the
      // accumulator's decode catches it as an Error event.
      skipUnlessLive()
      val pc = call(
        system      = "Reply via the `respond` tool. Set topicLabel, topicSummary, content, and disposition = \"Success\". Keep content brief.",
        userMessage = "Tell me one fun fact about octopuses.",
        tools       = Vector(RespondTool),
        toolChoice  = ToolChoice.Required,
        reasoning   = ReasoningMode.Auto,
        maxTokens   = 500
      )
      runScenario(pc).map { events =>
        val errors    = events.collect { case e: ProviderEvent.Error => e }
        val completes = events.collect { case c: ProviderEvent.ToolCallComplete => c }
        withClue(s"errors: ${errors.mkString("; ")}: ") { errors shouldBe empty }
        completes should not be empty
        val r = completes.head.input.asInstanceOf[RespondInput]
        // Each required string field must be populated.
        r.topicLabel.trim should not be empty
        r.topicSummary.trim should not be empty
        r.content.trim should not be empty
      }
    }

    "pick a tool from a multi-tool roster" in {
      // Three tools, ambiguous-ish prompt. The framework's job: emit a
      // valid typed call for whichever tool the model picks. The
      // model's job: pick something coherent. We assert only on the
      // framework's side (no decode error, typed input emerged).
      skipUnlessLive()
      val pc = call(
        system      = "You have three tools available: `respond` for replies, `no_response` for silence, " +
                      "`find_capability` for discovering additional tools. Pick one.",
        userMessage = "What is 5 minus 2?",
        tools       = Vector(RespondTool, NoResponseTool, FindCapabilityTool),
        toolChoice  = ToolChoice.Required,
        reasoning   = ReasoningMode.Auto
      )
      runScenario(pc).map { events =>
        val errors    = events.collect { case e: ProviderEvent.Error => e }
        val completes = events.collect { case c: ProviderEvent.ToolCallComplete => c }
        withClue(s"errors: ${errors.mkString("; ")}: ") { errors shouldBe empty }
        completes should not be empty
      }
    }

    "complete a multi-turn conversation (assistant message threaded in history)" in {
      // Mock a prior turn into history — first-user + first-assistant
      // (text-only) — then a follow-up user. Verifies the wire
      // accepts multi-message histories and the model can still emit
      // a typed tool call against it.
      skipUnlessLive()
      val pc = ProviderCall(
        modelId      = modelId,
        system       = "Reply via the `respond` tool.",
        messages     = Vector(
          ProviderMessage.User(Vector(MessageContent.Text("My favorite color is blue."))),
          ProviderMessage.Assistant(content = "Got it — blue is a great color!", toolCalls = Nil),
          ProviderMessage.User(Vector(MessageContent.Text("What did I just tell you my favorite color was?")))
        ),
        tools        = Vector(RespondTool),
        builtInTools = Set.empty,
        toolChoice   = ToolChoice.Required,
        generationSettings = GenerationSettings(
          maxOutputTokens = Some(400),
          temperature     = Some(0.0),
          reasoningMode   = ReasoningMode.Auto
        )
      )
      runScenario(pc).map { events =>
        val errors    = events.collect { case e: ProviderEvent.Error => e }
        val completes = events.collect { case c: ProviderEvent.ToolCallComplete => c }
        withClue(s"errors: ${errors.mkString("; ")}: ") { errors shouldBe empty }
        completes should not be empty
        val r = completes.head.input.asInstanceOf[RespondInput]
        r.content.trim should not be empty
      }
    }

    "complete a tool call under a realistic agent system prompt (~500 tokens)" in {
      // Closer to a real downstream-app system prompt: persona, rules,
      // tool-usage policy. Verifies surface compliance doesn't degrade
      // when the system block is substantial.
      skipUnlessLive()
      val longSystem =
        """You are Helper, an assistant deployed by Acme Corp to help users navigate
          |their account dashboard. Your responsibilities:
          |
          |- Answer questions about account features.
          |- Direct users to the right page when they ask.
          |- Be concise, friendly, and accurate.
          |- Never invent URLs or feature names.
          |- If you don't know, say so.
          |
          |You have one tool: `respond`. Use it for every reply. Populate `topicLabel`,
          |`topicSummary`, `content`, and `disposition: "Success"`. Set `endsTurn: true`
          |unless you genuinely need another turn.
          |
          |Tone: helpful, professional, never patronising. Keep replies under three
          |sentences when possible.""".stripMargin
      val pc = call(
        system      = longSystem,
        userMessage = "How do I update my email address?",
        tools       = Vector(RespondTool),
        toolChoice  = ToolChoice.Required,
        reasoning   = ReasoningMode.Auto,
        maxTokens   = 400
      )
      runScenario(pc).map { events =>
        val errors    = events.collect { case e: ProviderEvent.Error => e }
        val completes = events.collect { case c: ProviderEvent.ToolCallComplete => c }
        withClue(s"errors: ${errors.mkString("; ")}: ") { errors shouldBe empty }
        completes should not be empty
        completes.head.input shouldBe a[RespondInput]
      }
    }

    "complete a reasoning-heavy turn under a tight token budget (#161 provocation)" in {
      // Reasoning On + small max_tokens on a problem that benefits from
      // step-by-step thinking. Stresses the deployment's tendency to
      // burn the budget on reasoning without producing a tool call —
      // the documented #161 mode. If the framework correctly fits the
      // turn, the test passes; if degeneration fires, the wire layer
      // raises ProviderStreamException("empty_budget_burn") and the
      // test fails loudly with that message — exactly the signal we
      // want to surface.
      skipUnlessLive()
      val pc = call(
        system      = "Reply via the `respond` tool. Reason step-by-step where useful, then give the answer.",
        userMessage = "If a clock loses 3 minutes every hour and starts at 12:00 noon, " +
                      "what time will it show after 8 real hours?",
        tools       = Vector(RespondTool),
        toolChoice  = ToolChoice.Required,
        reasoning   = ReasoningMode.On,
        maxTokens   = 250
      )
      runScenario(pc).map { events =>
        val errors    = events.collect { case e: ProviderEvent.Error => e }
        val completes = events.collect { case c: ProviderEvent.ToolCallComplete => c }
        withClue(s"errors: ${errors.mkString("; ")}: ") { errors shouldBe empty }
        completes should not be empty
        completes.head.input shouldBe a[RespondInput]
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
