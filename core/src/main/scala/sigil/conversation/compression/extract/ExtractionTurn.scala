package sigil.conversation.compression.extract

import lightdb.id.Id
import sigil.event.Event
import sigil.tool.ToolName

/**
 * One settled agent turn as the per-turn extraction pathway sees it.
 * Carries the rendered text halves the extraction consult reads plus
 * the structured turn evidence that text alone cannot express:
 *
 *   - `sourceEventIds` — the durable [[Event]] ids of the window the
 *     extraction runs over (the triggering user message + the turn's
 *     events). Stamped onto every extracted
 *     [[sigil.conversation.ContextMemory.sourceEventIds]] so a fact is
 *     traceable to the exchange that produced it.
 *   - `settledMutations` — names of tools that settled successfully
 *     during the turn with a [[sigil.tool.Effect.Mutating]] /
 *     [[sigil.tool.Effect.Destructive]] profile. A turn that changed
 *     external state is high-signal for agentic corpora regardless of
 *     how chatty its text was — [[AgenticSignalFilter]] gates on this.
 */
case class ExtractionTurn(userMessage: String,
                          agentResponse: String,
                          sourceEventIds: List[Id[Event]] = Nil,
                          settledMutations: List[ToolName] = Nil)
