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
 * Sigil #265's collapse — `ContextFrame.ToolCall` + `ContextFrame.ToolResult`
 * fused into one stateful `ContextFrame.ToolCall(state: ToolCallState)`,
 * AND `Event.ToolResults` fused into the new stateful
 * [[sigil.event.ToolInvoke]] — retired two discriminators without
 * shipping a migration. Databases populated before #265 abort boot on
 * the events store's typed read:
 *
 * {{{
 *   // Inner-poly orphan (sigil #294):
 *   fabric.rw.RWException: sigil.event.Message.contextFrame
 *     - Unknown type discriminator: ToolResult
 *
 *   // Outer-poly orphan (sigil #295):
 *   RuntimeException: Type not found [ToolResults] converting from value
 *     {"_id":"…","type":"ToolResults",...}
 * }}}
 *
 * Two-pass migration walking `events.jsonStream` (so neither dead
 * discriminator can abort the read):
 *
 *   1. **Dead-outer drop (sigil #295).** Rows whose top-level
 *      `type` is in [[deadEventTypes]] (`ToolResults`, `ToolCall`)
 *      represent the pre-#265 tool-pair event model and have no
 *      typed counterpart today. Drop both halves of the orphaned
 *      tool transaction together — they're both dead-typed, so the
 *      same pass cleans up both. Conversation history loses the
 *      tool-call/result pairs but the surrounding `Message` events
 *      remain intact and self-consistent.
 *   2. **Dead-inner null (sigil #294).** Surviving rows whose inner
 *      `contextFrame.type == "ToolResult"` (typically `Message`
 *      events whose cached projection denormalized the old
 *      ToolResult shape) have `contextFrame` nulled and the typed
 *      row re-upserted. Durable fields — content, role,
 *      participant, timestamp — survive; only the cached frame
 *      projection drops. The framework's `framesFor` path uses
 *      `.flatMap(_.contextFrame)` so a missing projection is a
 *      benign "no frame for this row" rather than a hard fault.
 *
 * Pair-and-rewrite (reconstructing `ToolInvoke(state =
 * Complete(content, images))` by matching `ToolResults.origin`
 * against a paired half) was rejected:
 *   - The pre-#265 model's "paired half" wasn't a separate `ToolCall`
 *     event subtype (no `event/ToolCall.scala` ever existed in git
 *     history); the bug doc's reference is speculative.
 *   - Even if the paired half were reliably identifiable, the new
 *     `ToolInvoke` requires `toolName`, `input`, `output` fields whose
 *     positions in the old shape aren't deterministic across the
 *     full pre-#265 schema window.
 *   - Drop-and-continue is bootable, the conversation's surrounding
 *     Messages stay intact, and the framework's frame projection
 *     regenerates on demand for surviving rows.
 *
 * `alwaysRun = true, blockStartup = true`. Idempotent — once the
 * dead-outer rows are dropped and the dead-inner contextFrames are
 * nulled, subsequent runs see neither discriminator and do nothing
 * (a stream walk + zero writes is negligible cost).
 */
class ContextFrameToolResultMigrationUpgrade extends DatabaseUpgrade {
  override def label: String = "context-frame-toolresult-migration"
  override def alwaysRun: Boolean = true
  override def applyToNew: Boolean = true
  override def blockStartup: Boolean = true

  override def upgrade(ldb: LightDB): Task[Unit] = ldb match {
    case sigilDb: SigilDB => migrate(sigilDb)
    case _ => Task.unit
  }

  private def migrate(db: SigilDB): Task[Unit] =
    db.events.transaction { tx =>
      tx.jsonStream.toList.flatMap { rows =>
        val (toDelete, toUpsert) = ContextFrameToolResultMigrationUpgrade.classifyRows(rows)
        val drops: Task[Unit] =
          if (toDelete.isEmpty) Task.unit
          else {
            scribe.info(
              s"context-frame-toolresult-migration: dropping ${toDelete.size} legacy " +
                "dead-outer-discriminator row(s) (ToolResults/ToolCall — pre-#265 tool-pair model)."
            )
            Task.sequence(toDelete.map(id => tx.delete(Id[Event](id)))).unit
          }
        val rewrites: Task[Unit] =
          if (toUpsert.isEmpty) Task.unit
          else {
            scribe.info(
              s"context-frame-toolresult-migration: rewriting ${toUpsert.size} legacy " +
                "ContextFrame.ToolResult row(s) to contextFrame = null."
            )
            Task.sequence(toUpsert.map(tx.upsert)).unit
          }
        drops.flatMap(_ => rewrites)
      }
    }
}

object ContextFrameToolResultMigrationUpgrade {

  /**
   * Top-level Event-poly discriminators that #265 retired. Rows
   * carrying one of these as their `type` are dropped wholesale —
   * no surviving Scala class can decode them.
   */
  val deadEventTypes: Set[String] = Set("ToolResults", "ToolCall")

  /**
   * Whether the row's top-level `type` is a #265-retired Event
   * discriminator. Public for unit-test access.
   */
  def isDeadOuterEvent(json: Json): Boolean =
    json.get("type").map(_.asString).exists(deadEventTypes.contains)

  /**
   * Whether the row's `contextFrame` carries the retired
   * `ToolResult` discriminator. Public for unit-test access.
   */
  def isOrphanToolResult(json: Json): Boolean =
    json.get("contextFrame")
      .flatMap(_.get("type"))
      .map(_.asString)
      .contains("ToolResult")

  /**
   * Classify a batch of raw rows into the two migration outcomes:
   *
   *   - `toDelete`: row ids whose top-level `type` is dead-poly;
   *     the entire row gets dropped from the events store. Drop
   *     wins over rewrite — a row that's BOTH dead-outer AND
   *     dead-inner is dropped (no point trying to rewrite a row
   *     we can't decode at all).
   *   - `toUpsert`: rewritten typed [[Event]]s whose `contextFrame`
   *     has been nulled and the surviving fields successfully
   *     re-decoded. Caller upserts these back via the typed path.
   *
   * Returns `(toDelete, toUpsert)`.
   */
  def classifyRows(rows: List[Json]): (List[String], List[Event]) = {
    val toDelete = scala.collection.mutable.ListBuffer.empty[String]
    val toUpsert = scala.collection.mutable.ListBuffer.empty[Event]
    rows.foreach { json =>
      if (isDeadOuterEvent(json)) {
        extractOrphanId(json) match {
          case Some(id) => toDelete += id
          case None =>
            scribe.warn(
              s"context-frame-toolresult-migration: dead-outer row has no extractable _id; " +
                s"cannot drop. Type=${json.get("type").map(_.asString).getOrElse("<missing>")}"
            )
        }
      } else if (isOrphanToolResult(json)) {
        rewriteOrphanRow(json).foreach(toUpsert += _)
      }
    }
    (toDelete.toList, toUpsert.toList)
  }

  /**
   * Given a row whose inner `contextFrame` carries the dead
   * `ToolResult` discriminator (but whose top-level `type` is
   * still valid), return the rewritten typed [[Event]] with
   * `contextFrame = null`. Returns `None` when the rewrite still
   * fails to decode (some other dead discriminator in the row).
   * Stateless — pure JSON → Event transformation, no DB / framework
   * dependencies.
   */
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

  /**
   * Recover an event row's id from its raw JSON for drop / diagnostic
   * logging. Mirrors lightdb's stored `_id` field; returns `None`
   * when the JSON shape doesn't carry one.
   */
  def extractOrphanId(json: Json): Option[String] =
    json.get("_id").map(_.asString)
}
