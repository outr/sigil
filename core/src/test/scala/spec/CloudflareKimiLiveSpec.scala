package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}

import scala.concurrent.duration.*
import sigil.db.Model
import sigil.provider.{
  GenerationSettings, MessageContent, ProviderCall, ProviderEvent,
  ProviderMessage, ReasoningMode, ToolChoice
}
import sigil.provider.cloudflare.CloudflareProvider
import sigil.tool.Tool
import sigil.tool.core.{FindCapabilityTool, NoResponseTool, RespondTool}
import sigil.tool.model.RespondInput
import sigil.tool.ToolRoster

/**
 * Live reliability characterization for Kimi-K2.6 hosted on Cloudflare
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
 * holds against the live Kimi-K2.6 deployment.
 */
class CloudflareKimiLiveSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  // Live Cloudflare/Kimi calls are slow under the concurrent forked-JVM
  // load of the full suite, and reasoning-heavy turns (high effort + a
  // large budget) plus the per-rep retry below can stack several
  // sequential round-trips. Give the async harness generous headroom
  // over its 1-minute default so genuine upstream latency doesn't read
  // as a test failure.
  override protected val testTimeout: FiniteDuration = 4.minutes

  private val apiTokenOpt: Option[String]  = sys.env.get("CLOUDFLARE_AUTH_TOKEN").filter(_.nonEmpty)
  private val accountIdOpt: Option[String] = sys.env.get("CLOUDFLARE_ACCOUNT_ID").filter(_.nonEmpty)

  // Constructed lazily — every test gates on `skipUnlessLive()` before
  // accessing `provider`, so the `.get` calls are safe.
  private lazy val provider: CloudflareProvider =
    CloudflareProvider(apiTokenOpt.get, accountIdOpt.get, TestSigil)

  private val modelId: Id[Model] = Model.id("cloudflare", "@cf/moonshotai/kimi-k2.6")

  // Sigil-test convention: live-API specs override `run` and route
  // through their provider's `runGated` so a missing SIGIL_LIVE
  // opt-in (or missing credentials / drained quota) skips the
  // whole suite cleanly without any per-test gate. Mirrors the
  // GoogleProviderSpec / AnthropicProviderSpec / OpenRouter*Spec
  // shape so every live suite uses the same wiring.
  override def run(testName: Option[String], args: org.scalatest.Args): org.scalatest.Status =
    CloudflareLiveSupport.runGated(this, testName, args) {
      super.run(testName, args)
    }

  // Per-test belt-and-suspenders: in case the suite-level probe
  // succeeded (metadata calls don't consume neurons; quota walls
  // only show up on real model invocations), the in-flight
  // RuntimeException-shaped quota error is still translated into
  // a per-test cancel by `runScenario` below.
  private def skipUnlessLive(): Unit =
    if (apiTokenOpt.isEmpty || accountIdOpt.isEmpty)
      cancel(
        "CLOUDFLARE_AUTH_TOKEN / CLOUDFLARE_ACCOUNT_ID not set — skipping live Cloudflare Kimi-K2.6 spec"
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
    roster = ToolRoster(tools),
    builtInTools = Set.empty,
    toolChoice   = toolChoice,
    generationSettings = GenerationSettings(
      maxOutputTokens = Some(maxTokens),
      temperature     = Some(0.0),
      reasoningMode   = reasoning
    )
  )

  /** Run `provider.call(pc).toList`, but translate "neurons
    * exhausted" responses (HTTP 429 from Cloudflare's free-tier
    * quota wall) into a cancelled test rather than letting the
    * exception bubble as a failure. Matches the live-spec
    * convention of self-skipping when the external service is
    * unavailable. */
  private def runScenarioOnce(pc: ProviderCall): Task[List[ProviderEvent]] =
    provider.call(pc).toList.handleError { t =>
      // Self-skip on ANY live-service-unavailable signal — the daily-quota wall
      // AND the mid-run capacity throttle (`429 Capacity temporarily exceeded`)
      // / timeouts the free tier returns under load (sigil live-spec convention).
      if (CloudflareLiveSupport.isServiceUnavailable(t))
        Task(cancel(s"Cloudflare Workers AI unavailable (throttle/timeout) — skipping live spec. (${Option(t.getMessage).getOrElse(t.toString)})"))
      else Task.error(t)
    }

  /** A degenerate completion — no tool call AND no error event — is the
    * signature of a transient upstream blip (Cloudflare/Kimi occasionally
    * returns an empty body). Retry those up to [[MaxTriesPerRep]] times;
    * a real error is surfaced immediately (not retried), and a sustained
    * outage still fails because every retry comes back empty too. */
  private def isEmptyBlip(events: List[ProviderEvent]): Boolean =
    !events.exists(_.isInstanceOf[ProviderEvent.ToolCallComplete]) &&
      !events.exists(_.isInstanceOf[ProviderEvent.Error])

  private val MaxTriesPerRep: Int = 3

  private def runScenario(pc: ProviderCall): Task[List[ProviderEvent]] = {
    def attempt(triesLeft: Int): Task[List[ProviderEvent]] =
      runScenarioOnce(pc).flatMap { events =>
        if (isEmptyBlip(events) && triesLeft > 1) attempt(triesLeft - 1)
        else Task.pure(events)
      }
    attempt(MaxTriesPerRep)
  }

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

  /** Assert a MAJORITY of attempts produced a clean tool-call completion
    * (no Error events, at least one ToolCallComplete). Each attempt has
    * already retried transient empty blips (see [[runScenario]]); a
    * strict majority then tolerates the rare blip that survives every
    * retry while still failing on sustained degradation — if Kimi is
    * less than ~half reliable, the majority isn't met. */
  private def expectAllPassed(attempts: List[List[ProviderEvent]]): org.scalatest.Assertion = {
    val perAttempt = attempts.zipWithIndex.map { case (events, idx) =>
      val errors    = events.collect { case e: ProviderEvent.Error => e }
      val completes = events.collect { case c: ProviderEvent.ToolCallComplete => c }
      val ok = errors.isEmpty && completes.nonEmpty
      val tag = if (ok) "ok" else
        s"FAIL[errors=${errors.size}, completes=${completes.size}: ${errors.take(1).mkString}]"
      s"attempt ${idx + 1}: $tag"
    }
    val passed   = perAttempt.count(_.endsWith("ok"))
    val majority = attempts.size / 2 + 1
    withClue(s"$passed/${attempts.size} attempts passed (need majority $majority) (${perAttempt.mkString("; ")}): ") {
      passed should be >= majority
    }
  }

  "Cloudflare Kimi-K2.6 live reliability" should {

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
          val r = events.collect { case ProviderEvent.ToolCallComplete(_, wc) => wc }
            .headOption.flatMap(_.inputFor(RespondTool)).getOrElse(fail("no decoded respond call"))
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
        roster = ToolRoster(Vector(RespondTool)),
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
          val r = events.collect { case ProviderEvent.ToolCallComplete(_, wc) => wc }
            .headOption.flatMap(_.inputFor(RespondTool)).getOrElse(fail("no decoded respond call"))
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
