package spec

import fabric.io.JsonParser
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import fabric.str
import sigil.db.Model
import sigil.provider.cloudflare.{Cloudflare, CloudflareProvider}
import sigil.provider.{CallId => _, _}
import sigil.tool.core.RespondTool
import sigil.tool.ToolRoster

/**
 * Sigil #333 — the Cloudflare chat call must send the *bare* Workers AI
 * model name (`@cf/moonshotai/kimi-k2.6`) on the wire, NOT the
 * Sigil-namespaced id (`cloudflare/@cf/…`), which Cloudflare rejects with
 * a 400 "No such model". This drives the real provider's request builder
 * and inspects the serialized `model` field.
 */
class CloudflareWireModelSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  /** The exact Model record #331's catalog parse produces for Kimi —
    * `_id = cloudflare/@cf/moonshotai/kimi-k2.6`, `name = @cf/…`. */
  private val kimi: Model = Cloudflare.toModel(
    Cloudflare.Entry(
      id         = "uuid-kimi",
      name       = "@cf/moonshotai/kimi-k2.6",
      properties = List(Cloudflare.Property("context_window", str("131072")))
    )
  )

  private def wireModelField(model: Model): rapid.Task[String] =
    CloudflareProvider.create(TestSigil, apiToken = "test", accountId = "acct").flatMap { provider =>
      val call = ProviderCall(
        model              = model,
        system             = "s",
        messages           = Vector(ProviderMessage.User(Vector(MessageContent.Text("hi")))),
        roster = ToolRoster(Vector(RespondTool)),
        builtInTools       = Set.empty,
        toolChoice         = ToolChoice.Required,
        generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0))
      )
      provider.httpRequestFor(call).map { req =>
        val body = req.content match {
          case Some(c: spice.http.content.StringContent) => c.value
          case other => fail(s"expected StringContent body, got $other")
        }
        JsonParser(body).get("model").map(_.asString).getOrElse(fail(s"no model field in $body"))
      }
    }

  "CloudflareProvider.httpRequestFor" should {
    "register the catalog model under the namespaced id (cloudflare/@cf/…)" in rapid.Task {
      kimi._id.value shouldBe "cloudflare/@cf/moonshotai/kimi-k2.6"
    }

    "send the bare @cf/ model name on the wire for a catalog-derived model" in
      wireModelField(kimi).map(_ shouldBe "@cf/moonshotai/kimi-k2.6")

    "strip the namespace for a Model.id-constructed id too" in
      wireModelField(TestSigil.testModel(Model.id("cloudflare", "@cf/moonshotai/kimi-k2.6")))
        .map(_ shouldBe "@cf/moonshotai/kimi-k2.6")
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
