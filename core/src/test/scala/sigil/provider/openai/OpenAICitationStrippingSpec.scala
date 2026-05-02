package sigil.provider.openai

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.provider.{ProviderEvent, ToolCallAccumulator}
import spec.TestSigil
import spice.net.url

/**
 * Regression for bug #50 — OpenAI Responses API web-search responses
 * embed `【cite_…】` placeholder markers inline in `output_text`
 * deltas, paired with `response.output_text.annotation.added` events
 * carrying the actual `url_citation` payload. Sigil used to leak the
 * markers as raw text and drop the URLs entirely.
 *
 * The provider now strips the markers from emitted text and flushes
 * the buffered URL citations as a markdown footer at
 * `response.completed`.
 */
class OpenAICitationStrippingSpec extends AnyWordSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private def runLines(lines: List[String]): Vector[ProviderEvent] = {
    val provider = OpenAIProvider("", TestSigil, url"https://api.openai.com")
    val state    = new provider.StreamState(new ToolCallAccumulator())
    lines.flatMap(line => provider.parseLine(line, state)).toVector
  }

  // SSE wire shape: each event is `data: {json}\n\n`. Splitting by line
  // and prefixing with `data: ` is what the live wire produces; the
  // parser treats blank/comment/other lines as no-ops.
  private def sse(events: List[String]): List[String] =
    events.flatMap(e => List(s"data: $e", ""))

  "OpenAI Responses parser (bug #50)" should {
    "strip 【cite_…】 markers from output_text deltas" in {
      val events = runLines(sse(List(
        """{"type":"response.output_text.delta","delta":"OSHA's threshold is 50 ppm 【cite_turn0view0】 averaged over 8 hours."}""",
        """{"type":"response.output_text.delta","delta":"Multiple sources confirm 【cite_turn1view2_turn3search0】 the same value."}"""
      )))
      val texts = events.collect { case ProviderEvent.ContentBlockDelta(_, t) => t }
      texts.mkString shouldNot include("【")
      texts.mkString shouldNot include("】")
      texts.mkString shouldNot include("cite")
      // Surrounding text survives untouched (citation marker is removed,
      // not replaced with whitespace fixups).
      texts.mkString should include("OSHA's threshold is 50 ppm")
      texts.mkString should include("Multiple sources confirm")
    }

    "strip bare citeturnXviewY markers (bug #51 — no brackets, e.g. 'citeturn1view0turn1view2turn1view3')" in {
      val events = runLines(sse(List(
        """{"type":"response.output_text.delta","delta":"MurCal lists controllers online citeturn1view0 with prices."}""",
        """{"type":"response.output_text.delta","delta":"DSF, Techno Gamma, and Rekarma have product pages citeturn1view1turn1view2turn1view3 too."}""",
        """{"type":"response.output_text.delta","delta":"Search-style markers also leak: citeturn0search0navlist1news2."}"""
      )))
      val text = events.collect { case ProviderEvent.ContentBlockDelta(_, t) => t }.mkString
      text shouldNot include("cite")
      text shouldNot include("turn")
      text should include("MurCal lists controllers online")
      text should include("with prices.")
      text should include("DSF, Techno Gamma, and Rekarma have product pages")
      text should include("Search-style markers also leak:")
    }

    "buffer url_citation annotations and emit a markdown Sources footer at response.completed" in {
      val events = runLines(sse(List(
        """{"type":"response.output_text.delta","delta":"Hello 【cite_turn0view0】 world."}""",
        """{"type":"response.output_text.annotation.added","annotation":{"type":"url_citation","start_index":6,"end_index":24,"url":"https://example.com/a","title":"Example A"}}""",
        """{"type":"response.output_text.annotation.added","annotation":{"type":"url_citation","start_index":30,"end_index":45,"url":"https://example.com/b","title":"Example B"}}""",
        """{"type":"response.completed","response":{"status":"completed"}}"""
      )))
      val text = events.collect { case ProviderEvent.ContentBlockDelta(_, t) => t }.mkString
      text shouldNot include("【")
      text should include("**Sources:**")
      text should include("[Example A](https://example.com/a)")
      text should include("[Example B](https://example.com/b)")

      // Done is the final event and the only one of its kind.
      events.last shouldBe a[ProviderEvent.Done]
    }

    "deduplicate citations by URL when the same source is annotated multiple times" in {
      val events = runLines(sse(List(
        """{"type":"response.output_text.delta","delta":"Sentence A 【cite_turn0view0】."}""",
        """{"type":"response.output_text.delta","delta":" Sentence B 【cite_turn1view0】."}""",
        """{"type":"response.output_text.annotation.added","annotation":{"type":"url_citation","url":"https://example.com/page","title":"Page"}}""",
        """{"type":"response.output_text.annotation.added","annotation":{"type":"url_citation","url":"https://example.com/page","title":"Page"}}""",
        """{"type":"response.completed","response":{"status":"completed"}}"""
      )))
      val footer = events.collect { case ProviderEvent.ContentBlockDelta(_, t) => t }.mkString
      // The "Sources:" section must appear once and the URL once.
      footer.split("\\*\\*Sources:\\*\\*").length shouldBe 2 // text-before-Sources + after
      footer.split("https://example\\.com/page").length shouldBe 2 // appears exactly once
    }

    "emit no Sources footer when no annotations were buffered (web search not used)" in {
      val events = runLines(sse(List(
        """{"type":"response.output_text.delta","delta":"Plain answer with no citations."}""",
        """{"type":"response.completed","response":{"status":"completed"}}"""
      )))
      val text = events.collect { case ProviderEvent.ContentBlockDelta(_, t) => t }.mkString
      text shouldNot include("**Sources:**")
      text should include("Plain answer with no citations.")
    }
  }

  /**
   * Bug #61 — OpenAI Responses reasoning items used to be silently
   * dropped (`case "reasoning" => Vector.empty`). The fix captures
   * each item's id, summary, and encrypted_content into a
   * [[ProviderEvent.ReasoningItem]] that the orchestrator persists
   * as a [[sigil.event.Reasoning]] event for replay on subsequent
   * turns.
   */
  "OpenAI Responses reasoning capture (bug #61)" should {
    "emit a ReasoningItem with the item id and summary text from a streamed reasoning item" in {
      val events = runLines(sse(List(
        // Add the reasoning item — id arrives, no summary yet.
        """{"type":"response.output_item.added","output_index":0,"item":{"id":"rs_test_001","type":"reasoning","summary":[]}}""",
        // Two summary deltas land before the item completes.
        """{"type":"response.reasoning_summary_text.delta","delta":"Step 1: identify the user goal."}""",
        """{"type":"response.reasoning_summary_text.delta","delta":" Step 2: pick a tool."}""",
        // Item completes — emits the ReasoningItem with accumulated text.
        """{"type":"response.output_item.done","output_index":0,"item":{"id":"rs_test_001","type":"reasoning","summary":[]}}"""
      )))
      val items = events.collect { case ri: ProviderEvent.ReasoningItem => ri }
      items should have size 1
      items.head.providerItemId shouldBe "rs_test_001"
      items.head.summary shouldBe List("Step 1: identify the user goal. Step 2: pick a tool.")
      items.head.encryptedContent shouldBe None
    }

    "prefer the settled summary on output_item.done over delta-accumulated text when both exist" in {
      val events = runLines(sse(List(
        """{"type":"response.output_item.added","output_index":0,"item":{"id":"rs_test_002","type":"reasoning","summary":[]}}""",
        // Delta text accumulated…
        """{"type":"response.reasoning_summary_text.delta","delta":"draft draft draft"}""",
        // …but the wire's settled summary is what should win.
        """{"type":"response.output_item.done","output_index":0,"item":{"id":"rs_test_002","type":"reasoning","summary":[{"type":"summary_text","text":"final summary"}]}}"""
      )))
      val items = events.collect { case ri: ProviderEvent.ReasoningItem => ri }
      items should have size 1
      items.head.summary shouldBe List("final summary")
    }

    "preserve encrypted_content (o1 / o3 chain-of-thought blob)" in {
      val events = runLines(sse(List(
        """{"type":"response.output_item.added","output_index":0,"item":{"id":"rs_test_003","type":"reasoning","summary":[],"encrypted_content":"OPAQUE_BASE64_BLOB"}}""",
        """{"type":"response.output_item.done","output_index":0,"item":{"id":"rs_test_003","type":"reasoning","summary":[]}}"""
      )))
      val items = events.collect { case ri: ProviderEvent.ReasoningItem => ri }
      items should have size 1
      items.head.encryptedContent shouldBe Some("OPAQUE_BASE64_BLOB")
    }
  }
}
