package sigil.tool

/**
 * A tool's identity and capabilities, validated at creation. The
 * private constructor makes [[ToolSpec.apply]] the single gate:
 * every path that produces a `ToolSpec` — static case objects,
 * dynamic records deserialized from the DB — has passed validation,
 * so a persisted row that has drifted out of validity fails loudly
 * on load rather than at first dispatch.
 */
final case class ToolSpec private (name: ToolName,
                                   description: String,
                                   profile: ToolProfile,
                                   discovery: DiscoverySpec) {

  /**
   * Convenience projections.
   */
  def keywords: Set[String] = discovery.keywords
  def effect: Effect = profile.effect
}

object ToolSpec {

  /**
   * Ceiling on wire-facing description length in characters.
   * Providers accept descriptions well past this, but a description
   * beyond it is a per-turn token tax on every request carrying the
   * roster; the largest legitimate framework description sits near
   * 3.5K.
   */
  val DescriptionBudget: Int = 4096

  /**
   * Validation gate. Collects every violation and raises one
   * [[ToolSpecException]] carrying them all:
   *
   *   - description non-blank and within [[DescriptionBudget]]
   *   - destructive consequence non-blank
   *   - detachable progress story non-blank
   *   - consent prompt non-blank when a consent gate is declared
   *   - discoverable kinds (see [[ToolKind.discoverable]]) declare
   *     non-empty keywords
   */
  def apply(name: ToolName,
            description: String,
            profile: ToolProfile,
            discovery: DiscoverySpec = DiscoverySpec.default): ToolSpec = {
    val violations = List.newBuilder[String]
    if (description.isBlank)
      violations += "description must be non-blank"
    else if (description.length > DescriptionBudget)
      violations += s"description is ${description.length} chars — over the $DescriptionBudget-char wire budget"
    profile.effect match {
      case Effect.Destructive(_, consequence) if consequence.isBlank =>
        violations += "Effect.Destructive requires a non-blank consequence (it becomes the wire warning)"
      case _ => ()
    }
    profile.execution match {
      case Execution.Detachable(_, progress) if progress.story.isBlank =>
        violations += "Execution.Detachable requires a non-blank ProgressContract story"
      case _ => ()
    }
    profile.gates.consent match {
      case Some(ConsentSpec(prompt)) if prompt.isBlank =>
        violations += "a consent gate requires a non-blank ConsentSpec prompt"
      case _ => ()
    }
    if (discovery.kind.discoverable && discovery.keywords.isEmpty)
      violations += s"discoverable tools (kind '${discovery.kind.value}') require non-empty keywords"
    val collected = violations.result()
    if (collected.nonEmpty) throw new ToolSpecException(name.value, collected)
    new ToolSpec(name, description, profile, discovery)
  }
}
