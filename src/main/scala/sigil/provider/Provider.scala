package sigil.provider

import rapid.Stream
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
   * The contract is that every Model-visible piece of the
   * `ProviderRequest` (Messages, ToolInvokes, ModeChange, ToolResults,
   * TitleChange, etc.) is represented in the returned request's content.
   */
  def requestConverter(request: ProviderRequest): HttpRequest

  /**
   * Send a request and receive a stream of provider events. Implementations
   * back this with the HttpRequest produced by `requestConverter`. The
   * stream terminates with a `Done` event (or `Error`).
   */
  def apply(request: ProviderRequest): Stream[ProviderEvent]
}
