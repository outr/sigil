package sigil.tool.consult

import fabric.define.{DefType, Definition}
import lightdb.id.Id
import sigil.tool.{Tool, ToolName, ToolSchema}

/**
 * Internal-only tool used by [[sigil.Sigil.classifyTopicShift]] to drive
 * the framework's two-step topic classifier. Constructed per-call with
 * the conversation's current prior-topic labels so the grammar-enforced
 * enum constrains the LLM to a valid choice.
 *
 * Never registered into any agent's tool roster — this is private
 * framework machinery invoked via [[ConsultTool.invoke]].
 */
class TopicClassifierTool(priorLabels: List[String]) extends Tool[TopicClassifierInput] {
  override protected def uniqueName: String = "classify_topic_shift"

  override protected def description: String =
    """Classify the proposed topic relative to the current and prior topics. Pick exactly one:
      |  - "NoChange" — same subject as the Current topic; nothing to relabel.
      |  - "Refine"   — same subject as Current, but a sharper / more specific label.
      |  - <prior label> — same subject as one of the prior topics; the user is returning.
      |  - "New"      — a subject genuinely different from Current and all priors.""".stripMargin

  /** Override the auto-derived schema with one whose `kind` field has a
    * dynamic enum populated from the per-call prior labels. */
  override lazy val schema: ToolSchema[TopicClassifierInput] = {
    val enumKeys: List[String] = "NoChange" :: "Refine" :: "New" :: priorLabels
    // Each enum value is a leaf with no payload — DefType.Null. The
    // surrounding DefType.Poly is what DefinitionToSchema renders as a
    // string enum (since all variants are Null).
    val polyValues: Map[String, Definition] =
      enumKeys.map(k => k -> Definition(DefType.Null)).toMap
    val inputDef = Definition(DefType.Obj(Map(
      "kind" -> Definition(DefType.Poly(polyValues))
    )))
    ToolSchema(
      id = Id[ToolSchema[TopicClassifierInput]](uniqueName),
      name = ToolName(uniqueName),
      description = description,
      input = inputDef,
      examples = Nil
    )
  }

  /** Never invoked as a regular tool — the classifier path uses
    * [[ConsultTool.invoke]] which calls the provider directly and
    * extracts the parsed input. */
  override def execute(input: TopicClassifierInput, context: sigil.TurnContext): rapid.Stream[sigil.event.Event] =
    rapid.Stream.empty
}
