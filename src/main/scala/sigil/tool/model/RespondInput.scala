package sigil.tool.model

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for the respond tool.
 *
 * `content` is the multipart-format reply (`▶Text`, `▶Code <lang>`, etc.)
 * documented on the tool's description. The `@pattern` annotation enforces
 * the first header at the JSON Schema level so grammar-constrained decoders
 * cannot emit a bare string.
 *
 * `topic` is REQUIRED on every call and is the label of the current
 * conversation thread. Pass the current topic unchanged unless the
 * subject has shifted — the authoritative signal for what to do is
 * `topicChangeType`.
 *
 * `topicChangeType` is REQUIRED on every call. Categorical — the LLM
 * picks one of [[TopicChangeType]] to declare its intent. Forces a
 * deliberate choice on every turn and gives the framework an
 * unambiguous basis for deciding whether to emit a
 * [[sigil.event.TopicChange]] and which kind (Switch vs. Rename).
 */
case class RespondInput(@pattern("""^▶[A-Z][A-Za-z0-9]*(\s+\S+)?\n""") content: String,
                        topic: String,
                        topicChangeType: TopicChangeType)
  extends ToolInput derives RW
