package sigil.provider

import lightdb.id.Id
import rapid.{Stream, Task}
import sigil.db.Model
import sigil.tool.{Tool, ToolInput}
import spice.http.HttpRequest

trait Provider {
  def `type`: ProviderType
  def models: List[Model]

  /**
   * Build the underlying [[HttpRequest]] for a sigil request without
   * performing any network I/O. `apply` invokes this and dispatches the
   * resulting request; tests can call it directly to inspect the wire
   * payload (typically by reading `httpRequest.content` and asserting on
   * the JSON body).
   *
   * Returns `Task` because rendering may require async lookups —
   * resolving `TurnInput.memories` / `.summaries` ids to records is the
   * main reason today. Future providers may also need to fetch tools,
   * models, or other references at render time.
   *
   * The contract is that every Model-visible piece of the
   * `ProviderRequest` (view frames, memory records, summaries,
   * information catalog entries, participant projections, etc.) is
   * represented in the returned request's content.
   */
  def requestConverter(request: ProviderRequest): Task[HttpRequest]

  /**
   * Send a request and receive a stream of provider events. Implementations
   * back this with the HttpRequest produced by `requestConverter`. The
   * stream terminates with a `Done` event (or `Error`).
   */
  def apply(request: ProviderRequest): Stream[ProviderEvent]

  /**
   * One-shot synchronous call with a clean prompt — no conversation
   * frames, no priors, no projection rendering. Used by the framework
   * for focused sub-decisions (topic classification, intent detection,
   * structured-output extraction) and exposed to LLMs via
   * [[sigil.tool.consult.ConsultTool]] for cross-model consultation.
   *
   * If `tools` is non-empty, the call is sent with `tool_choice = required`
   * so the model is forced to invoke one of them; the result's `toolCall`
   * carries the parsed input. If `tools` is empty, the model returns
   * free-form text in `text`.
   */
  def consult(modelId: Id[Model],
              systemPrompt: String,
              userPrompt: String,
              tools: Vector[Tool[? <: ToolInput]] = Vector.empty,
              generationSettings: GenerationSettings = GenerationSettings()): Task[ConsultResult]
}
