package sigil.provider

import rapid.{Stream, Task}
import sigil.db.Model
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
}
