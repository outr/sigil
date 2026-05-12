# ❌ #160 — `record_consent` accepts fabricated tool names; autonomous mode forces the agent to self-grant consent it shouldn't need to

**Where:**
- `core/src/main/scala/sigil/tool/core/RecordConsentTool.scala:69-94` — `executeTyped` writes a `ToolApproval` without validating `input.toolName` against the registry
- `core/src/main/scala/sigil/provider/Instructions.scala:107` — `AutonomousSafety` posture
- `core/src/main/scala/sigil/tool/Tool.scala:154` — `requiresUserConsent` gate (consulted by orchestrator on dispatch)

**What's wrong:**

Two compounding problems observed in a single Sage wire log entry:

**Problem A — `record_consent` accepts hallucinated tool names.** The agent called:

```
record_consent(toolName="start_coding", approved=true, reason="User entered coding mode")
```

There is no tool named `start_coding` anywhere in Sigil, Sage, or any of the registered modules. The agent fabricated the name. `record_consent.executeTyped` happily persisted a `ToolApproval` row for `("start_coding", convId)` and emitted a confirmation Tool-result. The DB now has a useless consent record for a tool that doesn't exist; any future agent iteration that consults that record sees it as a real approval.

The tool's own description warns about this: *"Mistyped names persist a useless record and the gate keeps refusing."* — but there's no validation. The agent is the validator, and the agent is the one mistyping.

**Problem B — autonomous safety still forces the agent to call `record_consent` itself.** Sage uses `Instructions.autonomous()` per its single-user-local stance — the user has implicitly granted the agent full authority by running it. Yet `requiresUserConsent`-flagged tools still gate on a `ToolApproval` record, so the agent has to call `record_consent(approved=true, reason="...")` and fabricate a "user reason" to clear the gate. That's not consent — it's the agent rubber-stamping itself with a made-up explanation. (In the wire log: the reason `"User entered coding mode"` was the agent's invention; the user never said any such thing.)

The consent gate exists to slot human-in-the-loop approval into destructive / expensive / external-effecting flows. Autonomous mode by definition opts OUT of that loop — the human delegated authority. The current architecture makes autonomous mode burn one extra LLM iteration per consent-gated tool to perform self-approval theater.

**Reproducers (Sigil-side specs):**

```scala
class RecordConsentValidationSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  "record_consent" should {
    "refuse to persist a ToolApproval for a tool name that isn't in the registry" in {
      val input = RecordConsentInput(toolName = "definitely_not_a_real_tool",
                                     approved = true, reason = Some("test"))
      RecordConsentTool.execute(input, ctx).toList.flatMap { events =>
        // Today this passes (silently persists). Should fail / emit Failure.
        events.collect { case m: Message => m.content }
          .flatten
          .exists {
            case ResponseContent.Failure(reason, _, _) =>
              reason.toLowerCase.contains("unknown tool")
            case _ => false
          } shouldBe true
        // ToolApproval row should NOT exist
        TestSigil.withDB(_.events.transaction { tx =>
          tx.query.filter(_.signal === "ToolApproval").toList
        }).map(_.exists(/* toolName == "definitely_not_a_real_tool" */ ???)) map (_ shouldBe false)
      }
    }
  }
}

class AutonomousConsentBypassSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  "in autonomous mode, requiresUserConsent gates" should {
    "be bypassed without requiring the agent to call record_consent" in {
      // Set up: agent uses Instructions.autonomous(),
      //   a tool with `requiresUserConsent = true` is in scope
      //   no ToolApproval record exists in db.events
      // Action: orchestrator dispatches the tool
      // Expected: dispatch proceeds, NO refusal, NO injected
      //   "consent required" Tool-result, NO record_consent call
      //   needed from the agent
      ...
    }
  }
}
```

**Suggested fix:**

**For Problem A — validate `toolName` in `RecordConsentTool`:**

```scala
override protected def executeTyped(input: RecordConsentInput, ctx: TurnContext): Stream[Event] = {
  ctx.sigil.staticTools.find(_.schema.name.value == input.toolName) match {
    case None =>
      val msg = s"record_consent: unknown tool '${input.toolName}'. " +
                s"Call find_capability first to discover the correct tool name; " +
                s"don't fabricate names."
      Stream.emits(List(Message(
        ...,
        content = Vector(ResponseContent.Failure(reason = msg, recoverable = true)),
        ...
      )))
    case Some(_) =>
      // existing happy path
      ...
  }
}
```

**For Problem B — autonomous mode bypasses the consent gate:**

Two shapes:

1. **In `Orchestrator`'s dispatch gate:** before refusing on `requiresUserConsent`, check the conversation / participant's effective safety posture. If autonomous, skip the gate entirely — dispatch immediately. This is the structural fix; the consent surface becomes invisible to autonomous-mode agents.

2. **Or auto-record approval on first encounter:** when a `requiresUserConsent` tool is requested by an autonomous-posture agent AND no `ToolApproval` exists, the orchestrator writes one synthetically (attribution: `"autonomous safety mode"`) and proceeds. Subsequent calls in the same conversation hit the existing approval and dispatch normally. The agent never burns a turn on self-grant.

Option 1 is cleaner — autonomous mode means "no consent gate" and the data model reflects that. Option 2 keeps the audit trail but introduces a synthetic "approval" that looks like a real user decision. I'd recommend Option 1, but file the trade-off so the implementer can choose.

**Severity:** High for autonomous-mode apps (every consent-gated tool burns an LLM iteration on self-approval theater) + Medium for all apps (the validation gap lets hallucinated tool names pollute the audit log).
