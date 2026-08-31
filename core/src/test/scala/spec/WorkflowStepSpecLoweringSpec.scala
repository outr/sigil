package spec

import fabric.{obj, str}
import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.workflow.{JobStepInput, LoopStepInput, ParallelStepInput, WorkflowStepKind, WorkflowStepSpec}
import sigil.workflow.tool.CreateWorkflowInput

/**
 * The flat, model-fillable [[WorkflowStepSpec]] lowers to the engine's
 * [[sigil.workflow.WorkflowStepInput]] IR. Covers the workflow-first shape
 * (a discovery Job whose output a Loop consumes), nesting-by-reference
 * (body/branch step ids excluded from the top level and nested into their
 * owners), validation failures, and a wire round-trip through the real
 * `create_workflow` input RW — the path a model's tool call actually takes.
 */
class WorkflowStepSpecLoweringSpec extends AnyWordSpec with Matchers {

  "WorkflowStepSpec.lower" should {
    "lower discovery-as-a-stage: a Job's output feeds a Loop; the body step nests, not top-level" in {
      val specs = List(
        WorkflowStepSpec(
          id = "find",
          kind = WorkflowStepKind.Job,
          tool = Some("grep"),
          arguments = Some(obj("pattern" -> str("bug #"), "path" -> str("/src"))),
          output = Some("hits")),
        WorkflowStepSpec(
          id = "each",
          kind = WorkflowStepKind.Loop,
          over = Some("hits"),
          itemVariable = Some("f"),
          bodyStepIds = List("act")),
        WorkflowStepSpec(
          id = "act",
          kind = WorkflowStepKind.Job,
          tool = Some("echo_back"),
          arguments = Some(obj("text" -> str("{{f}}"))))
      )
      WorkflowStepSpec.lower(specs) match {
        case Left(errors) => fail(s"expected success, got: $errors")
        case Right(ir) =>
          // "act" is referenced as a loop body → excluded from the top level.
          ir.map(_.id) shouldBe List("find", "each")
          ir.head shouldBe a[JobStepInput]
          ir(1) match {
            case loop: LoopStepInput =>
              loop.over shouldBe "hits"
              loop.itemVariable shouldBe "f"
              loop.body.map(_.id) shouldBe List("act")
              loop.body.head shouldBe a[JobStepInput]
            case other => fail(s"expected a LoopStepInput, got: $other")
          }
      }
    }

    "nest Parallel branches by reference" in {
      val specs = List(
        WorkflowStepSpec(id = "fork", kind = WorkflowStepKind.Parallel, branchStepIds = List(List("a"), List("b"))),
        WorkflowStepSpec(id = "a", kind = WorkflowStepKind.Job, prompt = Some("A")),
        WorkflowStepSpec(id = "b", kind = WorkflowStepKind.Job, prompt = Some("B"))
      )
      WorkflowStepSpec.lower(specs) match {
        case Right(ir) =>
          ir.map(_.id) shouldBe List("fork")
          ir.head match {
            case p: ParallelStepInput => p.branches.map(_.map(_.id)) shouldBe List(List("a"), List("b"))
            case other => fail(s"expected ParallelStepInput, got: $other")
          }
        case Left(errors) => fail(s"expected success, got: $errors")
      }
    }

    "reject a Loop missing `over` / `bodyStepIds` with a recoverable message" in {
      val specs = List(WorkflowStepSpec(id = "each", kind = WorkflowStepKind.Loop))
      WorkflowStepSpec.lower(specs) match {
        case Right(ir) => fail(s"expected failure, got: $ir")
        case Left(errors) =>
          errors.exists(_.contains("requires 'over'")) shouldBe true
          errors.exists(_.contains("bodyStepIds")) shouldBe true
      }
    }

    "reject a Loop whose `over` names a variable no step produces" in {
      val specs = List(
        WorkflowStepSpec(id = "find", kind = WorkflowStepKind.Job, tool = Some("grep"), output = Some("hits")),
        WorkflowStepSpec(id = "each", kind = WorkflowStepKind.Loop, over = Some("ghostVar"), bodyStepIds = List("act")),
        WorkflowStepSpec(id = "act", kind = WorkflowStepKind.Job, prompt = Some("x"))
      )
      WorkflowStepSpec.lower(specs).left.toOption.getOrElse(Nil).exists(_.contains("iterates over 'ghostVar'")) shouldBe true
    }

    "accept a Loop whose `over` is a declared input variable" in {
      val specs = List(
        WorkflowStepSpec(id = "each", kind = WorkflowStepKind.Loop, over = Some("seed"), bodyStepIds = List("act")),
        WorkflowStepSpec(id = "act", kind = WorkflowStepKind.Job, prompt = Some("x"))
      )
      WorkflowStepSpec.lower(specs, knownVariables = Set("seed")).map(_.map(_.id)) shouldBe Right(List("each"))
    }

    "reject a body reference to an unknown step id" in {
      val specs = List(
        WorkflowStepSpec(id = "each", kind = WorkflowStepKind.Loop, over = Some("hits"), bodyStepIds = List("ghost"))
      )
      WorkflowStepSpec.lower(specs).left.toOption.getOrElse(Nil).exists(_.contains("unknown body step id 'ghost'")) shouldBe true
    }

    "reject a Job with neither prompt nor tool" in {
      val specs = List(WorkflowStepSpec(id = "x", kind = WorkflowStepKind.Job))
      WorkflowStepSpec.lower(specs).left.toOption.getOrElse(Nil).exists(_.contains("requires either a prompt or a tool")) shouldBe true
    }

    // Sigil #384 — the `sigil-remove-bug-references` plan: a top-level writeFile
    // reads the loop's itemVariable + a body step's output from OUTSIDE the loop,
    // so per-item edits never persist and `{{editedContents}}` can reach disk.
    "reject a step outside a loop that references the loop's itemVariable and a body output (#384)" in {
      val specs = List(
        WorkflowStepSpec(
          id = "discover",
          kind = WorkflowStepKind.Job,
          tool = Some("grep"),
          arguments = Some(obj("pattern" -> str("bug #"))),
          output = Some("bugFiles")),
        WorkflowStepSpec(
          id = "editLoop",
          kind = WorkflowStepKind.Loop,
          over = Some("bugFiles"),
          itemVariable = Some("filePath"),
          bodyStepIds = List("readFile", "editFile")),
        WorkflowStepSpec(
          id = "readFile",
          kind = WorkflowStepKind.Job,
          tool = Some("read_file"),
          arguments = Some(obj("path" -> str("{{filePath}}"))),
          output = Some("fileContents")),
        WorkflowStepSpec(
          id = "editFile",
          kind = WorkflowStepKind.Job,
          prompt = Some("remove refs from {{fileContents}}"),
          output = Some("editedContents")),
        WorkflowStepSpec(
          id = "writeFile",
          kind = WorkflowStepKind.Job,
          tool = Some("write_file"),
          arguments = Some(obj("path" -> str("{{filePath}}"), "content" -> str("{{editedContents}}")))
        )
      )
      val errors = WorkflowStepSpec.lower(specs).left.toOption.getOrElse(Nil)
      withClue(s"errors=$errors: ") {
        errors.exists(e => e.contains("writeFile") && e.contains("{{editedContents}}") && e.contains("editLoop")) shouldBe true
        errors.exists(e => e.contains("writeFile") && e.contains("{{filePath}}") && e.contains("editLoop")) shouldBe true
      }
    }

    "accept the same plan once writeFile is moved INTO the loop body (#384)" in {
      val specs = List(
        WorkflowStepSpec(
          id = "discover",
          kind = WorkflowStepKind.Job,
          tool = Some("grep"),
          arguments = Some(obj("pattern" -> str("bug #"))),
          output = Some("bugFiles")),
        WorkflowStepSpec(
          id = "editLoop",
          kind = WorkflowStepKind.Loop,
          over = Some("bugFiles"),
          itemVariable = Some("filePath"),
          bodyStepIds = List("readFile", "editFile", "writeFile")),
        WorkflowStepSpec(
          id = "readFile",
          kind = WorkflowStepKind.Job,
          tool = Some("read_file"),
          arguments = Some(obj("path" -> str("{{filePath}}"))),
          output = Some("fileContents")),
        WorkflowStepSpec(
          id = "editFile",
          kind = WorkflowStepKind.Job,
          prompt = Some("remove refs from {{fileContents}}"),
          output = Some("editedContents")),
        WorkflowStepSpec(
          id = "writeFile",
          kind = WorkflowStepKind.Job,
          tool = Some("write_file"),
          arguments = Some(obj("path" -> str("{{filePath}}"), "content" -> str("{{editedContents}}")))
        )
      )
      WorkflowStepSpec.lower(specs).isRight shouldBe true
    }

    "not false-positive when a same-named variable is also produced outside the loop (#384)" in {
      // `result` is produced both inside the loop body AND by a top-level step,
      // so a later top-level reference to it has a valid outer binding.
      val specs = List(
        WorkflowStepSpec(
          id = "loop",
          kind = WorkflowStepKind.Loop,
          over = Some("xs"),
          itemVariable = Some("x"),
          bodyStepIds = List("inner")),
        WorkflowStepSpec(id = "inner", kind = WorkflowStepKind.Job, prompt = Some("use {{x}}"), output = Some("result")),
        WorkflowStepSpec(id = "outerProducer", kind = WorkflowStepKind.Job, prompt = Some("compute"), output = Some("result")),
        WorkflowStepSpec(id = "consume", kind = WorkflowStepKind.Job, prompt = Some("read {{result}}"))
      )
      WorkflowStepSpec.lower(specs, knownVariables = Set("xs")).isRight shouldBe true
    }

    "round-trip flat specs through the real create_workflow input RW" in {
      val input = CreateWorkflowInput(
        name = "process-matches",
        steps = List(
          WorkflowStepSpec(
            id = "find",
            kind = WorkflowStepKind.Job,
            tool = Some("grep"),
            arguments = Some(obj("pattern" -> str("TODO"), "path" -> str("/src"))),
            output = Some("hits")),
          WorkflowStepSpec(id = "each", kind = WorkflowStepKind.Loop, over = Some("hits"), bodyStepIds = List("act")),
          WorkflowStepSpec(
            id = "act",
            kind = WorkflowStepKind.Job,
            tool = Some("read_file"),
            arguments = Some(obj("path" -> str("{{item}}"))))
        )
      )
      val rw = summon[RW[CreateWorkflowInput]]
      // fabric RW: read(value): Json, write(json): value — round-trip value→Json→value.
      val back = rw.write(rw.read(input))
      back.steps.map(_.kind) shouldBe List(WorkflowStepKind.Job, WorkflowStepKind.Loop, WorkflowStepKind.Job)
      WorkflowStepSpec.lower(back.steps).map(_.map(_.id)) shouldBe Right(List("find", "each"))
    }
  }
}
