package sigil.tool.util

import fabric.*
import fabric.io.JsonFormatter
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Sigil #389 — `lookup` windows the dominant text field of a large record
 * so it pages in chunks instead of being stubbed by the generic overflow
 * path (which left a 501 KB saved page unreadable: "Narrow your inputs",
 * with nothing to narrow). Lives in `sigil.tool.util` to reach the
 * package-private `LookupTool.windowPayload`.
 */
class LookupWindowingSpec extends AnyWordSpec with Matchers {

  private val threshold = 4096L

  /** A payload shaped like an Information record: a big `content` field
    * plus small metadata. */
  private def payload(contentLen: Int): Obj =
    obj(
      "id"          -> str("OJGY0Qur"),
      "contentType" -> str("text/html"),
      "summary"     -> str("a captured page"),
      "content"     -> str("x" * contentLen)
    )

  private def contentOf(j: Json): String = j("content").asString

  "LookupTool.windowPayload (sigil #389)" should {

    "leave a small record untouched (no chunk)" in {
      val p = payload(100)
      val (out, chunk) = LookupTool.windowPayload(p, None, threshold)
      out shouldBe p
      chunk shouldBe None
    }

    "window the content of a large record into a first chunk under the cap" in {
      val total = 50_000
      val (out, chunk) = LookupTool.windowPayload(payload(total), None, threshold)
      chunk shouldBe defined
      val c = chunk.get
      c.field shouldBe "content"
      c.offset shouldBe 0
      c.total shouldBe total
      c.nextOffset shouldBe defined
      c.returned shouldBe contentOf(out).length
      // The windowed result fits under the inline cap (so it ships inline,
      // not stubbed) — the whole point of windowing here.
      JsonFormatter.Compact(out).length.toLong should be <= threshold
      // Non-content metadata survives.
      out("id").asString shouldBe "OJGY0Qur"
    }

    "page the rest of the record across offsets and reassemble exactly" in {
      val full = "abcdefghij" * 6000 // 60k chars
      val p = obj("content" -> str(full))
      val sb = new StringBuilder
      var offset: Option[Int] = None
      var guard = 0
      var done = false
      while (!done && guard < 1000) {
        guard += 1
        val (out, chunk) = LookupTool.windowPayload(p, offset, threshold)
        chunk shouldBe defined
        sb.append(contentOf(out))
        chunk.get.nextOffset match {
          case Some(n) => offset = Some(n)
          case None    => done = true
        }
      }
      done shouldBe true
      sb.toString shouldBe full
    }

    "window the LARGEST string field when several are present" in {
      val p = obj(
        "title"   -> str("short"),
        "content" -> str("y" * 40_000), // the dominant field
        "note"    -> str("also short")
      )
      val (_, chunk) = LookupTool.windowPayload(p, None, threshold)
      chunk.get.field shouldBe "content"
    }

    "return the payload untouched when there is no string field to window" in {
      val p = obj("count" -> num(3), "flag" -> bool(true))
      val (out, chunk) = LookupTool.windowPayload(p, Some(0), threshold)
      out shouldBe p
      chunk shouldBe None
    }

    "signal completion (nextOffset = None) on the final chunk" in {
      val total = 5000
      // First read to discover the chunk size, then jump near the end.
      val (_, first) = LookupTool.windowPayload(payload(total), None, threshold)
      val nearEnd = total - 10
      val (out, chunk) = LookupTool.windowPayload(payload(total), Some(nearEnd), threshold)
      chunk shouldBe defined
      chunk.get.nextOffset shouldBe None
      contentOf(out).length shouldBe 10
      first.get.nextOffset shouldBe defined // sanity: a mid read DID have a next
    }
  }
}
