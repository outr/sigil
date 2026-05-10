package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.conversation.ProgressContext

/**
 * Coverage for [[sigil.Sigil#renderCheckpointPrompt]] — the
 * reflection-prompt assembly that bug #116 reshapes.
 *
 * The contract: every checkpoint prompt must include the user's
 * original task verbatim AND the agent's tool-call history since
 * that task AND the prior checkpoint's status. Together those
 * three anchors let the reflection model produce an actionable
 * diagnosis ("agent has 6 respond drafts without executing a
 * tool") instead of "awaiting specific task instructions".
 */
class ProgressCheckpointContextSpec extends AnyWordSpec with Matchers {

  // Use TestSigil to exercise the (private[sigil]) renderCheckpointPrompt
  // helper. TestSigil lives in the same package so the call resolves.
  TestSigil.initFor(getClass.getSimpleName)

  "renderCheckpointPrompt" should {

    "include the user's original task verbatim" in {
      val ctx = ProgressContext(
        userTask    = Some("Evaluate the password reset functionality and tell me if it's secure"),
        toolHistory = List("set_workspace → OK", "glob → OK", "respond × 6 (latest: \"Let me pull up\")")
      )
      val rendered = TestSigil.renderCheckpointPrompt(ctx, priorStatus = None, iteration = 15)
      rendered should include ("Evaluate the password reset functionality")
      rendered should include ("The user's request:")
    }

    "include the tool-call history line by line" in {
      val ctx = ProgressContext(
        userTask    = Some("evaluate"),
        toolHistory = List(
          "set_workspace → OK",
          "start_metals → OK",
          "glob → OK",
          "respond × 6 (latest: \"Let me pull up the full list\")"
        )
      )
      val rendered = TestSigil.renderCheckpointPrompt(ctx, priorStatus = None, iteration = 15)
      rendered should include ("What you've done since:")
      rendered should include ("set_workspace → OK")
      rendered should include ("start_metals → OK")
      rendered should include ("respond × 6")
    }

    "include the prior checkpoint status when supplied" in {
      val ctx = ProgressContext(userTask = Some("evaluate"), toolHistory = List("glob → OK"))
      val rendered = TestSigil.renderCheckpointPrompt(
        ctx,
        priorStatus = Some("Browsing source code via lsp_workspace_symbols"),
        iteration   = 30
      )
      rendered should include ("Prior checkpoint status:")
      rendered should include ("Browsing source code via lsp_workspace_symbols")
    }

    "label the first checkpoint explicitly when no prior status" in {
      val ctx = ProgressContext(userTask = Some("evaluate"), toolHistory = Nil)
      val rendered = TestSigil.renderCheckpointPrompt(ctx, priorStatus = None, iteration = 15)
      rendered should include ("first checkpoint")
    }

    "instruct the reflection model on the answer shape" in {
      val ctx = ProgressContext(userTask = Some("evaluate"), toolHistory = Nil)
      val rendered = TestSigil.renderCheckpointPrompt(ctx, priorStatus = None, iteration = 15)
      rendered should include ("currentStatus")
      rendered should include ("meaningfulProgress")
      rendered should include ("remainingSteps")
      rendered should include ("shouldAskUser")
    }

    "handle the fresh-conversation case gracefully" in {
      val ctx = ProgressContext(userTask = None, toolHistory = Nil)
      val rendered = TestSigil.renderCheckpointPrompt(ctx, priorStatus = None, iteration = 1)
      rendered should include ("(no recent substantive user message found)")
      rendered should include ("(no tool calls yet)")
    }

    "repro: the live wire-log scenario surfaces the paraphrase loop in the prompt" in {
      // Reflects the actual Sage 2026-05-10 failure shape — user
      // asks one task, agent emits multiple respond drafts without
      // invoking a real follow-up tool. The reflection prompt must
      // make both surfaces visible to the model.
      val ctx = ProgressContext(
        userTask    = Some("Evaluate the password reset functionality and tell me if it's functionally and security complete."),
        toolHistory = List(
          "set_workspace → OK",
          "start_metals → OK",
          "find_capability → OK",
          "glob → OK",
          "respond × 6 (latest: \"Found many password-related files. Let me pull up the full list and key source files.\")"
        )
      )
      val rendered = TestSigil.renderCheckpointPrompt(ctx, priorStatus = None, iteration = 15)
      rendered should include ("Evaluate the password reset")
      rendered should include ("respond × 6")
      rendered should include ("Let me pull up")
    }
  }

  "tear down" should {
    "dispose TestSigil" in {
      TestSigil.shutdown.sync()
      succeed
    }
  }
}
