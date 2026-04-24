package sigil.pipeline

import sigil.Sigil
import sigil.participant.ParticipantId
import sigil.signal.Signal

/**
 * Per-subscriber transform applied to signals before they reach a
 * given viewer. Unlike [[InboundTransform]] and [[SettledEffect]],
 * which run once per signal, a `ViewerTransform` runs once per
 * (signal, viewer) pair — different viewers can see different
 * versions of the same signal.
 *
 * Redaction is the canonical use case: strip sender-private metadata
 * for non-sender viewers. Apps may also add filtering (e.g., drop
 * signals a viewer shouldn't see at all by returning a placeholder),
 * viewer-aware enrichment, or rate-shaping.
 *
 * Applied by `Sigil.applyViewerTransforms(signal, viewer)` and by
 * the per-viewer stream helper `Sigil.signalsFor(viewer)`.
 */
trait ViewerTransform {
  def apply(signal: Signal, viewer: ParticipantId, self: Sigil): Signal
}
