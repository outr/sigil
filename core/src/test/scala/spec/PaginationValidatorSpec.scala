package spec

import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{PaginationValidator, ToolInput, ToolName, TypedTool}

/**
 * Coverage for [[PaginationValidator]] (sigil bug #201). Every Tool
 * author must explicitly declare `paginate`; tools that say `true`
 * without exposing a pagination input AND without extending
 * `PaginatedTool` are rejected at registration.
 */
class PaginationValidatorSpec extends AnyWordSpec with Matchers {

  // ---- single-shot input (no pagination fields) ----
  case class PlainInput(query: String) extends ToolInput derives RW

  case object PlainSingleShotTool
    extends TypedTool[PlainInput](
      name = ToolName("plain_single_shot"),
      description = "A single-shot tool with no pagination fields."
    ) {
    override def paginate: Boolean = false
    override protected def executeTyped(input: PlainInput, context: TurnContext): Stream[Event] =
      Stream.empty
  }

  case object PlainButClaimsPaginatedTool
    extends TypedTool[PlainInput](
      name = ToolName("plain_claims_paginated"),
      description = "Claims paginate=true but exposes no pagination field — invalid."
    ) {
    override def paginate: Boolean = true
    override protected def executeTyped(input: PlainInput, context: TurnContext): Stream[Event] =
      Stream.empty
  }

  // ---- paginated input ----
  case class PagedInput(query: String, offset: Option[Int] = None, limit: Option[Int] = None) extends ToolInput derives RW

  case object PagedTool
    extends TypedTool[PagedInput](
      name = ToolName("paged_tool"),
      description = "Exposes offset / limit; valid paginate=true."
    ) {
    override def paginate: Boolean = true
    override protected def executeTyped(input: PagedInput, context: TurnContext): Stream[Event] =
      Stream.empty
  }

  case class CursorInput(cursor: Option[String] = None) extends ToolInput derives RW

  case object CursorTool
    extends TypedTool[CursorInput](
      name = ToolName("cursor_tool"),
      description = "Uses a single cursor field; valid paginate=true."
    ) {
    override def paginate: Boolean = true
    override protected def executeTyped(input: CursorInput, context: TurnContext): Stream[Event] =
      Stream.empty
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
