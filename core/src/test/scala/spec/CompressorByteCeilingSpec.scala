package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.conversation.{ContextFrame, TopicEntry}
import sigil.conversation.compression.SummaryOnlyCompressor
import sigil.event.{Event, MessageVisibility}
import sigil.tokenize.HeuristicTokenizer

/**
 * Regression for sigil bug #143 — the chunker only considered the
 * token budget. A run with 48K placeholder-bearing frames produced
 * an 18 MB transcript that the framework happily handed to a
 * frontier model with a 200K-token context, then OpenAI rejected
 * with HTTP 400 "string too long. Expected a string with maximum
 * length 10485760".
 *
 * Fix: `chunkByTokensAndBytes` splits whenever EITHER the token
 * budget OR the byte ceiling would be exceeded. Both compressors
 * gain a `maxChunkBytes` knob (default 8 MB) that the path-decision
 * code consults alongside the token check.
 */
class CompressorByteCeilingSpec extends AnyWordSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val ctx: (Option[sigil.provider.Mode], Option[TopicEntry]) = (None, None)

  /**
   * Renderer that emits each frame's content verbatim (one line).
   */
  private val verbatim: SummaryOnlyCompressor.Renderer =
    (frames, _, _) =>
      frames.iterator.map {
        case t: ContextFrame.Text => t.content
        case tr: ContextFrame.ToolResult => tr.content
        case _ => ""
      }.mkString("\n")

  private def textFrame(content: String, idSuffix: String): ContextFrame =
    ContextFrame.Text(content, TestUser, Id[Event](s"e-$idSuffix"), MessageVisibility.All)

  "SummaryOnlyCompressor.chunkByTokensAndBytes" should {

    "split when byte ceiling would be exceeded even if token budget would not be" in {
      // 4 frames × 100 KB each = 400 KB total. Token budget is
      // ample (the frames are ~25K tokens each at 4 chars/token =
      // 100K tokens total). Byte ceiling 200 KB forces 2-frame
      // chunks regardless of the token headroom.
      val payload = "x" * 100_000
      val frames = (1 to 4).toVector.map(i => textFrame(payload, i.toString))
      val chunks = SummaryOnlyCompressor.chunkByTokensAndBytes(
        frames,
        ctx,
        verbatim,
        HeuristicTokenizer,
        budgetTokens = Long.MaxValue,
        maxBytes = 200_000L
      )
      chunks should have size 2
      chunks.foreach(_.size shouldBe 2)
    }

    "split when token budget would be exceeded even if byte ceiling would not be" in {
      // Same total byte size; tighten the token budget below the
      // accumulated count of two frames to force a split.
      val payload = "x" * 100_000
      val frames = (1 to 4).toVector.map(i => textFrame(payload, i.toString))
      val perFrameTokens = HeuristicTokenizer.count(payload).toLong
      val chunks = SummaryOnlyCompressor.chunkByTokensAndBytes(
        frames,
        ctx,
        verbatim,
        HeuristicTokenizer,
        budgetTokens = perFrameTokens + perFrameTokens - 1L, // 2 frames just barely don't fit
        maxBytes = Long.MaxValue
      )
      chunks should have size 4
      chunks.foreach(_.size shouldBe 1)
    }

    "keep the legacy chunkByTokens delegate working (byte ceiling = MaxValue)" in {
      val payload = "x" * 50_000
      val frames = (1 to 3).toVector.map(i => textFrame(payload, i.toString))
      // chunkByTokens forwards to chunkByTokensAndBytes with byte
      // ceiling = MaxValue, so behaviour is identical to the legacy
      // path.
      val legacy = SummaryOnlyCompressor.chunkByTokens(
        frames,
        ctx,
        verbatim,
        HeuristicTokenizer,
        budgetTokens = Long.MaxValue
      )
      legacy should have size 1
      legacy.head.size shouldBe 3
    }

    "land a single oversized frame alone in its chunk (caller decides)" in {
      // Single frame whose own byte size exceeds the ceiling lands
      // by itself; chunking can't sub-split a single frame. The
      // downstream summarizeOnce will hit the provider's byte cap
      // on this one chunk — refusal handling is the caller's job
      // (see CompressionTooLargeException).
      val huge = "y" * 5_000_000L.toInt
      val chunks = SummaryOnlyCompressor.chunkByTokensAndBytes(
        Vector(textFrame(huge, "huge")),
        ctx,
        verbatim,
        HeuristicTokenizer,
        budgetTokens = Long.MaxValue,
        maxBytes = 1_000_000L
      )
      chunks should have size 1
      chunks.head.size shouldBe 1
    }
  }

  "SummaryOnlyCompressor.DefaultMaxChunkBytes" should {
    "stay under OpenAI's 10 MB per-text-input ceiling" in {
      // 10 MB = 10 * 1024 * 1024 = 10_485_760. We default to 8 MB
      // so an 8 MB chunk + overhead still fits a 10 MB body.
      SummaryOnlyCompressor.DefaultMaxChunkBytes should be < 10L * 1024L * 1024L
    }
  }
}
