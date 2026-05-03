package sigil.tool.util

import fabric.io.JsonFormatter
import fabric.rw.*
import lightdb.id.Id
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.conversation.ContextMemory
import sigil.event.{Event, Message, MessageRole}
import sigil.information.Information
import sigil.information.Information.given
import sigil.tool.discovery.CapabilityType
import sigil.tool.{ToolName, TypedTool}
import sigil.tool.model.{LookupInput, ResponseContent}

/**
 * Generic retrieval tool. Resolves any retrievable
 * [[sigil.tool.discovery.CapabilityMatch]] back to its full record:
 *   - `Memory`      → full [[ContextMemory]] (looked up by `key`,
 *                     falling back to `_id` for direct hits)
 *   - `Information` → full [[Information]] record
 *   - `Skill`       → reserved (Phase 4)
 *
 * `Tool` and `Mode` capability types are not retrievable — tools are
 * called via their name in the tool roster; modes are entered via
 * `change_mode`. Looking those up returns a not-supported message
 * rather than silently doing nothing.
 *
 * The result is emitted as a tool-role [[Message]] containing the
 * full JSON serialisation of the record, suitable for the agent to
 * read on its next turn.
 */
case object LookupTool extends TypedTool[LookupInput](
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
      |Tools and modes are not retrievable — call tools directly; switch modes via `change_mode`.""".stripMargin,
  keywords = Set("lookup", "fetch", "retrieve", "resolve", "details", "full", "expand")
) {
  override protected def executeTyped(input: LookupInput, context: TurnContext): Stream[Event] =
    Stream.force(resolve(input, context).map { body =>
      Stream.emits(List[Event](Message(
        participantId = context.caller,
        conversationId = context.conversation.id,
        topicId = context.conversation.currentTopicId,
        content = Vector(ResponseContent.Text(body)),
        role = MessageRole.Tool
      )))
    })

  private def resolve(input: LookupInput, context: TurnContext): Task[String] =
    input.capabilityType match {
      case CapabilityType.Memory      => resolveMemory(input.name, context)
      case CapabilityType.Information => resolveInformation(input.name, context)
      case CapabilityType.Skill       => Task.pure(s"[lookup] skill retrieval not yet supported (Phase 4).")
      case CapabilityType.Tool        => Task.pure(s"[lookup] tools are not retrievable — call '${input.name}' directly.")
      case CapabilityType.Mode        => Task.pure(s"[lookup] modes are not retrievable — call change_mode(\"${input.name}\") to enter it.")
    }

  private def resolveMemory(name: String, context: TurnContext): Task[String] =
    context.sigil.withDB { db =>
      db.memories.transaction { tx =>
        tx.query.filter(_.key === name).toList.flatMap { byKey =>
          byKey.headOption match {
            case Some(memory) => Task.pure(formatMemory(memory))
            case None         =>
              tx.get(Id[ContextMemory](name)).map {
                case Some(memory) => formatMemory(memory)
                case None         => s"[lookup] no Memory found for name '$name'."
              }
          }
        }
      }
    }

  private def resolveInformation(name: String, context: TurnContext): Task[String] =
    context.sigil.getInformation(Id[Information](name)).map {
      case Some(full) => JsonFormatter.Default(summon[RW[Information]].read(full))
      case None       => s"[lookup] no Information found for id '$name'."
    }

  private def formatMemory(m: ContextMemory): String =
    JsonFormatter.Default(summon[RW[ContextMemory]].read(m))
}
