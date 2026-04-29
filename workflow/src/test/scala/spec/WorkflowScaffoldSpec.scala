package spec

import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.{GlobalSpace, SpaceId}
import sigil.workflow.*
import sigil.workflow.trigger.*

/**
 * Smoke test for the `sigil-workflow` typed shapes. Confirms every
 * step input + framework trigger round-trips through fabric RW —
 * the precondition for Spice's Dart codegen to emit usable typed
 * classes for each subtype.
 *
 * Doesn't drive a real Strider manager (that's wired by
 * [[WorkflowSigil]] in a follow-up). The point of this spec is the
 * wire-shape contract: agents author `WorkflowStepInput` /
 * `WorkflowTrigger` instances, the framework persists + transports
 * them, and they decode back to the exact same typed shape.
 */
class WorkflowScaffoldSpec extends AnyWordSpec with Matchers {
  // SpaceId is an open PolyType; the framework normally registers
  // GlobalSpace inside Sigil.polymorphicRegistrations. This spec
  // doesn't construct a Sigil so we register the framework sentinel
  // directly.
  SpaceId.register(RW.static[SpaceId](GlobalSpace))

  // Register the four framework triggers + seven framework step
  // inputs so the polymorphic dispatchers know the subtype set.
  // In production this is done by mixing `WorkflowSigil` into the
  // app's Sigil; doing it manually here keeps the test
  // self-contained.
  WorkflowTrigger.register(
    summon[RW[ConversationMessageTrigger]],
    summon[RW[TimeTrigger]],
    summon[RW[WebhookTrigger]],
    summon[RW[WorkflowEventTrigger]]
  )

  WorkflowStepInput.register(
    summon[RW[JobStepInput]],
    summon[RW[ConditionStepInput]],
    summon[RW[ApprovalStepInput]],
    summon[RW[ParallelStepInput]],
    summon[RW[LoopStepInput]],
    summon[RW[SubWorkflowStepInput]],
    summon[RW[TriggerStepInput]]
  )

  private def roundTrip[T: RW](value: T): Unit = {
    val rw = summon[RW[T]]
    rw.write(rw.read(value)) shouldBe value
  }

  "WorkflowTrigger subtypes" should {
    "round-trip ConversationMessageTrigger via the polymorphic dispatcher" in {
      val t: WorkflowTrigger = ConversationMessageTrigger(
        conversationId = "conv-123",
        participantId = Some("user-1"),
        containsText = Some("deploy")
      )
      roundTrip(t)
    }
    "round-trip TimeTrigger" in {
      roundTrip[WorkflowTrigger](TimeTrigger(intervalMs = Some(60000L)))
      roundTrip[WorkflowTrigger](TimeTrigger(cron = Some("0 9 * * 1")))
    }
    "round-trip WebhookTrigger" in {
      roundTrip[WorkflowTrigger](WebhookTrigger(path = "/webhooks/build", secret = "topsecret"))
    }
    "round-trip WorkflowEventTrigger" in {
      roundTrip[WorkflowTrigger](WorkflowEventTrigger(eventName = "build_succeeded"))
    }
  }

  "WorkflowStepInput subtypes" should {
    "round-trip JobStepInput" in {
      roundTrip[WorkflowStepInput](JobStepInput(
        id = "summarize",
        name = "Summarize the input",
        prompt = "Summarize: {{input}}",
        modelId = "openai/gpt-5.4-mini",
        output = "summary"
      ))
    }
    "round-trip ConditionStepInput" in {
      roundTrip[WorkflowStepInput](ConditionStepInput(
        id = "check",
        expression = "{{count}} > 0",
        onTrue = "process",
        onFalse = "noop"
      ))
    }
    "round-trip ApprovalStepInput" in {
      roundTrip[WorkflowStepInput](ApprovalStepInput(
        id = "review",
        prompt = "Approve the deploy?",
        options = List("approve", "reject"),
        timeoutMs = Some(3600000L)
      ))
    }
    "round-trip ParallelStepInput with nested branches" in {
      roundTrip[WorkflowStepInput](ParallelStepInput(
        id = "parallel",
        branches = List(
          List(JobStepInput(id = "a", prompt = "A", modelId = "m")),
          List(JobStepInput(id = "b", prompt = "B", modelId = "m"))
        ),
        joinMode = "all"
      ))
    }
    "round-trip LoopStepInput with a body" in {
      roundTrip[WorkflowStepInput](LoopStepInput(
        id = "loop",
        over = "items",
        body = List(JobStepInput(id = "process", prompt = "{{item}}", modelId = "m"))
      ))
    }
    "round-trip SubWorkflowStepInput" in {
      roundTrip[WorkflowStepInput](SubWorkflowStepInput(
        id = "sub",
        workflowId = "wf-123",
        variables = Map("input" -> "{{prior}}"),
        output = "subResult"
      ))
    }
    "round-trip TriggerStepInput carrying a typed trigger" in {
      roundTrip[WorkflowStepInput](TriggerStepInput(
        id = "wait",
        trigger = TimeTrigger(intervalMs = Some(30000L)),
        mode = "branch"
      ))
    }
  }

  "WorkflowTemplate" should {
    "round-trip with a mixed step list and trigger list" in {
      val tmpl = WorkflowTemplate(
        name = "demo",
        description = "Mixed shapes",
        steps = List(
          JobStepInput(id = "s1", prompt = "Hello {{name}}", modelId = "m"),
          ConditionStepInput(id = "s2", expression = "{{x}} == \"ok\"", onTrue = "s3", onFalse = "s4"),
          JobStepInput(id = "s3", prompt = "Yes"),
          JobStepInput(id = "s4", prompt = "No")
        ),
        triggers = List(
          ConversationMessageTrigger(conversationId = "c"),
          TimeTrigger(intervalMs = Some(60000L))
        ),
        space = GlobalSpace
      )
      val rw = summon[RW[WorkflowTemplate]]
      rw.write(rw.read(tmpl)) shouldBe tmpl
    }
  }

  "WorkflowVariableSubstitution" should {
    "substitute {{var}} placeholders" in {
      val vars = Map[String, fabric.Json]("name" -> fabric.str("Alice"), "count" -> fabric.num(42))
      WorkflowVariableSubstitution.substitute("Hello {{name}}, count={{count}}", vars) shouldBe
        "Hello Alice, count=42"
    }
    "leave unknown placeholders untouched" in {
      WorkflowVariableSubstitution.substitute("Hi {{missing}}", Map.empty) shouldBe "Hi {{missing}}"
    }
  }

  "WorkflowStepInputCompiler" should {
    "compile a typed step list into Strider's flat step list" in {
      import strider.step.Step
      given stepRW: RW[Step] = RW.poly[Step]()(
        summon[RW[SigilJobStep]],
        summon[RW[SigilCondition]],
        summon[RW[SigilApproval]]
      )
      val inputs: List[WorkflowStepInput] = List(
        JobStepInput(id = "a", prompt = "Hello", modelId = "m"),
        JobStepInput(id = "b", prompt = "World", modelId = "m")
      )
      val compiled = WorkflowStepInputCompiler.compile(inputs)
      compiled.steps should have size 2
      compiled.queue should have size 2
      compiled.idsByInputId.keySet shouldBe Set("a", "b")
    }
  }
}
