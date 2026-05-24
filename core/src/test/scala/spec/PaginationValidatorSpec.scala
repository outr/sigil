package spec

import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import rapid.Task
import sigil.TurnContext
import sigil.tool.{PaginationValidator, TextToolOutput, Tool, ToolInput, ToolName, ToolResult}
import sigil.tool.ToolContext

/**
 * Coverage for [[PaginationValidator]] (sigil bug #201). Every Tool
 * author must explicitly declare `paginate`; tools that say `true`
 * without exposing a pagination input AND without extending
 * `PaginatedTool` are rejected at registration.
 */
class PaginationValidatorSpec extends AnyWordSpec with Matchers {

  // ---- single-shot input (no pagination fields) ----
  case class PlainInput(query: String) extends ToolInput derives RW

  case object PlainSingleShotTool extends Tool {
    type Input  = PlainInput
    type Output = TextToolOutput
    val inputRW  = summon[RW[PlainInput]]
    val outputRW = summon[RW[TextToolOutput]]
    val name        = ToolName("plain_single_shot")
    val description = "A single-shot tool with no pagination fields."
    override def paginate: Boolean = false
    override def executeResult(input: PlainInput, context: ToolContext): Task[ToolResult[TextToolOutput]] =
      Task.pure(ToolResult.Success(TextToolOutput("ok")))
  }

  case object PlainButClaimsPaginatedTool extends Tool {
    type Input  = PlainInput
    type Output = TextToolOutput
    val inputRW  = summon[RW[PlainInput]]
    val outputRW = summon[RW[TextToolOutput]]
    val name        = ToolName("plain_claims_paginated")
    val description = "Claims paginate=true but exposes no pagination field — invalid."
    override def paginate: Boolean = true
    override def executeResult(input: PlainInput, context: ToolContext): Task[ToolResult[TextToolOutput]] =
      Task.pure(ToolResult.Success(TextToolOutput("ok")))
  }

  // ---- paginated input ----
  case class PagedInput(query: String, offset: Option[Int] = None, limit: Option[Int] = None) extends ToolInput derives RW

  case object PagedTool extends Tool {
    type Input  = PagedInput
    type Output = TextToolOutput
    val inputRW  = summon[RW[PagedInput]]
    val outputRW = summon[RW[TextToolOutput]]
    val name        = ToolName("paged_tool")
    val description = "Exposes offset / limit; valid paginate=true."
    override def paginate: Boolean = true
    override def executeResult(input: PagedInput, context: ToolContext): Task[ToolResult[TextToolOutput]] =
      Task.pure(ToolResult.Success(TextToolOutput("ok")))
  }

  case class CursorInput(cursor: Option[String] = None) extends ToolInput derives RW

  case object CursorTool extends Tool {
    type Input  = CursorInput
    type Output = TextToolOutput
    val inputRW  = summon[RW[CursorInput]]
    val outputRW = summon[RW[TextToolOutput]]
    val name        = ToolName("cursor_tool")
    val description = "Uses a single cursor field; valid paginate=true."
    override def paginate: Boolean = true
    override def executeResult(input: CursorInput, context: ToolContext): Task[ToolResult[TextToolOutput]] =
      Task.pure(ToolResult.Success(TextToolOutput("ok")))
  }

  "PaginationValidator" should {

    "accept a paginate=false single-shot tool" in {
      PaginationValidator.validate(PlainSingleShotTool) shouldBe Right(())
    }

    "reject paginate=true on a tool whose input exposes no pagination field" in {
      PaginationValidator.validate(PlainButClaimsPaginatedTool) match {
        case Left(reason) =>
          reason should include("plain_claims_paginated")
          reason should include("offset")
          reason should include("cursor")
        case Right(_) => fail("expected rejection")
      }
    }

    "accept paginate=true when the input exposes offset / limit" in {
      PaginationValidator.validate(PagedTool) shouldBe Right(())
    }

    "accept paginate=true when the input exposes a cursor field" in {
      PaginationValidator.validate(CursorTool) shouldBe Right(())
    }

    "raise IllegalStateException from validateAll on the first invalid tool" in {
      val ex = intercept[IllegalStateException] {
        PaginationValidator.validateAll(List(PlainSingleShotTool, PlainButClaimsPaginatedTool, PagedTool))
      }
      ex.getMessage should include("plain_claims_paginated")
    }
  }
}
