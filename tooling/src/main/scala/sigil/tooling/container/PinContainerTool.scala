package sigil.tooling.container

import fabric.rw.*
import rapid.Task
import sigil.TurnContext
import sigil.tool.{Tool, ToolName}

/**
 * Mark every row of a container as pinned. The conversation-level
 * container cleanup skips pinned rows when age- or size-pruning.
 * Use this for containers the user / agent wants to keep around
 * for unusually long workflows (a conversation paused for a week,
 * a reference list curated by the user).
 */
case object PinContainerTool extends Tool {
  type Input  = PinContainerInput
  type Output = PinContainerOutput
  val inputRW  = summon[RW[PinContainerInput]]
  val outputRW = summon[RW[PinContainerOutput]]

  val name = ToolName("pin_container")
  val description =
    """Mark a container's rows as pinned so the conversation-level cleanup skips them.
      |Use when the container is worth keeping across long idle windows (a conversation
      |paused for a week, a curated reference list). Idempotent — re-pinning an
      |already-pinned container reports zero rows affected.""".stripMargin
  override val keywords = Set("pin", "container", "preserve", "keep", "retain", "no gc")

  override def paginate: Boolean = false

  override def executeOutput(input: PinContainerInput,
                             ctx: TurnContext): Task[PinContainerOutput] =
    ContainerSupport.readItems(ctx.sigil, ctx.conversation.id, input.itemsId).flatMap { rows =>
      val needsUpdate = rows.filterNot(_.pinned)
      if (needsUpdate.isEmpty) Task.pure(PinContainerOutput(itemsId = input.itemsId, rowsAffected = 0))
      else ctx.sigil.withDB(_.toolOutputs.transaction { tx =>
        Task.sequence(needsUpdate.map(r => tx.upsert(r.copy(pinned = true))))
          .map(_ => PinContainerOutput(itemsId = input.itemsId, rowsAffected = needsUpdate.size))
      })
    }
}
