package sigil.provider

import rapid.Stream
import sigil.db.Model

trait Provider {
  def `type`: ProviderType
  def models: List[Model]

  /**
   * Send a request and receive a stream of provider events. Implementations
   * may back this with a real streaming API or synthesize a stream from a
   * non-streaming call. The stream terminates with a `Done` event (or `Error`).
   */
  def apply(request: ProviderRequest): Stream[ProviderEvent]
}
