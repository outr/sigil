package sigil.tool.random

import rapid.Task
import sigil.TurnContext
import sigil.tool.{ToolExample, ToolName, TypedOutputTool}
import sigil.tool.model.{RandomUuidInput, RandomUuidOutput}

/**
 * Generate a v4 (random) UUID. Useful when an agent needs an opaque
 * identifier — correlation tokens, idempotency keys, fresh resource
 * ids — without going through an external service.
 *
 * Backed by `java.util.UUID.randomUUID()` which uses
 * `SecureRandom` under the hood; safe for token-style use cases
 * where guessability matters.
 */
case object RandomUuidTool
  extends TypedOutputTool[RandomUuidInput, RandomUuidOutput](
    name = ToolName("random_uuid"),
    description = "Generate a v4 (random) UUID. Returns `{uuid}`.",
    examples = List(ToolExample("fresh uuid", RandomUuidInput())),
    keywords = Set("uuid", "guid", "random", "id", "identifier", "token")
  ) {
  override def paginate: Boolean = false

  override protected def executeTyped(input: RandomUuidInput, context: TurnContext): Task[RandomUuidOutput] =
    Task(RandomUuidOutput(uuid = java.util.UUID.randomUUID().toString))
}
