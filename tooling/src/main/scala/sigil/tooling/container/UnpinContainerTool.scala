package sigil.tooling.container

import rapid.Task
import sigil.TurnContext
import sigil.tool.{ToolName, TypedOutputTool}

/**
 * Inverse of [[PinContainerTool]] — clears the `pinned` flag on
 * every row of a container so the conversation-level cleanup
 * resumes counting it for age / size pruning.
 */
case object UnpinContainerTool extends TypedOutputTool[PinContainerInput, PinContainerOutput](
  name = ToolName("unpin_container"),
  description =
    """Clear the pin flag on a container's rows so the conversation-level cleanup
      |resumes age- / size-pruning it. Idempotent — un-pinning an already-unpinned
      |container reports zero rows affected.""".stripMargin,
  keywords = Set("unpin", "container", "release", "gc")
) {
  override def paginate: Boolean = false

  override protected def executeTyped(input: PinContainerInput,
                                      ctx: TurnContext): Task[PinContainerOutput] =
    ContainerSupport.readItems(ctx.sigil, ctx.conversation.id, input.itemsId).flatMap { rows =>
      val needsUpdate = rows.filter(_.pinned)
      if (needsUpdate.isEmpty) Task.pure(PinContainerOutput(itemsId = input.itemsId, rowsAffected = 0))
      else ctx.sigil.withDB(_.toolOutputs.transaction { tx =>
        Task.sequence(needsUpdate.map(r => tx.upsert(r.copy(pinned = false))))
          .map(_ => PinContainerOutput(itemsId = input.itemsId, rowsAffected = needsUpdate.size))
      })
    }
}
