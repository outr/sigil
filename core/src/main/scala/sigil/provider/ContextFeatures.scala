package sigil.provider

import rapid.Task
import sigil.diagnostics.ProfileSection

/**
 * The framework's registered [[ContextFeature]]s, and the compilation
 * from features down to [[ContextSection]]s.
 *
 * [[all]] is what [[sigil.Sigil.contextFeatures]] returns by default;
 * apps append their own and switch any of them off by id.
 */
object ContextFeatures {

  /** Framework features, in emission order. */
  val all: List[ContextFeature] = Nil

  /**
   * Compile features into the sections the renderer, the profiler, the
   * shed cascade, and `context_breakdown` consume.
   *
   * One section per (feature, placement): a body's placement is a
   * per-turn decision while the section list is static, so both halves
   * exist and each renders only the bodies that chose it — commonly
   * leaving the other empty, which renders as nothing. Both carry the
   * SAME [[ProfileSection.Feature]] id, so the profiler sums a
   * feature's tokens under one key however its bodies were placed.
   *
   * The shed stage rides the feature's default-placement section alone.
   * A `shed` edits the `TurnInput`, not a rendered position, so one
   * entry in the cascade covers the whole feature; two would run the
   * same effect twice and make the cascade look deeper than it is.
   *
   * Several bodies at one placement render as one block in emission
   * order. A budget can only trim the entry-shaped single-body case;
   * once a feature emits more than one body at a placement they
   * concatenate as prose, which is also what a feature wants when it
   * splits a narrative across bodies.
   */
  def sections(features: List[ContextFeature]): List[ContextSection] =
    features.flatMap { feature =>
      Placement.values.toList.map { placement =>
        ContextSection(
          id = ProfileSection.Feature(feature.id),
          placement = placement,
          shedStage = if (placement == feature.placement) feature.shedStage else None,
          render = c => merge(bodiesAt(c, feature, placement)),
          shed = if (placement == feature.placement) feature.shed else None,
          budget = feature.budget
        )
      }
    }

  /**
   * Run every feature's `compute` once and memoize the results onto the
   * context the renderer and the profiler then share.
   *
   * A feature that fails contributes nothing and the turn proceeds: a
   * live-source lookup timing out must not cost the user their turn.
   * The failure is logged with the feature's id.
   */
  def evaluate(features: List[ContextFeature], ctx: SectionContext): Task[SectionContext] =
    if (features.isEmpty) Task.pure(ctx)
    else Task.sequence(features.map { feature =>
      feature.compute(ctx)
        .map(bodies => feature.id -> bodies)
        .handleError { throwable =>
          Task {
            scribe.warn(s"ContextFeature '${feature.id.value}' failed to compute; contributing nothing this turn: " +
              s"${throwable.getClass.getSimpleName}: ${throwable.getMessage}")
            feature.id -> List.empty[FeatureBody]
          }
        }
    }).map(computed => ctx.copy(featureBodies = computed.toMap))

  private def bodiesAt(c: SectionContext, feature: ContextFeature, placement: Placement): List[SectionBody] =
    c.featureBodies.getOrElse(feature.id, Nil).filter(_.placementIn(feature) == placement).map(_.body)

  private def merge(bodies: List[SectionBody]): Option[SectionBody] = bodies match {
    case Nil        => None
    case one :: Nil => Some(one)
    case many       => Some(SectionBody.Blob(many.iterator.map(_.rendered).mkString))
  }
}
