package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.provider.openai.OpenAIBatch
import sigil.provider.{OneShotRequest, ProviderRequest}
import sigil.tool.model.ResponseContent
import fabric.io.JsonParser

/**
 * Sigil #299 — OpenAI Batch wire-layer regression. Covers the pure-
 * function paths (JSONL line render, result-line parse) without
 * needing live OpenAI credentials. The HTTP-flow paths (file upload,
 * batch create, polling, download) are exercised against the real
 * service in integration runs gated on `OPENAI_API_KEY`.
 */
class OpenAIBatchSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private def req(text: String): OneShotRequest = OneShotRequest(
    model        = TestSigil.testModel(sigil.db.Model.id("openai", "gpt-4o-mini")),
    systemPrompt = "You are a classifier.",
    userPrompt   = text
  )

  "OpenAIBatch.renderJsonlLine" should {

    "produce a valid JSONL line with the chat-completions shape" in rapid.Task {
      val r = req("Classify this: cats")
      val line = OpenAIBatch.renderJsonlLine(r)
      // Must parse as single-line JSON (no embedded newlines).
      line should not include "\n"
      val json = JsonParser(line)
      json("custom_id").asString shouldBe r.requestId.value
      json("method").asString shouldBe "POST"
      json("url").asString shouldBe "/v1/chat/completions"
      val body = json("body")
      body("model").asString shouldBe "gpt-4o-mini"
      val messages = body("messages").asVector
      messages should have size 2
      messages(0)("role").asString shouldBe "system"
      messages(0)("content").asString shouldBe "You are a classifier."
      messages(1)("role").asString shouldBe "user"
      val userContent = messages(1)("content").asVector
      userContent(0)("type").asString shouldBe "text"
      userContent(0)("text").asString shouldBe "Classify this: cats"
    }

    "render ImageBytes content as a data: image_url block" in rapid.Task {
      val r = OneShotRequest(
        model        = TestSigil.testModel(sigil.db.Model.id("openai", "gpt-4o")),
        systemPrompt = "Describe.",
        userPrompt   = "",
        userContent  = Vector(
          ResponseContent.Text("What's in this image?"),
          ResponseContent.ImageBytes("image/png", "iVBORw0KGgo", Some("test"))
        )
      )
      val line = OpenAIBatch.renderJsonlLine(r)
      val json = JsonParser(line)
      val content = json("body")("messages").asVector(1)("content").asVector
      content should have size 2
      content(0)("type").asString shouldBe "text"
      content(0)("text").asString shouldBe "What's in this image?"
      content(1)("type").asString shouldBe "image_url"
      content(1)("image_url")("url").asString shouldBe "data:image/png;base64,iVBORw0KGgo"
    }
  }

  "OpenAIBatch.parseResultLine" should {

    "decode a successful chat-completions response" in rapid.Task {
      val customId = "ps-success-001"
      val line = """{"id":"batch_req_xyz","custom_id":"""" + customId + """","response":{"status_code":200,"body":{"choices":[{"message":{"role":"assistant","content":"foo bar"}}],"usage":{"prompt_tokens":12,"completion_tokens":3,"total_tokens":15}}},"error":null}"""
      val parsed = OpenAIBatch.parseResultLine(line)
      parsed shouldBe defined
      val r = parsed.get
      r.requestId.value shouldBe customId
      r.content.collectFirst { case t: ResponseContent.Text => t.text } shouldBe Some("foo bar")
      r.usage.map(_.promptTokens) shouldBe Some(12)
      r.usage.map(_.completionTokens) shouldBe Some(3)
      r.error shouldBe None
    }

    "decode a per-line error" in rapid.Task {
      val customId = "ps-error-002"
      val line = """{"id":"batch_req_xyz","custom_id":"""" + customId + """","response":null,"error":{"code":"invalid_request_error","message":"prompt is too long"}}"""
      val parsed = OpenAIBatch.parseResultLine(line)
      parsed shouldBe defined
      val r = parsed.get
      r.requestId.value shouldBe customId
      r.error shouldBe defined
      r.error.get.message shouldBe "prompt is too long"
      r.error.get.code shouldBe Some("invalid_request_error")
      r.content shouldBe empty
    }

    "skip blank lines" in rapid.Task {
      OpenAIBatch.parseResultLine("") shouldBe None
      OpenAIBatch.parseResultLine("   ") shouldBe None
    }

    "skip malformed JSON without throwing" in rapid.Task {
      OpenAIBatch.parseResultLine("{not valid json") shouldBe None
    }
  }

  "OpenAIProvider.batchSupported" should {
    "be true (native batch override is wired)" in rapid.Task {
      val provider = sigil.provider.openai.OpenAIProvider(
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
