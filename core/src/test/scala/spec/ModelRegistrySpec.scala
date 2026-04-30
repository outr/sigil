package spec

import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.cache.ModelRegistry
import sigil.db.{Model, ModelArchitecture, ModelDefaultParameters, ModelLinks, ModelPricing, ModelTopProvider}

import java.nio.file.{Files, Path}

/**
 * Direct coverage for the in-memory model registry. Verifies the
 * synchronous accessors, atomic replace, and the disk-fallback
 * roundtrip used to survive an offline boot.
 */
class ModelRegistrySpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  private def fakeModel(provider: String, model: String): Model = Model(
    canonicalSlug = s"$provider/$model",
    huggingFaceId = "",
    name = s"$provider/$model",
    description = "fake",
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

  "ModelRegistry (no disk)" should {

    "start empty" in Task {
      val reg = new ModelRegistry()
      reg.all shouldBe Nil
      succeed
    }

    "store and resolve by id, by provider, and by exact pair" in {
      val reg = new ModelRegistry()
      val a = fakeModel("openai", "gpt-x")
      val b = fakeModel("openai", "gpt-y")
      val c = fakeModel("anthropic", "claude-z")
      for {
        _ <- reg.replace(List(a, b, c))
      } yield {
        reg.all.size shouldBe 3
        reg.find(a._id) shouldBe Some(a)
        reg.find(provider = Some("openai")).map(_.model).toSet shouldBe Set("gpt-x", "gpt-y")
        reg.find("anthropic", "claude-z") shouldBe Some(c)
        reg.find(provider = Some("missing")) shouldBe Nil
        succeed
      }
    }

    "atomically replace previous contents" in {
      val reg = new ModelRegistry()
      for {
        _ <- reg.replace(List(fakeModel("openai", "first"), fakeModel("openai", "second")))
        _ <- reg.replace(List(fakeModel("openai", "fresh")))
      } yield {
        reg.all.map(_.model) shouldBe List("fresh")
        succeed
      }
    }

    "merge preserves existing entries from other providers (per-provider seeding pattern)" in {
      val reg = new ModelRegistry()
      val openaiA = fakeModel("openai", "gpt-x")
      val openaiB = fakeModel("openai", "gpt-y")
      val llamaA  = fakeModel("llamacpp", "gemma-9b")
      val llamaB  = fakeModel("llamacpp", "qwen-7b")
      for {
        // OpenRouter-style: seed the registry with the OpenAI catalog.
        _ <- reg.replace(List(openaiA, openaiB))
        // LlamaCppProvider construction adds its own catalog without
        // wiping the OpenAI entries — this is the bug #40 fix shape.
        _ <- reg.merge(List(llamaA, llamaB))
      } yield {
        reg.all.map(_._id.value).toSet shouldBe Set(
          openaiA._id.value, openaiB._id.value, llamaA._id.value, llamaB._id.value
        )
        succeed
      }
    }

    "merge overwrites existing entries with the same id" in {
      val reg = new ModelRegistry()
      val original = fakeModel("llamacpp", "gemma").copy(name = "Gemma v1")
      val updated  = fakeModel("llamacpp", "gemma").copy(name = "Gemma v2")
      for {
        _ <- reg.merge(List(original))
        _ <- reg.merge(List(updated))
      } yield {
        reg.find(updated._id).map(_.name) shouldBe Some("Gemma v2")
        reg.all should have size 1
        succeed
      }
    }
  }

  "ModelRegistry (disk fallback)" should {

    "round-trip through the cache file" in {
      val tmp = Files.createTempFile("sigil-modelreg-", ".json")
      Files.deleteIfExists(tmp)
      val source = new ModelRegistry(Some(tmp))
      val restored = new ModelRegistry(Some(tmp))
      val records = List(fakeModel("openai", "gpt-x"), fakeModel("anthropic", "claude-z"))
      for {
        _ <- source.replace(records)
        _ <- restored.loadFromDisk
      } yield {
        Files.exists(tmp) shouldBe true
        restored.all.map(_._id.value).toSet shouldBe records.map(_._id.value).toSet
        succeed
      }
    }

    "no-op on loadFromDisk when the file does not exist" in {
      val tmp = Path.of("/tmp", s"sigil-modelreg-${rapid.Unique()}.json")
      val reg = new ModelRegistry(Some(tmp))
      for {
        _ <- reg.loadFromDisk
      } yield {
        reg.all shouldBe Nil
        succeed
      }
    }
  }
}
