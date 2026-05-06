package spec

import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.SpaceId
import sigil.db.{Model, ModelArchitecture, ModelLinks, ModelPricing, ModelTopProvider}
import sigil.provider.{ModelCandidate, ProviderStrategy, SummarizationWork}

/**
 * Coverage for sigil bug #41 — `routedModelFor` must skip candidates
 * whose `Model.contextLength` can't accommodate the estimated request
 * size, so a cost-aware chain like `[small (32K), big (200K)]` does
 * the right thing automatically: small input → small model; oversized
 * input → fall through to the larger candidate.
 */
class RoutedModelSizeAwareSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers with BeforeAndAfterAll {
  TestSigil.initFor(getClass.getSimpleName)

  private val smallModelId: Id[Model] = Id[Model]("test/size-aware-small")
  private val bigModelId: Id[Model]   = Id[Model]("test/size-aware-big")

  private def synthModel(id: Id[Model], slug: String, contextLength: Long): Model = Model(
    canonicalSlug = slug,
    huggingFaceId = "",
    name = slug,
    description = s"Synthetic test model for bug #41 ($slug)",
    contextLength = contextLength,
    architecture = ModelArchitecture(
      modality = "text->text",
      inputModalities = List("text"),
      outputModalities = List("text"),
      tokenizer = "None",
      instructType = None
    ),
    pricing = ModelPricing(prompt = BigDecimal(0), completion = BigDecimal(0), webSearch = None, inputCacheRead = None),
    topProvider = ModelTopProvider(contextLength = Some(contextLength), maxCompletionTokens = None, isModerated = false),
    perRequestLimits = None,
    supportedParameters = Set.empty,
    knowledgeCutoff = None,
    expirationDate = None,
    links = ModelLinks(details = ""),
    created = Timestamp(),
    _id = id
  )

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    TestSigil.cache.merge(List(
      synthModel(smallModelId, "test/size-aware-small", 32_000L),
      synthModel(bigModelId, "test/size-aware-big", 200_000L)
    )).sync()
  }

  // Custom Sigil hook: per-test override of `resolveProviderStrategy`
  // wired so the test space carries a SummarizationWork chain
  // [small, big]. Without this, framework default has no strategy
  // and `routedModelFor` returns the fallback unconditionally.
  private def withCostAwareSummarization[T](body: => Task[T]): Task[T] = {
    TestSigil.setAccessibleSpaces(_ => Task.pure(Set(TestSpace)))
    val strategy: ProviderStrategy = ProviderStrategy.routed(
      default = List(ModelCandidate(smallModelId)),
      routes = Map(SummarizationWork -> List(
        ModelCandidate(smallModelId),
        ModelCandidate(bigModelId)
      ))
    )
    TestSigil.setResolveProviderStrategy(_ => Task.pure(Some(strategy)))
    body.guarantee(Task {
      TestSigil.reset()
    })
  }

  "Sigil.routedModelFor" should {

    "pick the head candidate when the input fits its context window" in {
      withCostAwareSummarization {
        TestSigil.routedModelFor(
          SummarizationWork,
          chain = List(TestUser, TestAgent),
          fallback = bigModelId,
          estimatedInputTokens = Some(5_000L) // well under 32K
        ).map { picked =>
          picked shouldBe smallModelId
        }
      }
    }

    "skip the small candidate and pick the big one when the input exceeds the small's window" in {
      withCostAwareSummarization {
        TestSigil.routedModelFor(
          SummarizationWork,
          chain = List(TestUser, TestAgent),
          fallback = bigModelId,
          estimatedInputTokens = Some(50_000L) // over 32K, under 200K
        ).map { picked =>
          picked shouldBe bigModelId
        }
      }
    }

    "fall back to `fallback` when every candidate's window is too small" in {
      withCostAwareSummarization {
        TestSigil.routedModelFor(
          SummarizationWork,
          chain = List(TestUser, TestAgent),
          fallback = bigModelId,
          estimatedInputTokens = Some(500_000L) // over both windows
        ).map { picked =>
          picked shouldBe bigModelId
        }
      }
    }

    "pick the head candidate when no estimatedInputTokens is supplied (legacy behavior)" in {
      withCostAwareSummarization {
        TestSigil.routedModelFor(
          SummarizationWork,
          chain = List(TestUser, TestAgent),
          fallback = bigModelId,
          estimatedInputTokens = None
        ).map { picked =>
          picked shouldBe smallModelId
        }
      }
    }

    "treat candidates with unknown contextLength as keep (don't filter)" in {
      val unknownModelId: Id[Model] = Id[Model]("test/size-aware-unknown")
      // Don't add to TestSigil.cache — `cache.find` will return None.
      TestSigil.setAccessibleSpaces(_ => Task.pure(Set(TestSpace)))
      val strategy: ProviderStrategy = ProviderStrategy.routed(
        default = List(ModelCandidate(unknownModelId)),
        routes = Map(SummarizationWork -> List(
          ModelCandidate(unknownModelId),
          ModelCandidate(bigModelId)
        ))
      )
      TestSigil.setResolveProviderStrategy(_ => Task.pure(Some(strategy)))
      TestSigil.routedModelFor(
        SummarizationWork,
        chain = List(TestUser, TestAgent),
        fallback = bigModelId,
        estimatedInputTokens = Some(50_000L)
      ).map { picked =>
        picked shouldBe unknownModelId
      }.guarantee(Task { TestSigil.reset() })
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
