package sigil.cache

import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.controller.OpenRouter
import sigil.db.{Model, ModelArchitecture, ModelDefaultParameters, ModelLinks, ModelPricing, ModelTopProvider}
import sigil.provider.llamacpp.LlamaCppProvider
import spec.TestSigil
import spice.net.*

/**
 * The registry federates independently-maintained slices, so a catalog
 * refresh can only ever evict the catalog's own models.
 *
 * Covers the failure an app hits when it locks an agent to a model no
 * aggregate catalog lists (a local llama.cpp deployment): the model is
 * registered at provider construction, then the background catalog
 * refresh runs on its interval and every subsequent turn fails to
 * resolve the id. The catalog fetch is injected here — these never
 * touch the network.
 */
class FederatedModelRegistrySpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private def fakeModel(provider: String, model: String): Model = Model(
    canonicalSlug = s"$provider/$model",
    huggingFaceId = "",
    name = s"$provider/$model",
    description = "fixture",
    contextLength = 4096,
    architecture = ModelArchitecture(
      modality = "text->text",
      inputModalities = List("text"),
      outputModalities = List("text"),
      tokenizer = "GPT",
      instructType = None
    ),
    pricing = ModelPricing(prompt = 0, completion = 0, webSearch = None, inputCacheRead = None),
    topProvider = ModelTopProvider(contextLength = Some(4096), maxCompletionTokens = Some(2048), isModerated = false),
    perRequestLimits = None,
    supportedParameters = Set.empty,
    defaultParameters = ModelDefaultParameters(),
    knowledgeCutoff = None,
    expirationDate = None,
    links = ModelLinks(details = ""),
    created = Timestamp(0L),
    _id = Model.id(provider, model)
  )

  private def refresh(catalog: List[Model]): Task[Unit] =
    TestSigil.withDB(db => OpenRouter.refreshModels(TestSigil, db, Task.pure(catalog)))

  "A catalog refresh" should {

    "leave a side-loaded model resolvable" in {
      val sideLoaded = fakeModel("llamacpp", "qwen3.5-9b-q4-k-m")
      val catalog = fakeModel("openai", "gpt-refresh-a")
      for {
        _ <- TestSigil.cache.merge(List(sideLoaded))
        _ <- refresh(List(catalog))
      } yield {
        TestSigil.cache.find(sideLoaded._id) shouldBe Some(sideLoaded)
        TestSigil.cache.find(catalog._id) shouldBe Some(catalog)
        succeed
      }
    }

    "evict a catalog model the fresh catalog no longer lists" in {
      val retired = fakeModel("openai", "gpt-retired")
      val current = fakeModel("openai", "gpt-current")
      for {
        _ <- refresh(List(retired))
        firstPass = TestSigil.cache.find(retired._id)
        _ <- refresh(List(current))
      } yield {
        firstPass shouldBe Some(retired)
        TestSigil.cache.find(retired._id) shouldBe None
        TestSigil.cache.find(current._id) shouldBe Some(current)
        succeed
      }
    }
  }

  "The boot seed from the persisted snapshot" should {
    "leave a model that was side-loaded before boot finished resolvable" in {
      val sideLoaded = fakeModel("llamacpp", "gemma-4-26b")
      val snapshot = List(fakeModel("openai", "gpt-snapshot"))
      for {
        _ <- TestSigil.cache.merge(List(sideLoaded))
        _ <- TestSigil.seedCatalogSnapshot(snapshot)
      } yield {
        TestSigil.cache.find(sideLoaded._id) shouldBe Some(sideLoaded)
        TestSigil.cache.find(snapshot.head._id) shouldBe Some(snapshot.head)
        succeed
      }
    }
  }

  "A llama.cpp provider" should {
    "report exactly what the registry resolves, across a catalog refresh" in {
      val served = fakeModel("llamacpp", "provider-served")
      // Unreachable port: construction falls back to single-slot capacity
      // without a live server, which is all this needs.
      val provider = LlamaCppProvider(url"http://127.0.0.1:1", List(served), TestSigil)
      for {
        _ <- refresh(List(fakeModel("openai", "gpt-refresh-b")))
      } yield {
        provider.models.map(_._id).toSet should contain(served._id)
        TestSigil.cache.find(served._id) shouldBe Some(served)
        provider.models.map(_._id).toSet shouldBe
          TestSigil.cache.find(provider = Some("llamacpp")).map(_._id).toSet
        succeed
      }
    }

    "swap its own slice when the backend reloads, without touching other sources" in {
      val loadedFirst = fakeModel("llamacpp", "reload-before")
      val loadedAfter = fakeModel("llamacpp", "reload-after")
      val catalog = fakeModel("openai", "gpt-reload")
      val provider = LlamaCppProvider(url"http://127.0.0.2:1", List(loadedFirst), TestSigil)
      for {
        _ <- refresh(List(catalog))
        _ <- provider.modelSource.set(List(loadedAfter))
      } yield {
        TestSigil.cache.find(loadedFirst._id) shouldBe None
        TestSigil.cache.find(loadedAfter._id) shouldBe Some(loadedAfter)
        TestSigil.cache.find(catalog._id) shouldBe Some(catalog)
        succeed
      }
    }
  }

  "Source precedence" should {

    "keep the catalog authoritative for an id a bare merge also carries" in {
      val merged = fakeModel("openai", "gpt-precedence").copy(description = "side-loaded")
      val fromCatalog = fakeModel("openai", "gpt-precedence").copy(description = "catalog")
      for {
        _ <- TestSigil.cache.merge(List(merged))
        _ <- refresh(List(fromCatalog))
      } yield {
        TestSigil.cache.find(fromCatalog._id).map(_.description) shouldBe Some("catalog")
        succeed
      }
    }

    "resolve a colliding id in favor of the later-registered source" in {
      val registry = new ModelRegistry
      val early = new MutableModelSource("early")
      val late = new MutableModelSource("late")
      val fromEarly = fakeModel("vendor", "shared").copy(description = "early")
      val fromLate = fakeModel("vendor", "shared").copy(description = "late")
      for {
        _ <- registry.register(early)
        _ <- registry.register(late)
        _ <- early.set(List(fromEarly))
        _ <- late.set(List(fromLate))
      } yield {
        registry.find(fromLate._id).map(_.description) shouldBe Some("late")
        registry.all should have size 1
        succeed
      }
    }

    "restore the earlier source's record when the later source releases the id" in {
      val registry = new ModelRegistry
      val early = new MutableModelSource("early")
      val late = new MutableModelSource("late")
      val fromEarly = fakeModel("vendor", "shared").copy(description = "early")
      val fromLate = fakeModel("vendor", "shared").copy(description = "late")
      for {
        _ <- registry.register(early)
        _ <- registry.register(late)
        _ <- early.set(List(fromEarly))
        _ <- late.set(List(fromLate))
        _ <- late.set(Nil)
      } yield {
        registry.find(fromEarly._id).map(_.description) shouldBe Some("early")
        succeed
      }
    }

    "keep registration position when a source re-registers under the same name" in {
      val registry = new ModelRegistry
      val early = new MutableModelSource("early")
      val late = new MutableModelSource("late")
      val replacement = new MutableModelSource("early")
      val fromLate = fakeModel("vendor", "shared").copy(description = "late")
      val fromReplacement = fakeModel("vendor", "shared").copy(description = "replacement")
      for {
        _ <- registry.register(early)
        _ <- registry.register(late)
        _ <- late.set(List(fromLate))
        _ <- replacement.set(List(fromReplacement))
        _ <- registry.register(replacement)
      } yield {
        registry.sources.map(_.name) shouldBe List(
          ModelRegistry.AppSourceName,
          ModelRegistry.CatalogSourceName,
          "early",
          "late"
        )
        registry.find(fromLate._id).map(_.description) shouldBe Some("late")
        succeed
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
