package sigil.tool.util

import fabric.rw.*
import lightdb.id.Id
import rapid.Task
import sigil.TurnContext
import sigil.conversation.ContextMemory
import sigil.information.Information
import sigil.information.Information.given
import sigil.skill.Skill
import sigil.tool.discovery.CapabilityType
import sigil.tool.{ToolName, TypedOutputTool}
import sigil.tool.model.{LookupInput, LookupOutput}

/**
 * Generic retrieval tool. Resolves any retrievable
 * [[sigil.tool.discovery.CapabilityMatch]] back to its full record:
 *   - `Memory`      → full [[ContextMemory]] (looked up by `key`,
 *                     falling back to `_id` for direct hits)
 *   - `Information` → full [[Information]] record
 *   - `Skill`       → full [[Skill]] record by name (the skill's
 *                     `description` + `content` markdown body, plus
 *                     `modes` / `keywords` metadata so the agent can
 *                     decide whether to `activate_skill` it)
 *
 * `Tool` and `Mode` capability types are not retrievable — tools are
 * called via their name in the tool roster; modes are entered via
 * `change_mode`. Looking those up returns a `NotRetrievable` result
 * rather than silently doing nothing.
 *
 * Emits a typed [[LookupOutput]] enum — `Found(payload)` carries the
 * matched record's JSON for the caller to deserialize against
 * whichever shape matches `capabilityType`.
 */
case object LookupTool extends TypedOutputTool[LookupInput, LookupOutput](
  name = ToolName("lookup"),
  description =
    """Resolve a capability match (from `find_capability`) to its full record. Use this when
      |you need the details behind a memory's summary, the body of a referenced Information
      |record, or any other discovered capability with stored content.
      |
      |- `capabilityType` — what kind of record to fetch (`Memory`, `Information`, `Skill`).
      |- `name` — the identifier the match surfaced (memory key, information id-as-string,
      |  skill name).
      |
      |Tools and modes are not retrievable — call tools directly; switch modes via `change_mode`.
      |Returns `Found(capabilityType, name, payload)`, `NotFound(capabilityType, name)`, or
      |`NotRetrievable(capabilityType, name, hint)`.""".stripMargin,
  keywords = Set("lookup", "fetch", "retrieve", "resolve", "details", "full", "expand")
) with sigil.tool.ReadOnlyInternalTool {
  override def paginate: Boolean = false

  override protected def executeTyped(input: LookupInput, context: TurnContext): Task[LookupOutput] = {
    val typeName = input.capabilityType.toString
    input.capabilityType match {
      case CapabilityType.Memory      => resolveMemory(input.name, typeName, context)
      case CapabilityType.Information => resolveInformation(input.name, typeName, context)
      case CapabilityType.Skill       => resolveSkill(input.name, typeName, context)
      case CapabilityType.Tool =>
        Task.pure(LookupOutput.NotRetrievable(typeName, input.name,
          s"tools are not retrievable — call '${input.name}' directly."))
      case CapabilityType.Mode =>
        Task.pure(LookupOutput.NotRetrievable(typeName, input.name,
          s"modes are not retrievable — call change_mode(\"${input.name}\") to enter it."))
    }
  }

  private def resolveMemory(name: String, typeName: String, context: TurnContext): Task[LookupOutput] =
    context.sigil.withDB { db =>
      db.memories.transaction { tx =>
        tx.query.filter(_.key === Some(name)).toList.flatMap { byKey =>
          byKey.headOption match {
            case Some(memory) =>
              Task.pure(LookupOutput.Found(typeName, name, summon[RW[ContextMemory]].read(memory)))
            case None =>
              tx.get(Id[ContextMemory](name)).map {
                case Some(memory) =>
                  LookupOutput.Found(typeName, name, summon[RW[ContextMemory]].read(memory))
                case None =>
                  LookupOutput.NotFound(typeName, name)
              }
          }
        }
      }
    }

  private def resolveInformation(name: String, typeName: String, context: TurnContext): Task[LookupOutput] =
    context.sigil.getInformation(Id[Information](name)).map {
      case Some(full) => LookupOutput.Found(typeName, name, summon[RW[Information]].read(full))
      case None       => LookupOutput.NotFound(typeName, name)
    }

  private def resolveSkill(name: String, typeName: String, context: TurnContext): Task[LookupOutput] =
    context.sigil.withDB(_.skills.transaction(_.get(Id[Skill](name)))).map {
      case Some(skill) => LookupOutput.Found(typeName, name, summon[RW[Skill]].read(skill))
      case None        => LookupOutput.NotFound(typeName, name)
    }
}
