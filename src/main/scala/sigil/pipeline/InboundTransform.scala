package sigil.pipeline

import rapid.Task
import sigil.Sigil
import sigil.signal.Signal

/**
 * Pre-persist transform applied to every signal passing through
 * `Sigil.publish`. Transforms run in declaration order (the order of
 * `Sigil.inboundTransforms`), each seeing the output of the previous.
 * A transform that doesn't care about a given signal kind should
 * return it unchanged.
 *
 * The transform runs on `publish`'s hot path — implementations should
 * be fast (hook lookups, local state, simple rewrites). Long-running
 * enrichment belongs in [[SettledEffect]] instead.
 */
trait InboundTransform {
  def apply(signal: Signal, self: Sigil): Task[Signal]
}
