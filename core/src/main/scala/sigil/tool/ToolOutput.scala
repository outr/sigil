package sigil.tool

import fabric.define.Definition
import fabric.rw.*
import scala.reflect.ClassTag

/**
 * Marker for a tool's typed output payload — the `Output` half of a
 * [[Tool]]'s contract, parallel to [[ToolInput]] on the input side.
 *
 * Sigil #265 — `ToolOutput` is an open polymorphic registry (apps
 * register their concrete subtypes via `Sigil.toolOutputRegistrations`)
 * so the framework can carry the typed result on the durable
 * [[sigil.event.ToolInvoke]] event itself, folded in via
 * [[sigil.signal.ToolDelta]] when the tool settles. Replaces the
 * pre-#265 paired-event model (separate `ToolResults` event linked
 * back to its invoke by `origin`) — there is now one stateful event
 * per tool call, and the framework no longer has to keep two events
 * in sync.
 *
 * Framework-shipped cases:
 *   - [[Pending]]  — the call is in flight, no output yet (the
 *                    initial state of every `ToolInvoke`).
 *   - [[Progress]] — interim status report from a long-running tool
 *                    (replaces the transient `ToolProgress` Notice).
 *
 * Concrete result types are app-defined: a tool author writes
 * `case class FooOutput(...) extends ToolOutput derives RW` and
 * registers the `RW` via `Sigil.toolOutputRegistrations`. The
 * framework ships `sigil.tool.TextToolOutput` for the common
 * markdown-text case.
 */
trait ToolOutput

object ToolOutput {

  /** Initial state of every [[sigil.event.ToolInvoke]] — the call
    * has been issued but the tool's `execute` hasn't yet settled it.
    * A [[sigil.signal.ToolDelta]] with `output = Some(...)` replaces
    * this with the real output (or a [[Progress]] interim). */
  case object Pending extends ToolOutput

  /** Interim progress update emitted by a long-running tool via
    * `ToolContext.progress(message, percent?)`. Folded into the live
    * invoke by a [[sigil.signal.ToolDelta]]; consumers see the chip's
    * content advance through successive Progress values before the
    * final concrete `ToolOutput` lands.
    *
    * `percent` is `Some(0.0..1.0)` when the tool can express a real
    * completion fraction; `None` for unbounded "still working" pulses. */
  case class Progress(message: String, percent: Option[Double] = None) extends ToolOutput derives RW

  /** Disambiguating class-name mapping for the polymorphic
    * discriminator. fabric's default mapping takes the segment after
    * the last `.` of a `$`-cleaned name — which collapses
    * `WriteFileOutput.Success` and `EditFileOutput.Success` into the
    * same `Success` key, breaking the round-trip for any framework
    * shipping multiple enum-shaped outputs that share variant names.
    *
    * We keep `$` in place (no `$ → .` swap), strip any package prefix
    * via the last-`.` split, and then remove `$` to collapse the
    * enum-parent + variant into a single ParentVariant identifier.
    * So `sigil.tool.model.WriteFileOutput$Success` becomes
    * `WriteFileOutputSuccess` and `sigil.tool.model.EditFileOutput$Success`
    * becomes `EditFileOutputSuccess` — distinct. Plain case-class
    * outputs (e.g. `sigil.tool.model.ReadFileOutput`) survive
    * unchanged as `ReadFileOutput`. */
  private val classNameMapping: String => String = { raw =>
    val lastDot = raw.lastIndexOf('.')
    val tail    = if (lastDot >= 0) raw.substring(lastDot + 1) else raw
    tail.replace("$", "")
  }

  @volatile private var registry: List[RW[? <: ToolOutput]] = Nil
  @volatile private var _rw: RW[ToolOutput] = generate()

  private def generate(): RW[ToolOutput] =
    RW.poly[ToolOutput](classNameMapping = classNameMapping)(registry*)

  /** Add subtypes to the polymorphic discriminator. Idempotent on
    * `definition.className` — duplicate registrations are ignored.
    * Call at backend startup before any serialization (the framework
    * threads this from `Sigil.polymorphicRegistrations`). */
  def register(types: RW[? <: ToolOutput]*): Unit = synchronized {
    val existingClassNames = registry.flatMap(_.definition.className).toSet
    val fresh = types.toList.filterNot(_.definition.className.exists(existingClassNames.contains))
    registry = registry ++ fresh
    _rw = generate()
  }

  /** Build one polymorphic-discriminator entry per enum variant that
    * proxies its read/write through the parent enum's `derives RW`.
    * Each entry advertises its variant via `definition.className =
    * "<parent-fqn>$<variant>"` so the disambiguating `classNameMapping`
    * resolves both the registration and runtime-instance sides to the
    * same `ParentVariant` key (e.g. `WriteFileOutputSuccess` vs
    * `EditFileOutputSuccess`) without colliding.
    *
    * On the JSON-write path the outer polymorphic merges its
    * disambiguated `"type": "WriteFileOutputSuccess"` over whatever
    * the inner parent emitted (`"type": "Success"`). To round-trip,
    * the wrapper's `write(json)` restores the leaf variant name on
    * `"type"` before delegating to the parent's enum RW — the
    * parent's variant dispatch expects the leaf form. */
  def enumCaseRWs[E <: ToolOutput](
      caseNames: String*
  )(using parentRw: RW[E], ct: ClassTag[E]): List[RW[? <: ToolOutput]] = {
    val parentClassName = ct.runtimeClass.getName
    caseNames.toList.map { caseName =>
      new RW[E] {
        override def read(t: E): fabric.Json = parentRw.read(t)
        override def write(json: fabric.Json): E =
          parentRw.write(json.merge(fabric.obj("type" -> fabric.str(caseName))))
        override def definition: Definition =
          parentRw.definition.copy(className = Some(s"$parentClassName$$$caseName"))
      }
    }
  }

  /** RWs the framework registers with the polymorphic discriminator —
    * Pending / Progress (declared above) plus every framework-shipped
    * concrete `ToolOutput` subtype, regardless of whether the app has
    * wired the corresponding tool into its `staticTools`. Registering
    * by default keeps the polymorphic round-trip working for any app
    * that uses the optional tool families (fs / git / process / web /
    * random / memory / model / capability / pagination / …) without
    * forcing every consumer to remember to list outputs by hand.
    *
    * Enum-shaped outputs (e.g. `WriteFileOutput.Success`, `Stale`,
    * `NotFound`) are aliased through `enumCaseRWs` so each variant
    * gets its own discriminator entry pointing back at the parent
    * enum's `derives RW`.
    *
    * App-defined subtypes (outside `sigil.tool.*`) still register
    * via `Sigil.toolOutputRegistrations`. */
  val frameworkOutputRWs: List[RW[? <: ToolOutput]] = List[RW[? <: ToolOutput]](
    RW.static(Pending),
    summon[RW[Progress]],
    summon[RW[TextToolOutput]],

    // core/
    summon[RW[sigil.tool.core.FindCapabilityOutput]],
    summon[RW[sigil.tool.core.RequestEscalationOutput]],

    // util/ — case-class outputs
    summon[RW[sigil.tool.util.AnswerWorkerOutput]],
    summon[RW[sigil.tool.util.DelegateTaskOutput]],

    // model/ — case-class outputs (enums go through `enumCaseRWs`
    // appended below)
    summon[RW[sigil.tool.model.ContextBreakdownOutput]],
    summon[RW[sigil.tool.model.DeleteFileOutput]],
    summon[RW[sigil.tool.model.HttpRequestOutput]],
    summon[RW[sigil.tool.model.ProcessListOutput]],
    summon[RW[sigil.tool.model.ProcessOutputResult]],
    summon[RW[sigil.tool.model.ProcessSignalOutput]],
    summon[RW[sigil.tool.model.ProcessSpawnOutput]],
    summon[RW[sigil.tool.model.RandomChoiceOutput]],
    summon[RW[sigil.tool.model.RandomDoubleOutput]],
    summon[RW[sigil.tool.model.RandomIntOutput]],
    summon[RW[sigil.tool.model.RandomUuidOutput]],
    summon[RW[sigil.tool.model.ReadFileOutput]],
    summon[RW[sigil.tool.model.SaveMemoryOutput]],
    summon[RW[sigil.tool.model.SearchConversationOutput]],
    summon[RW[sigil.tool.model.SemanticSearchOutput]],
    summon[RW[sigil.tool.model.SystemStatsOutput]],
    summon[RW[sigil.tool.model.WebFetchOutput]],
    summon[RW[sigil.tool.model.WebSearchOutput]],

    // output/ — streaming-pagination payload
    summon[RW[sigil.tool.output.JsonPagedResult]],

    // provider/
    summon[RW[sigil.tool.provider.CurrentModelOutput]],
    summon[RW[sigil.tool.provider.ListModelsOutput]]
  ) ++
    // Enum-shaped outputs — one variant alias each.
    enumCaseRWs[sigil.tool.core.CancelFrameworkWorkflowOutput]("Cancelled", "NotActive", "AlreadyCancelled") ++
    enumCaseRWs[sigil.tool.model.EditAtRangeOutput]("Success") ++
    enumCaseRWs[sigil.tool.model.EditFileOutput]("Success", "NotFound", "NotUnique", "Stale", "FileNotFound") ++
    enumCaseRWs[sigil.tool.model.GitBranchOutput]("Listed", "Failed") ++
    enumCaseRWs[sigil.tool.model.GitCommitOutput]("Committed", "Failed") ++
    enumCaseRWs[sigil.tool.model.GitDiffOutput]("Text", "Hunks", "Failed") ++
    enumCaseRWs[sigil.tool.model.GitLogOutput]("Listed", "Failed") ++
    enumCaseRWs[sigil.tool.model.GitPushOutput]("Pushed", "Failed") ++
    enumCaseRWs[sigil.tool.model.GitShowOutput]("Found", "Failed") ++
    enumCaseRWs[sigil.tool.model.GitStatusOutput]("Reported", "Failed") ++
    enumCaseRWs[sigil.tool.model.ListMemoriesOutput]("Listed", "NoAccessibleSpaces") ++
    enumCaseRWs[sigil.tool.model.LookupOutput]("Found", "NotFound", "NotRetrievable") ++
    enumCaseRWs[sigil.tool.model.WriteFileOutput]("Success", "Stale", "NotFound")

  // Eagerly register the framework cases so callers that summon the
  // `RW[ToolOutput]` before `Sigil.polymorphicRegistrations` runs
  // (codegen, schema dumps, isolated unit tests that skip the Sigil
  // boot) still see the framework's complete catalog.
  register(frameworkOutputRWs*)

  given rw: RW[ToolOutput] with {
    override def read(t: ToolOutput): fabric.Json     = _rw.read(t)
    override def write(json: fabric.Json): ToolOutput = _rw.write(json)
    override def definition: Definition                = _rw.definition
  }
}
