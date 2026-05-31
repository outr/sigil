package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import rapid.{Stream, Task}
import sigil.db.Model
import sigil.provider.{Provider, ProviderCall, ProviderEvent, ProviderModel, ProviderRegistry, ProviderType}
import spice.http.HttpRequest

/**
 * Covers the model→provider resolution that replaced the abstract
 * `Sigil.providerFor`: a single [[Provider]] is a [[sigil.provider.ModelResolver]]
 * (lenient self-resolution), and [[ProviderRegistry]] dispatches by the
 * model id's provider namespace.
 *
 * The dispatch-by-namespace property is what makes the sigil #333 class
 * (a `cloudflare/…` id reaching a provider whose key is something else)
 * impossible: the registry can only hand an id to the member whose
 * `providerKey` equals the id's namespace.
 */
class ProviderResolutionSpec extends AnyWordSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  /** Minimal provider — its only job here is to carry a `providerKey`
    * (derived from `type`) and resolve cached models. */
  private final class StubProvider(pt: ProviderType) extends Provider {
    override def `type`: ProviderType = pt
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def call(input: ProviderCall): Stream[ProviderEvent] = Stream.empty
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("stub"))
  }

  private val cloudflare = new StubProvider(ProviderType.Cloudflare)
  private val anthropic  = new StubProvider(ProviderType.Anthropic)
  private val llamacpp   = new StubProvider(ProviderType.LlamaCpp)

  // Register one model under each provider's namespace.
  private val kimi   = Model.id("cloudflare", "@cf/moonshotai/kimi-k2.6")
  private val claude = Model.id("anthropic", "claude-opus-4-8")
  private val gemma  = Model.id("llamacpp", "gemma-4-26b")
  TestSigil.testModel(kimi); TestSigil.testModel(claude); TestSigil.testModel(gemma)

  private val registry = new ProviderRegistry(List(cloudflare, anthropic, llamacpp))

  "A single Provider as a ModelResolver" should {
    "resolve any cached model to itself (lenient — namespace dispatch is the registry's job)" in {
      cloudflare.resolve(kimi).map(_.provider.providerKey) shouldBe Some("cloudflare")
      // Lenient: a lone provider serves any cached id regardless of namespace.
      anthropic.resolve(kimi).map(_.provider.providerKey) shouldBe Some("anthropic")
    }

    "return None for an id that isn't registered at all" in {
      cloudflare.resolve(Model.id("cloudflare", "not-registered")) shouldBe None
    }
  }

  "ProviderRegistry" should {
    "dispatch each id to the member whose providerKey matches its namespace" in {
      registry.resolve(kimi).map(pm => (pm.provider.providerKey, pm.model._id)) shouldBe
        Some(("cloudflare", kimi))
      registry.resolve(claude).map(_.provider.providerKey) shouldBe Some("anthropic")
      registry.resolve(gemma).map(_.provider.providerKey)  shouldBe Some("llamacpp")
    }

    "never cross-route — a cloudflare/ id only ever reaches the cloudflare provider (#333)" in {
      // The resolved provider's key equals the id's namespace, so the
      // wire `model` field will strip exactly that prefix.
      val pm = registry.resolve(kimi).getOrElse(fail("kimi not resolved"))
      pm.provider.providerKey shouldBe "cloudflare"
    }

    "return None when no member serves the id's namespace" in {
      registry.resolve(Model.id("openai", "gpt-5.5")) shouldBe None
    }

    "return None when the namespace matches but the model isn't registered" in {
      registry.resolve(Model.id("cloudflare", "@cf/unknown/model")) shouldBe None
    }

    "expose its known provider keys for diagnostics" in {
      registry.knownKeys.toSet shouldBe Set("cloudflare", "anthropic", "llamacpp")
    }
  }
}
