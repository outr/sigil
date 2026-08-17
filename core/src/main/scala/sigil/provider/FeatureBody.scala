package sigil.provider

/**
 * One block a [[ContextFeature]] contributed for this turn, plus the
 * [[Placement]] it wants — `None` uses the feature's own default.
 *
 * The override exists so a single feature can span both halves of the
 * prompt: an expansion module contributing stable usage guidance to the
 * cacheable prefix AND a volatile live-status line does it with one
 * registration rather than two artificial sibling features.
 */
case class FeatureBody(body: SectionBody, placement: Option[Placement] = None) {

  /** This body, pinned to `placement` regardless of the feature's default. */
  def at(placement: Placement): FeatureBody = copy(placement = Some(placement))

  /** The placement this body renders at, given its feature's default. */
  def placementIn(feature: ContextFeature): Placement = placement.getOrElse(feature.placement)
}

object FeatureBody {

  /** Prose, rendered as one unit and never budget-trimmed. */
  def prose(text: String): FeatureBody = FeatureBody(SectionBody.Blob(text))

  /** A header over independently droppable lines — the shape a feature
    * budget can actually trim. */
  def entries(header: String, lines: List[String], footer: String = ""): FeatureBody =
    FeatureBody(SectionBody.Entries(header, lines, footer))
}
