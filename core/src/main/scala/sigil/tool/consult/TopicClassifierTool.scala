package sigil.tool.consult

import fabric.define.{DefType, Definition}
import sigil.provider.{ClassificationWork, GenerationSettings, ReasoningMode, WorkType}
import sigil.tool.{ToolName, TypedTool}

/**
 * Internal-only tool used by [[sigil.Sigil.classifyTopicShift]] to drive
 * the framework's two-step topic classifier. Constructed per-call with
 * the conversation's current prior-topic labels so the grammar-enforced
 * enum constrains the LLM to a valid choice.
 */
class TopicClassifierTool(priorLabels: List[String]) extends TypedTool[TopicClassifierInput](
  name = ToolName("classify_topic_shift"),
  description =
    """Classify the proposed topic relative to the current and prior topics. Pick exactly one:
      |  - "NoChange" — same subject as the Current topic; nothing to relabel.
      |  - "Refine"   — same subject as Current, but a sharper / more specific label.
      |  - <prior label> — same subject as one of the prior topics; the user is returning.
      |  - "New"      — a subject genuinely different from Current and all priors.""".stripMargin
) with FrameworkConsult {
  override def paginate: Boolean = false

  /** Categorical decision — routes through the cheap classification tier. */
  override def consultWorkType: WorkType = ClassificationWork

  /** Output is a single enum string; 256 tokens covers the structured
    * payload plus a reasoning-spill margin for thinking-capable models. */
  override def consultSettings: GenerationSettings = GenerationSettings(
    maxOutputTokens = Some(256),
    reasoningMode = ReasoningMode.Off
  )

  /** Override the schema's input definition with one whose `kind` field
    * has a dynamic enum populated from the per-call prior labels. */
  override def inputDefinition: Definition = {
    val enumKeys: List[String] = "NoChange" :: "Refine" :: "New" :: priorLabels
    val polyValues: Map[String, Definition] =
      enumKeys.map(k => k -> Definition(DefType.Null)).toMap
    Definition(DefType.Obj(Map(
      "kind" -> Definition(DefType.Poly(polyValues))
    )))
  }

  override protected def executeTyped(input: TopicClassifierInput, context: sigil.TurnContext): rapid.Stream[sigil.event.Event] =
    rapid.Stream.empty
}
