package sigil.tool.consult

import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolName, TypedTool}

/**
 * Internal-only one-shot tool. Invoked by [[sigil.Sigil.persistMemory]]
 * / [[sigil.Sigil.upsertMemoryByKey]] right before the write — produces
 * keywords + permanence + space classification for the memory in a
 * single LLM round-trip.
 *
 * Never registered on any agent's roster — the framework calls it via
 * [[ConsultTool.invoke]] with `tool_choice = required`.
 */
case object ClassifyMemoryTool extends TypedTool[ClassifyMemoryInput](
  name = ToolName("classify_memory"),
  description =
    """Classify a memory the framework is about to persist. Produce three decisions in one call:
      |
      |1. `keywords` — 5–10 retrieval-shaped tokens for this memory. Pick terms a future query
      |   will plausibly mention: identifiers, names, languages, frameworks, file types,
      |   components, concepts, applicability terms (e.g. "code-style", "naming", "review").
      |   Skip generic words ("user", "task", "memory", "note"), stop words, articles.
      |   Lowercase, single-word tokens. Aim for precision over volume.
      |
      |2. `permanence` — `"Always"` if the user's most recent message used a durative imperative
      |   ("always", "never", "from now on", "every time", "must", "should never") signaling a
      |   rule that applies regardless of topic. `"Once"` for soft preferences ("I like", "I tend
      |   to", "for this project") or factual statements where topical retrieval will surface the
      |   memory only when relevant. When unsure, default to `"Once"` — over-pinning is worse than
      |   under-pinning because pinned memories load every turn.
      |
      |3. `space` — pick exactly one `value` from the accessible spaces listed in the prompt that
      |   best matches the memory's scope. Use the most-specific applicable space. If two spaces
      |   are equally applicable and the memory genuinely belongs in either / unclear, output the
      |   literal string `"ambiguous"` and supply `ambiguityReason` describing the conflict.
      |
      |   - User-scoped facts ("Alice prefers X") → user space
      |   - Project-scoped rules ("This project uses Scala 3") → project space
      |   - Truly universal facts → global space
      |
      |   Output ONLY the `value` field of the chosen space, exact-match.
      |
      |4. `ambiguityReason` — required when `space == "ambiguous"`; one short sentence telling
      |   the user what's unclear ("could apply to user or project; please pick").""".stripMargin
) {
  override protected def executeTyped(input: ClassifyMemoryInput, context: TurnContext): Stream[Event] =
    Stream.empty
}
