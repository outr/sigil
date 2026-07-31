package sigil.tool.random

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{DiscoverySpec, Effect, Freshness, Tool, ToolExample, ToolName, ToolProfile, ToolSpec}
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
case object RandomUuidTool extends Tool {
  type Input  = RandomUuidInput
  type Output = RandomUuidOutput
  val inputRW  = summon[RW[RandomUuidInput]]
  val outputRW = summon[RW[RandomUuidOutput]]

  override val name = ToolName("random_uuid")
  override val description = "Generate a v4 (random) UUID. Returns `{uuid}`."
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Volatile)),
    discovery = DiscoverySpec(keywords = Set("uuid", "guid", "random", "id", "identifier", "token"))
  )
  override val examples = List(ToolExample("fresh uuid", RandomUuidInput()))

  override def executeOutput(input: RandomUuidInput, context: ToolContext): Task[RandomUuidOutput] =
    Task(RandomUuidOutput(uuid = java.util.UUID.randomUUID().toString))
}
