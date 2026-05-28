package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.provider.google.GoogleBatch
import sigil.provider.{OneShotRequest, OneShotResponse}
import sigil.tool.model.ResponseContent
import fabric.io.JsonParser

/**
 * Sigil #299 — Gemini Batch wire-layer regression. Covers the pure-
 * function paths (request-entry render, response-entry parse)
 * without needing a live GOOGLE_API_KEY.
 */
class GoogleBatchSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private def req(text: String, maxTokens: Int = 256): OneShotRequest = OneShotRequest(
    model              = TestSigil.testModel(sigil.db.Model.id("google", "gemini-2.5-flash")),
    systemPrompt       = "You are a classifier.",
    userPrompt         = text,
    generationSettings = sigil.provider.GenerationSettings(
      outputTokenCap = sigil.provider.OutputTokenCap.Below(maxTokens)
    )
  )

  "GoogleBatch.renderRequestEntry" should {

    "produce a Gemini inlinedRequests entry with id + systemInstruction + contents" in rapid.Task {
      val r = req("Classify this: birds", maxTokens = 64)
      val json = GoogleBatch.renderRequestEntry(r)
      json("id").asString shouldBe r.requestId.value
      val request = json("request")
      // systemInstruction nested per Gemini's content shape.
      request("systemInstruction")("parts").asVector(0)("text").asString shouldBe "You are a classifier."
      val contents = request("contents").asVector
      contents should have size 1
      contents(0)("role").asString shouldBe "user"
      contents(0)("parts").asVector(0)("text").asString shouldBe "Classify this: birds"
      request("generationConfig")("maxOutputTokens").asInt shouldBe 64
    }

    "render ImageBytes as inlineData part with mimeType" in rapid.Task {
      val r = OneShotRequest(
        model        = TestSigil.testModel(sigil.db.Model.id("google", "gemini-2.5-pro")),
        systemPrompt = "Describe.",
        userPrompt   = "",
        userContent  = Vector(
          ResponseContent.Text("What's this?"),
          ResponseContent.ImageBytes("image/png", "iVBORw0KGgo")
        )
      )
      val json = GoogleBatch.renderRequestEntry(r)
      val parts = json("request")("contents").asVector(0)("parts").asVector
      parts should have size 2
      parts(0)("text").asString shouldBe "What's this?"
      val inlineData = parts(1)("inlineData")
      inlineData("mimeType").asString shouldBe "image/png"
      inlineData("data").asString shouldBe "iVBORw0KGgo"
    }

    "omit systemInstruction when systemPrompt is empty" in rapid.Task {
      val r = OneShotRequest(
        model        = TestSigil.testModel(sigil.db.Model.id("google", "gemini-2.5-flash")),
        systemPrompt = "",
        userPrompt   = "no system"
      )
      val json = GoogleBatch.renderRequestEntry(r)
      json("request").get("systemInstruction") shouldBe None
    }
  }

  "GoogleBatch.parseResponseEntry" should {

    "decode a successful response entry with content + usage" in rapid.Task {
      val customId = "gb-success-001"
      val json = JsonParser(
        """{"id":"""" + customId + """","response":{"candidates":[{"content":{"parts":[{"text":"foo bar"}]}}],"usageMetadata":{"promptTokenCount":12,"candidatesTokenCount":3,"totalTokenCount":15}}}"""
      )
      val parsed = GoogleBatch.parseResponseEntry(json)
      parsed shouldBe defined
      val r = parsed.get
      r.requestId.value shouldBe customId
      r.content.collectFirst { case t: ResponseContent.Text => t.text } shouldBe Some("foo bar")
      r.usage.map(_.promptTokens) shouldBe Some(12)
      r.usage.map(_.completionTokens) shouldBe Some(3)
      r.usage.map(_.totalTokens) shouldBe Some(15)
      r.error shouldBe None
    }

    "decode an error entry" in rapid.Task {
      val customId = "gb-error-002"
      val json = JsonParser(
        """{"id":"""" + customId + """","error":{"code":"INVALID_ARGUMENT","message":"input too long"}}"""
      )
      val parsed = GoogleBatch.parseResponseEntry(json)
      parsed shouldBe defined
      val r = parsed.get
      r.error shouldBe defined
      r.error.get.message shouldBe "input too long"
      r.error.get.code shouldBe Some("INVALID_ARGUMENT")
    }
  }

  "GoogleProvider.batchSupported" should {
    "be true (native batch override is wired)" in rapid.Task {
      val provider = sigil.provider.google.GoogleProvider(
        apiKey   = "test-key",
        sigilRef = TestSigil
      )
      provider.batchSupported shouldBe true
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
