package sigil.provider

import rapid.Task
import sigil.conversation.TurnInput

/**
 * A registered, per-turn contribution to the system prompt.
 *
 * `ContextFeature` is the composition and lifecycle layer over
 * [[ContextSection]]; it is not a second taxonomy. Every enabled feature
 * COMPILES DOWN to sections ([[ContextFeatures.sections]]), so the one
 * section list stays what the renderer concatenates, the profiler
 * counts, the curator sheds, and `context_breakdown` reports. A parallel
 * module system that bypassed sections would escape all four.
 *
 * What the layer adds on top of registering a section directly:
 *
 *   - **Effects.** [[compute]] is a `Task`, so a feature may consult a
 *     live source (connectivity status, presence, a calendar) without a
 *     second migration. Sections stay pure because several consumers
 *     render the same request: the request pipeline runs every enabled
 *     feature's `compute` exactly ONCE and memoizes the result into
 *     [[SectionContext.featureBodies]], and the compiled sections are
 *     pure readers of that map. A feature that throws contributes
 *     nothing for the turn rather than failing the request — a stale
 *     status line is a smaller loss than a dropped turn.
 *   - **Open identity.** [[id]] attributes the feature's tokens in the
 *     profiler under [[sigil.diagnostics.ProfileSection.Feature]] no
 *     matter how many bodies it emitted or where they landed, and it is
 *     the handle [[sigil.Sigil.disabledFeatures]] switches it off by.
 *   - **Shipped defaults.** The framework registers its own features in
 *     [[ContextFeatures.all]]; apps append theirs to
 *     [[sigil.Sigil.contextFeatures]] and disable any of them by id.
 *
 * `placement`, `shedStage`, `shed`, and `budget` pass through to the
 * compiled sections and mean exactly what they mean there — including
 * the rule that a declared `shedStage` without a `shed` effect fails
 * startup.
 */
trait ContextFeature {

  /**
   * Stable identity — the disable handle and the profiler key.
   */
  def id: FeatureId

  /**
   * Whether merely registering the feature makes it contribute.
   * Framework features ship `true`; a module that wants its context
   * opt-in ships `false` and the app turns it on by overriding
   * [[sigil.Sigil.featureEnabled]].
   */
  def defaultEnabled: Boolean = true

  /**
   * Where this feature's bodies land when they don't say otherwise.
   * Anything computed fresh per turn belongs in
   * [[Placement.VolatileTail]] — a per-turn value in the stable prefix
   * costs the whole request its cross-turn cache hit.
   */
  def placement: Placement

  /**
   * Position in the curator's shed cascade — lower sheds first; `None`
   * never sheds. A feature takes part on exactly the terms a section
   * does, so a stage declared here REQUIRES [[shed]].
   */
  def shedStage: Option[Int] = None

  /**
   * How the curator drops this feature's contribution from a turn.
   * Placement-agnostic: it edits the `TurnInput` the feature's
   * `compute` later reads, so one effect covers every body.
   */
  def shed: Option[TurnInput => TurnInput] = None

  /**
   * Tokens this feature's bodies may occupy, `None` unbounded. Bites
   * on entry-shaped bodies only, like any section budget.
   */
  def budget: Option[Int] = None

  /**
   * This turn's contributions, or `Nil` for none. Called exactly once
   * per request, before anything renders.
   */
  def compute(ctx: SectionContext): Task[List[FeatureBody]]
}
