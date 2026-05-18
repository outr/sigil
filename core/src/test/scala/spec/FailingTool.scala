package spec

import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolName, TypedTool}

/**
 * Test-only tool that unconditionally throws when executed. Used by
 * [[LlamaCppWorkerSpec]] to verify a worker's tool-dispatch path
 * surfaces tool failures cleanly: the workflow must reach a
 * terminal status (Failure) rather than hang.
 */
case object FailingTool
  extends TypedTool[FailingToolInput](
    name = ToolName("intentional_failure"),
    description = "Test-only tool that always throws an exception when called.",
    keywords = Set("fail", "test", "error")
  ) {
  override def paginate: Boolean = false

  override protected def executeTyped(input: FailingToolInput, ctx: TurnContext): Stream[Event] =
    Stream.force(rapid.Task.error(new RuntimeException("intentional failure for worker error-handling test")))
}
