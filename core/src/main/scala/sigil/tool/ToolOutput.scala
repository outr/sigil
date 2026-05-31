package sigil.tool

import fabric.rw.*

/**
 * Marker for a tool's typed output payload — the `Output` half of a
 * [[Tool]]'s contract, parallel to [[ToolInput]] on the input side.
 *
 * `ToolOutput` is an open [[PolyType]]: framework-shipped subtypes are
 * registered eagerly via [[frameworkOutputRWs]], and apps add their
 * own concrete subtypes through `Sigil.toolOutputRegistrations`. The
 * typed result is carried on the durable [[sigil.event.ToolInvoke]]
 * event itself and folded in via [[sigil.signal.ToolDelta]] when the
 * tool settles.
 *
 * Framework-shipped cases:
 *   - [[Pending]]            — the call is in flight (the initial
 *                               state of every `ToolInvoke`).
 *   - [[Progress]]           — interim status report from a long-
 *                               running tool.
 *   - [[TextToolOutput]]     — the shared shape for tools whose
 *                               result is prose.
 *   - [[ImageToolOutput]]    — image result (sigil #280) — the
 *                               framework lifts the URL into the
 *                               agent's next-turn visual context so
 *                               the agent can actually see what the
 *                               tool produced.
 *
 * Apps define their own subtypes as `case class FooOutput(...) extends
 * ToolOutput derives RW` and register the `RW` via
 * `Sigil.toolOutputRegistrations`. The framework ships
 * [[TextToolOutput]] for the common markdown-text case.
 */
trait ToolOutput

object ToolOutput extends PolyType[ToolOutput]()(using scala.reflect.ClassTag(classOf[ToolOutput])) {

  /** Initial state of every [[sigil.event.ToolInvoke]] — the call has
    * been issued but the tool's `execute` hasn't yet settled it. A
    * [[sigil.signal.ToolDelta]] with `output = Some(...)` replaces
    * this with the real output (or a [[Progress]] interim). */
  case object Pending extends ToolOutput

  /** Interim progress update emitted by a long-running tool via
    * [[sigil.tool.ToolContext.reportProgress]]. Folded into the live
    * invoke by a [[sigil.signal.ToolDelta]]; consumers see the chip's
    * content advance through successive Progress values before the
    * final concrete `ToolOutput` lands.
    *
    * `percent` is `Some(0.0..1.0)` when the tool can express a real
    * completion fraction; `None` for unbounded "still working" pulses. */
  case class Progress(message: String, percent: Option[Double] = None) extends ToolOutput derives RW

  /** Every framework-shipped concrete `ToolOutput` subtype, registered
    * by default so apps that wire the optional tool families
    * (fs / git / process / web / random / memory / model / capability /
    * pagination / …) get polymorphic round-trip on `ToolDelta.output`
    * / `ToolInvoke.output` without per-app registration.
    *
    * App-defined subtypes (outside `sigil.tool.*`) still register via
    * `Sigil.toolOutputRegistrations`. */
  val frameworkOutputRWs: List[RW[? <: ToolOutput]] = List[RW[? <: ToolOutput]](
    RW.static(Pending),
    summon[RW[Progress]],
    summon[RW[TextToolOutput]],
    summon[RW[ImageToolOutput]],

    // core/
    summon[RW[sigil.tool.core.FindCapabilityOutput]],
    summon[RW[sigil.tool.core.RequestEscalationOutput]],
    summon[RW[sigil.tool.core.CancelFrameworkWorkflowOutput]],

    // util/
    summon[RW[sigil.tool.util.DelegateTaskOutput]],

    // model/
    summon[RW[sigil.tool.model.ContextBreakdownOutput]],
    summon[RW[sigil.tool.model.DeleteFileOutput]],
    summon[RW[sigil.tool.model.EditAtRangeOutput]],
    summon[RW[sigil.tool.model.EditFileOutput]],
    summon[RW[sigil.tool.model.GitBranchOutput]],
    summon[RW[sigil.tool.model.GitCommitOutput]],
    summon[RW[sigil.tool.model.GitDiffOutput]],
    summon[RW[sigil.tool.model.GitLogOutput]],
    summon[RW[sigil.tool.model.GitPushOutput]],
    summon[RW[sigil.tool.model.GitShowOutput]],
    summon[RW[sigil.tool.model.GitStatusOutput]],
    summon[RW[sigil.tool.model.HttpRequestOutput]],
    summon[RW[sigil.tool.model.ListMemoriesOutput]],
    summon[RW[sigil.tool.model.LookupOutput]],
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
    summon[RW[sigil.tool.model.WriteFileOutput]],

    // output/
    summon[RW[sigil.tool.output.JsonPagedResult]],

    // provider/
    summon[RW[sigil.tool.provider.CurrentModelOutput]],
    summon[RW[sigil.tool.provider.ListModelsOutput]]
  )

  // Eagerly register the framework cases so callers that summon the
  // `RW[ToolOutput]` before `Sigil.polymorphicRegistrations` runs
  // (codegen, schema dumps, isolated unit tests that skip the Sigil
  // boot) still see the framework's complete catalog.
  register(frameworkOutputRWs*)
}
