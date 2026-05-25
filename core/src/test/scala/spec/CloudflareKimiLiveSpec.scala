package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.db.Model
import sigil.provider.{
  GenerationSettings, MessageContent, ProviderCall, ProviderEvent,
  ProviderMessage, ReasoningMode, ToolChoice
}
import sigil.provider.cloudflare.CloudflareProvider
import sigil.tool.Tool
import sigil.tool.core.{FindCapabilityTool, NoResponseTool, RespondTool}
import sigil.tool.model.RespondInput

/**
 * Live reliability characterization for Kimi-K2.5 hosted on Cloudflare
 * Workers AI. Mirrors [[DigitalOceanKimiLiveSpec]] scenario-for-scenario
 * so the two sets of results are directly comparable.
 *
 * **Self-skips** when `CLOUDFLARE_AUTH_TOKEN` or `CLOUDFLARE_ACCOUNT_ID`
 * are not set in the environment, so CI without the credentials passes
 * cleanly.
 *
 * Cloudflare's OpenAI-compatible endpoint at
 * `https://api.cloudflare.com/client/v4/accounts/{ACCOUNT_ID}/ai/v1/chat/completions`
 * documents native support for `reasoning_effort`, `tool_choice:
 * required`, and `response_format` — no provider-specific workarounds
 * required in [[CloudflareProvider]]. This spec verifies that promise
 * holds against the live Kimi-K2.5 deployment.
 */
class CloudflareKimiLiveSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val apiTokenOpt: Option[String]  = sys.env.get("CLOUDFLARE_AUTH_TOKEN").filter(_.nonEmpty)
  private val accountIdOpt: Option[String] = sys.env.get("CLOUDFLARE_ACCOUNT_ID").filter(_.nonEmpty)

  // Constructed lazily — every test gates on `skipUnlessLive()` before
  // accessing `provider`, so the `.get` calls are safe.
  private lazy val provider: CloudflareProvider =
    CloudflareProvider(apiTokenOpt.get, accountIdOpt.get, TestSigil)

  private val modelId: Id[Model] = Model.id("cloudflare", "@cf/moonshotai/kimi-k2.5")

  private def skipUnlessLive(): Unit =
    if (apiTokenOpt.isEmpty || accountIdOpt.isEmpty)
      cancel(
        "CLOUDFLARE_AUTH_TOKEN / CLOUDFLARE_ACCOUNT_ID not set — skipping live Cloudflare Kimi-K2.5 spec"
      )

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
    model      = TestSigil.testModel(modelId),
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

  private def runScenario(pc: ProviderCall) =
    provider.call(pc).toList

  /** Reliability multiplier — every scenario runs this many times and
    * must pass each attempt. Catches intermittent degeneration that a
    * single pass would mask. 3 is enough to surface ~30%+ flakiness in
    * one spec run; raise to 5+ for tighter characterization. */
  private val Reps: Int = 3

  /** Sequentially run `task` `n` times and collect every result. */
  private def repeat[A](n: Int)(task: Task[A]): Task[List[A]] = {
    def loop(remaining: Int, acc: List[A]): Task[List[A]] =
      if (remaining <= 0) Task.pure(acc.reverse)
      else task.flatMap(a => loop(remaining - 1, a :: acc))
    loop(n, Nil)
  }

  /** Assert every attempt produced a clean tool-call completion — no
    * Error events, at least one ToolCallComplete per attempt. */
  private def expectAllPassed(attempts: List[List[ProviderEvent]]): org.scalatest.Assertion = {
    val perAttempt = attempts.zipWithIndex.map { case (events, idx) =>
      val errors    = events.collect { case e: ProviderEvent.Error => e }
      val completes = events.collect { case c: ProviderEvent.ToolCallComplete => c }
      val ok = errors.isEmpty && completes.nonEmpty
      val tag = if (ok) "ok" else
        s"FAIL[errors=${errors.size}, completes=${completes.size}: ${errors.take(1).mkString}]"
      s"attempt ${idx + 1}: $tag"
    }
    val passed = perAttempt.count(_.endsWith("ok"))
    withClue(s"$passed/${attempts.size} attempts passed (${perAttempt.mkString("; ")}): ") {
      passed shouldBe attempts.size
    }
  }

  "Cloudflare Kimi-K2.5 live reliability" should {

    "complete a respond tool call with ReasoningMode.Auto" in {
      skipUnlessLive()
      val pc = call(
        system      = "Reply to the user via the `respond` tool. Keep it brief.",
        userMessage = "Say hello.",
        tools       = Vector(RespondTool),
        toolChoice  = ToolChoice.Required,
        reasoning   = ReasoningMode.Auto
      )
      repeat(Reps)(runScenario(pc)).map(expectAllPassed)
    }

    "complete a respond tool call with ReasoningMode.On" in {
      skipUnlessLive()
      val pc = call(
        system      = "Reply to the user via the `respond` tool. Keep it brief.",
        userMessage = "What is 2+2?",
        tools       = Vector(RespondTool),
        toolChoice  = ToolChoice.Required,
        reasoning   = ReasoningMode.On,
        maxTokens   = 600
      )
      repeat(Reps)(runScenario(pc)).map(expectAllPassed)
    }

    "complete a respond tool call with ReasoningMode.Off" in {
      skipUnlessLive()
      val pc = call(
        system      = "Reply to the user via the `respond` tool. Keep it brief.",
        userMessage = "Say hello.",
        tools       = Vector(RespondTool),
        toolChoice  = ToolChoice.Required,
        reasoning   = ReasoningMode.Off
      )
      repeat(Reps)(runScenario(pc)).map(expectAllPassed)
    }

    "decode a tool call whose Input has all-optional fields (no_response)" in {
      skipUnlessLive()
      val pc = call(
        system      = "If the user has nothing to discuss, call `no_response` (no arguments needed). Otherwise call `respond`.",
        userMessage = "Nothing for now.",
        tools       = Vector(NoResponseTool, RespondTool),
        toolChoice  = ToolChoice.Required,
        reasoning   = ReasoningMode.Auto
      )
      repeat(Reps)(runScenario(pc)).map(expectAllPassed)
    }

    "complete a respond call exercising every RespondInput field (multi-arg strict-mode stress)" in {
      skipUnlessLive()
      val pc = call(
        system      = "Reply via the `respond` tool. Set topicLabel, topicSummary, content, and disposition = \"Success\". Keep content brief.",
        userMessage = "Tell me one fun fact about octopuses.",
        tools       = Vector(RespondTool),
        toolChoice  = ToolChoice.Required,
        reasoning   = ReasoningMode.Auto,
        maxTokens   = 500
      )
      repeat(Reps)(runScenario(pc)).map { attempts =>
        expectAllPassed(attempts)
        attempts.foreach { events =>
          val r = events.collect { case ProviderEvent.ToolCallComplete(_, in) => in }
            .head.asInstanceOf[RespondInput]
          r.topicLabel.trim should not be empty
          r.topicSummary.trim should not be empty
          r.content.trim should not be empty
        }
        succeed
      }
    }

    "pick a tool from a multi-tool roster" in {
      skipUnlessLive()
      val pc = call(
        system      = "You have three tools available: `respond` for replies, `no_response` for silence, " +
                      "`find_capability` for discovering additional tools. Pick one.",
        userMessage = "What is 5 minus 2?",
        tools       = Vector(RespondTool, NoResponseTool, FindCapabilityTool),
        toolChoice  = ToolChoice.Required,
        reasoning   = ReasoningMode.Auto
      )
      repeat(Reps)(runScenario(pc)).map(expectAllPassed)
    }

    "complete a multi-turn conversation (assistant message threaded in history)" in {
      skipUnlessLive()
      val pc = ProviderCall(
        model      = TestSigil.testModel(modelId),
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
      repeat(Reps)(runScenario(pc)).map { attempts =>
        expectAllPassed(attempts)
        attempts.foreach { events =>
          val r = events.collect { case ProviderEvent.ToolCallComplete(_, in) => in }
            .head.asInstanceOf[RespondInput]
          r.content.trim should not be empty
        }
        succeed
      }
    }

    "complete a tool call under a realistic agent system prompt (~500 tokens)" in {
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
      repeat(Reps)(runScenario(pc)).map(expectAllPassed)
    }

    "complete a reasoning-heavy turn under a realistic budget" in {
      // The DO spec runs the equivalent scenario at max_tokens=250 to
      // provoke its `empty_budget_burn` deployment-degeneration mode
      // (sigil bug #161). Cloudflare Kimi behaves correctly under
      // reasoning load — it just runs cleanly out of tokens rather
      // than degenerating — so 250 tokens was an unfair budget here.
      // 1200 tokens is realistic for a multi-step reasoning chain
      // plus a tool call.
      skipUnlessLive()
      val pc = call(
        system      = "Reply via the `respond` tool. Reason step-by-step where useful, then give the answer.",
        userMessage = "If a clock loses 3 minutes every hour and starts at 12:00 noon, " +
                      "what time will it show after 8 real hours?",
        tools       = Vector(RespondTool),
        toolChoice  = ToolChoice.Required,
        reasoning   = ReasoningMode.On,
        maxTokens   = 1200
      )
      repeat(Reps)(runScenario(pc)).map(expectAllPassed)
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
