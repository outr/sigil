package sigil.upgrade

import fabric.{Json, Null, obj}
import fabric.rw.*
import lightdb.LightDB
import lightdb.id.Id
import lightdb.upgrade.DatabaseUpgrade
import rapid.Task
import sigil.db.SigilDB
import sigil.event.Event

/**
 * Sigil #294 — rescue boot for databases populated before Sigil #265
 * collapsed `ContextFrame.ToolCall` + `ContextFrame.ToolResult` into a
 * single stateful `ContextFrame.ToolCall(state: ToolCallState)`. The
 * `ToolResult` discriminator was retired without a migration, so any
 * existing event row whose `contextFrame.type == "ToolResult"` fails
 * to decode at boot:
 *
 * {{{
 *   fabric.rw.RWException: sigil.event.Message.contextFrame
 *     - Unknown type discriminator: ToolResult
 * }}}
 *
 * Drop-and-continue rewrite. Walks `events` via `jsonStream` (so the
 * dead discriminator can't abort the read), nulls `contextFrame` on
 * any orphan row, and re-upserts the typed Event. The durable event
 * data — content, role, participant, timestamp — survives untouched;
 * only the cached frame projection drops. The framework's
 * `framesFor` path uses `.flatMap(_.contextFrame)` so a missing
 * projection is a benign "no frame for this row" rather than a hard
 * fault; the projection regenerates the next time the event settles
 * through the publish pipeline.
 *
 * Pair-and-rewrite (reconstructing the new `ToolCall(state =
 * Complete(content, images))` shape by matching the orphan's `callId`
 * against the prior `ToolCall` frame) was considered and rejected as
 * too fragile against real-world data: interleaved events, partial
 * writes, missing pairs after manual deletes. Drop-and-continue
 * keeps the bug doc's secondary path — bootable, lossy at the
 * projection layer only — and lets the framework re-derive on
 * demand.
 *
 * `alwaysRun = true` so the upgrade is safe on every boot. Idempotent
 * — once the orphan rows are rewritten, subsequent runs see no
 * `"ToolResult"` discriminators and do nothing (a stream walk + zero
 * upserts is negligible cost).
 */
class ContextFrameToolResultMigrationUpgrade extends DatabaseUpgrade {
  override def label: String = "context-frame-toolresult-migration"
  override def alwaysRun: Boolean = true
  override def applyToNew: Boolean = true
  override def blockStartup: Boolean = true

  override def upgrade(ldb: LightDB): Task[Unit] = ldb match {
    case sigilDb: SigilDB => migrate(sigilDb)
    case _                => Task.unit
  }

  private def migrate(db: SigilDB): Task[Unit] = {
    db.events.transaction { tx =>
      tx.jsonStream.toList.flatMap { rows =>
        val rewrites: List[Event] = rows.flatMap(ContextFrameToolResultMigrationUpgrade.rewriteOrphanRow)
        if (rewrites.isEmpty) Task.unit
        else {
          scribe.info(
            s"context-frame-toolresult-migration: rewriting ${rewrites.size} legacy " +
              "ContextFrame.ToolResult row(s) to contextFrame = null."
          )
          Task.sequence(rewrites.map(tx.upsert)).unit
        }
      }
    }
  }
}

object ContextFrameToolResultMigrationUpgrade {

  /** Whether the row's `contextFrame` carries the retired
    * `ToolResult` discriminator. Public for unit-test access. */
  def isOrphanToolResult(json: Json): Boolean =
    json.get("contextFrame")
      .flatMap(_.get("type"))
      .map(_.asString)
      .contains("ToolResult")

  /** Given an orphan row, return the rewritten typed [[Event]] with
    * `contextFrame = null`. Returns `None` when the row is not an
    * orphan, OR when the rewrite still fails to decode (some other
    * dead discriminator in the row). Stateless — pure JSON → Event
    * transformation, no DB / framework dependencies. */
  def rewriteOrphanRow(json: Json): Option[Event] = {
    if (!isOrphanToolResult(json)) return None
    val rewritten = json.merge(obj("contextFrame" -> Null))
    val eventRW: RW[Event] = summon[RW[Event]]
    scala.util.Try(rewritten.as[Event](using eventRW)) match {
      case scala.util.Success(event) => Some(event)
      case scala.util.Failure(err) =>
        scribe.warn(
          s"context-frame-toolresult-migration: orphan event row failed to decode " +
            s"even after nulling contextFrame (${err.getClass.getSimpleName}: " +
            s"${Option(err.getMessage).getOrElse("")}); leaving in place. " +
            s"_id=${extractOrphanId(json).getOrElse("<unknown>")}"
        )
        None
    }
  }

  /** Recover an event row's id from its raw JSON for diagnostic logs
    * when a fallback decode fails. Mirrors lightdb's stored `_id`
    * field; returns `None` when the JSON shape doesn't carry one. */
  def extractOrphanId(json: Json): Option[String] =
    json.get("_id").map(_.asString)
}
