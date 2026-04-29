package sigil.tool.model

import rapid.Task
import sigil.Sigil
import sigil.participant.ParticipantId

/**
 * Extension methods on [[ResponseContent]] that need access to the
 * live [[Sigil]] runtime — kept out of the `enum` itself so the
 * data type stays pure-data.
 *
 * The marquee operation is [[dereference]]: turns a
 * [[ResponseContent.StoredFileReference]] into the full-content
 * variant the bytes originally lived as (`Code` for textual MIME
 * types, `Image` for image MIME types, `Text` carrying base64 for
 * everything else). Renderers that need the materialized content
 * — agent prompt-building, search indexing, UI editor opens —
 * call this; renderers that only need the chip / preview don't.
 *
 * Non-reference variants pass through unchanged so callers can
 * `.dereference(...)` blindly across a content vector.
 */
object ResponseContentOps {

  extension (rc: ResponseContent) {

    /**
     * Materialize the bytes behind a [[ResponseContent.StoredFileReference]]
     * and return the variant the bytes most naturally fit. Everything
     * else returns unchanged. Authz: the caller's `chain` must
     * authorize the file's space via
     * [[sigil.Sigil.accessibleSpaces]] — unauthorized fetches
     * surface as a `Text("[file no longer available]")` block so
     * downstream consumers don't break on missing content.
     */
    def dereference(sigil: Sigil, chain: List[ParticipantId]): Task[ResponseContent] = rc match {
      case ref: ResponseContent.StoredFileReference =>
        sigil.fetchStoredFile(ref.fileId, chain).map {
          case None =>
            ResponseContent.Text("[file no longer available]")
          case Some((_, bytes)) =>
            val ct = ref.contentType.toLowerCase
            if (ct.startsWith("image/")) {
              // No live URL on dereference — round-trip through a
              // data URL so renderers always have something to show.
              spice.net.URL.get(s"data:${ref.contentType};base64,${java.util.Base64.getEncoder.encodeToString(bytes)}",
                tldValidation = spice.net.TLDValidation.Off).toOption match {
                case Some(url) => ResponseContent.Image(url, altText = Some(ref.title))
                case None      => ResponseContent.Text("[file could not be rendered]")
              }
            } else if (ct.startsWith("text/") || ct == "application/json" || ct == "application/xml") {
              ResponseContent.Code(new String(bytes, "UTF-8"), language = ref.language)
            } else {
              // Unknown binary — preserve as base64 inside a Code block
              // so the LLM can still reason about it (and apps can choose
              // whether to render or skip).
              ResponseContent.Code(java.util.Base64.getEncoder.encodeToString(bytes),
                language = ref.language.orElse(Some("base64")))
            }
        }
      case other =>
        Task.pure(other)
    }
  }

  /** Dereference every block in a content vector, preserving order. */
  def dereferenceAll(sigil: Sigil,
                     chain: List[ParticipantId],
                     content: Vector[ResponseContent]): Task[Vector[ResponseContent]] =
    Task.sequence(content.toList.map(_.dereference(sigil, chain))).map(_.toVector)
}
