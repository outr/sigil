package sigil.pipeline

import rapid.Task
import sigil.{GlobalSpace, Sigil, SpaceId}
import sigil.event.Message
import sigil.signal.Signal
import sigil.tool.model.ResponseContent

/**
 * Pre-persist transform that rewrites oversized [[ResponseContent]]
 * blocks on [[Message]] events to [[ResponseContent.StoredFileReference]]
 * pointers. Keeps the event store lean — large code dumps,
 * diffs, and base64 images live in [[sigil.storage.StorageProvider]];
 * the persisted Message carries only metadata + an `Id[StoredFile]`.
 *
 * Threshold and space-resolver are read from the active [[Sigil]]:
 *   - [[Sigil.inlineContentThreshold]] — bytes; blocks larger than
 *     this are externalized. Default 8 KB; set to `Long.MaxValue` to
 *     disable.
 *   - [[Sigil.externalizationSpace]] — resolver picking the
 *     [[SpaceId]] each rewritten block lands under. Default
 *     [[GlobalSpace]]; apps tune for per-conversation / per-tenant
 *     tenancy.
 *
 * What gets externalized:
 *   - `Code(code, language)` when `code.length > threshold` →
 *     `StoredFileReference(_, "code-${lang}.txt", language, "text/x-${lang}", size)`
 *   - `Diff(diff, filename)` when `diff.length > threshold` →
 *     `StoredFileReference(_, filename ?? "diff.patch", None, "text/x-diff", size)`
 *   - `Image(dataUrl, alt)` when the URL is a `data:` scheme whose
 *     payload exceeds the threshold → `StoredFileReference(_,
 *     "image.${ext}", None, mimeType, size)`. Already-hosted URLs
 *     pass through unchanged.
 *
 * Other variants pass through unchanged. Apps that want to disable
 * the rewrite entirely override `Sigil.inboundTransforms` to drop
 * this transform, OR set
 * `inlineContentThreshold = Long.MaxValue`.
 */
object ContentExternalizationTransform extends InboundTransform {

  override def apply(signal: Signal, self: Sigil): Task[Signal] = signal match {
    case m: Message =>
      externalizeContent(m, self).map(rewritten => m.copy(content = rewritten))
    case other => Task.pure(other)
  }

  private def externalizeContent(m: Message, self: Sigil): Task[Vector[ResponseContent]] = {
    val threshold = self.inlineContentThreshold
    if (threshold == Long.MaxValue) Task.pure(m.content)
    else {
      Task.sequence(m.content.toList.map(rc => externalizeBlock(rc, m, self, threshold))).map(_.toVector)
    }
  }

  private def externalizeBlock(rc: ResponseContent,
                               m: Message,
                               self: Sigil,
                               threshold: Long): Task[ResponseContent] = rc match {
    case ResponseContent.Code(code, language) if code.length.toLong > threshold =>
      val bytes = code.getBytes("UTF-8")
      val title = language.map(l => s"code.$l").getOrElse("code.txt")
      val contentType = language.map(l => s"text/x-$l").getOrElse("text/plain")
      self.externalizationSpace(m).flatMap { space =>
        self.storeBytes(space, bytes, contentType,
                        metadata = Map("kind" -> "externalized-code", "conversationId" -> m.conversationId.value))
          .map { stored =>
            ResponseContent.StoredFileReference(
              fileId = stored._id,
              title = title,
              language = language,
              contentType = contentType,
              size = bytes.length.toLong
            )
          }
      }

    case ResponseContent.Diff(diff, filename) if diff.length.toLong > threshold =>
      val bytes = diff.getBytes("UTF-8")
      val title = filename.getOrElse("diff.patch")
      self.externalizationSpace(m).flatMap { space =>
        self.storeBytes(space, bytes, "text/x-diff",
                        metadata = Map("kind" -> "externalized-diff", "conversationId" -> m.conversationId.value))
          .map { stored =>
            ResponseContent.StoredFileReference(
              fileId = stored._id,
              title = title,
              language = Some("diff"),
              contentType = "text/x-diff",
              size = bytes.length.toLong
            )
          }
      }

    case ResponseContent.Image(url, alt) =>
      val raw = url.toString
      // `data:<mime>;base64,<payload>` — externalize when the payload
      // (post-base64) exceeds the threshold.
      DataUrl.parse(raw) match {
        case Some(parsed) if parsed.bytes.length.toLong > threshold =>
          val title = alt.getOrElse(s"image.${DataUrl.extFor(parsed.contentType)}")
          self.externalizationSpace(m).flatMap { space =>
            self.storeBytes(space, parsed.bytes, parsed.contentType,
                            metadata = Map("kind" -> "externalized-image",
                                           "conversationId" -> m.conversationId.value))
              .map { stored =>
                ResponseContent.StoredFileReference(
                  fileId = stored._id,
                  title = title,
                  language = None,
                  contentType = parsed.contentType,
                  size = parsed.bytes.length.toLong
                )
              }
          }
        case _ => Task.pure(rc)
      }

    case other => Task.pure(other)
  }

  private object DataUrl {
    case class Parsed(contentType: String, bytes: Array[Byte])

    def parse(url: String): Option[Parsed] =
      if (!url.startsWith("data:")) None
      else {
        val rest = url.substring(5)
        val commaIdx = rest.indexOf(',')
        if (commaIdx < 0) None
        else {
          val header = rest.substring(0, commaIdx)
          val payload = rest.substring(commaIdx + 1)
          val isBase64 = header.endsWith(";base64")
          val contentType =
            if (isBase64) header.dropRight(";base64".length).takeWhile(_ != ';')
            else header.takeWhile(_ != ';')
          val effectiveContentType = if (contentType.isEmpty) "application/octet-stream" else contentType
          try {
            val bytes =
              if (isBase64) java.util.Base64.getDecoder.decode(payload)
              else java.net.URLDecoder.decode(payload, "UTF-8").getBytes("UTF-8")
            Some(Parsed(effectiveContentType, bytes))
          } catch { case _: Throwable => None }
        }
      }

    def extFor(mime: String): String = mime.toLowerCase match {
      case "image/png"  => "png"
      case "image/jpeg" => "jpg"
      case "image/gif"  => "gif"
      case "image/webp" => "webp"
      case "image/svg+xml" => "svg"
      case _            => "bin"
    }
  }
}
