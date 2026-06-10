package sigil.upgrade

import fabric.{Json, Null, Obj, obj, str}
import fabric.rw.*
import lightdb.LightDB
import lightdb.upgrade.DatabaseUpgrade
import rapid.Task
import sigil.db.SigilDB
import sigil.event.Event
import sigil.tool.ToolOutput

/**
 * Rescue boot for databases holding a `ToolInvoke` whose `output`
 * names a [[sigil.tool.ToolOutput]] subtype that has since been
 * renamed, moved, or removed. `ToolInvoke.output` is a polymorphic
 * field on a durable event and the events store is read typed, so a
 * single such row throws `Type not found [<discriminator>]` on the
 * typed read and bricks the whole store at startup (sigil #374). Real
 * trigger: `browser_screenshot`'s output renamed `BrowserScreenshotOutput`
 * → `ImageToolOutput` (commit b1ed27c1) made every DB with a prior
 * screenshot event fail to boot.
 *
 * Walks `events.jsonStream` (raw, so the dead discriminator can't abort
 * the read) and, for each `ToolInvoke` row whose `output` block can no
 * longer be decoded as any registered `ToolOutput`, rewrites that block
 * to [[sigil.tool.UnknownToolOutput]] — preserving the original block
 * verbatim in `raw` — then re-upserts the now-decodable typed event.
 * Nothing is destroyed: the original bytes live on in `raw`, recoverable
 * if a consumer later re-registers the real type. The repair is precise
 * — it touches `output` only when that specific block is the orphan, so
 * a valid output paired with some other dead field is left untouched for
 * the migration that owns it.
 *
 * `alwaysRun = true, blockStartup = true` so a rename in any future
 * build is reconciled before consumers stream events. Runs after
 * [[polymorphicRegistrations]] has registered the framework + app
 * `ToolOutput` catalog, so a valid app subtype is never misjudged as an
 * orphan. Idempotent — once rewritten, rows decode cleanly and
 * subsequent runs do nothing (a stream walk + zero writes).
 */
class ToolOutputReconcileUpgrade extends DatabaseUpgrade {
  override def label: String = "tool-output-reconcile"
  override def alwaysRun: Boolean = true
  override def applyToNew: Boolean = true
  override def blockStartup: Boolean = true

  override def upgrade(ldb: LightDB): Task[Unit] = ldb match {
    case db: SigilDB => reconcile(db)
    case _           => Task.unit  // not our DB shape, skip
  }

  private def reconcile(db: SigilDB): Task[Unit] =
    db.events.transaction { tx =>
      tx.jsonStream.toList.flatMap { rows =>
        val repaired = rows.flatMap(ToolOutputReconcileUpgrade.repairedEvent)
        if (repaired.isEmpty) Task.unit
        else {
          scribe.info(
            s"tool-output-reconcile: rewriting ${repaired.size} ToolInvoke row(s) whose " +
              "ToolOutput discriminator is no longer registered → UnknownToolOutput."
          )
          Task.sequence(repaired.map(tx.upsert)).unit
        }
      }
    }
}

object ToolOutputReconcileUpgrade {

  /** Whether the row's top-level Event discriminator is `ToolInvoke`
    * (the only event carrying a polymorphic `output`). Public for
    * unit-test access. */
  def isToolInvoke(json: Json): Boolean =
    json.get("type").map(_.asString).contains("ToolInvoke")

  /** Whether the row's `output` block can no longer be decoded as any
    * registered [[ToolOutput]] — i.e. its discriminator names a
    * renamed/removed subtype. Uses fabric's own poly dispatch (not
    * name matching) so class-chain / legacy-leaf / alias forms resolve
    * exactly as a real read would. Public for unit-test access. */
  def outputIsOrphan(json: Json): Boolean =
    json.get("output").exists { out =>
      scala.util.Try(out.as[ToolOutput](using summon[RW[ToolOutput]])).isFailure
    }

  /** Replace the `output` block with the [[sigil.tool.UnknownToolOutput]]
    * shape, preserving the original block verbatim in `raw` and its
    * discriminator in `typeTag`. Returns `None` when there is no
    * `output` field. Pure JSON → JSON. Public for unit-test access. */
  def rewriteOutput(json: Json): Option[Json] =
    json.get("output").map { original =>
      val typeTag = original.get("type").map(_.asString).getOrElse("<unknown>")
      val replacement = obj("type" -> str("UnknownToolOutput"), "typeTag" -> str(typeTag), "raw" -> original)
      Obj(json.asMap.updated("output", replacement))
    }

  /** `Some(repaired Event)` only when the row is a `ToolInvoke` whose
    * `output` is a genuine orphan AND the row decodes once that block
    * is rewritten. None when the row isn't a `ToolInvoke`, its output
    * is still valid, or it can't be made decodable this way. Two-tier:
    * output rewrite alone, then also nulling the regenerable
    * `contextFrame` projection in case its cached copy embeds the same
    * dead discriminator. */
  def repairedEvent(json: Json): Option[Event] = {
    if (!isToolInvoke(json) || !outputIsOrphan(json)) return None
    val eventRW = summon[RW[Event]]
    rewriteOutput(json).flatMap { rewritten =>
      scala.util.Try(rewritten.as[Event](using eventRW)).toOption.orElse {
        val frameless = Obj(rewritten.asMap.updated("contextFrame", Null))
        scala.util.Try(frameless.as[Event](using eventRW)) match {
          case scala.util.Success(event) => Some(event)
          case scala.util.Failure(err) =>
            scribe.warn(
              s"tool-output-reconcile: ToolInvoke row failed to decode even after rewriting " +
                s"output and nulling contextFrame (${err.getClass.getSimpleName}: " +
                s"${Option(err.getMessage).getOrElse("")}); leaving in place. " +
                s"_id=${extractId(json).getOrElse("<unknown>")}"
            )
            None
        }
      }
    }
  }

  /** Recover an event row's id from its raw JSON for diagnostic
    * logging. Mirrors lightdb's stored `_id` field. */
  def extractId(json: Json): Option[String] =
    json.get("_id").map(_.asString)
}
