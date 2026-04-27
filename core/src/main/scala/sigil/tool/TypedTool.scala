package sigil.tool

import fabric.rw.*
import lightdb.id.Id
import sigil.{GlobalSpace, SpaceId, TurnContext}
import sigil.event.Event
import sigil.participant.ParticipantId
import sigil.provider.{ConversationMode, Mode}

import scala.reflect.ClassTag

/**
 * Authoring sugar for tools whose [[ToolInput]] subclass is statically
 * known. Concrete tools extend `TypedTool[XxxInput]` with their metadata
 * as constructor args and override `executeTyped` (which receives the
 * already-cast typed input). The base implements the unified
 * `execute(input: ToolInput, ...)` by runtime-checking with a `ClassTag`
 * and delegating.
 */
abstract class TypedTool[In <: ToolInput](
  override val name: ToolName,
  override val description: String,
  override val examples: List[ToolExample] = Nil,
  override val modes: Set[Id[Mode]] = Set(ConversationMode.id),
  override val space: SpaceId = GlobalSpace,
  override val keywords: Set[String] = Set.empty,
  override val createdBy: Option[ParticipantId] = None
)(using ct: ClassTag[In], rwEv: RW[In]) extends Tool {

  override val inputRW: RW[In] = rwEv

  /** Implement this with the typed input — the base class casts safely. */
  protected def executeTyped(input: In, context: TurnContext): rapid.Stream[Event]

  final override def execute(input: ToolInput, context: TurnContext): rapid.Stream[Event] =
    if (ct.runtimeClass.isInstance(input)) executeTyped(input.asInstanceOf[In], context)
    else rapid.Stream.empty
}
