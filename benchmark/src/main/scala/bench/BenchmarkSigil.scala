package bench

import fabric.rw.*
import lightdb.id.Id
import rapid.Task
import sigil.{Sigil, TurnContext}
import sigil.conversation.{ConversationView, TurnInput}
import sigil.SpaceId
import sigil.db.Model
import sigil.embedding.EmbeddingProvider
import sigil.information.Information
import sigil.participant.{Participant, ParticipantId}
import sigil.provider.Provider
import sigil.provider.anthropic.AnthropicProvider
import sigil.provider.llamacpp.LlamaCppProvider
import sigil.provider.openai.OpenAIProvider
import sigil.signal.Signal
import sigil.tool.{InMemoryToolFinder, ToolFinder}
import sigil.vector.VectorIndex
import spice.net.{TLDValidation, URL, url}

/**
 * Minimal concrete [[Sigil]] for benchmark use — satisfies the
 * abstract surface with no-op defaults so the benchmark harness
 * can drive `ConsultTool.invoke` (required by the LLM reranker)
 * without wiring an app's full conversation surface.
 *
 * Only the embedding + vector + provider plumbing is real; every
 * other hook returns a benign default. Safe because benchmarks
 * never publish signals, curate views, or run the orchestrator —
 * they just issue one-shot LLM calls (rerank) and search the
 * vector index.
 */
case class BenchmarkSigil(override val embeddingProvider: EmbeddingProvider,
                          override val vectorIndex: VectorIndex,
                          providerFactory: Id[Model] => Task[Provider]) extends Sigil {

  override type DB = sigil.db.DefaultSigilDB
  override protected def buildDB(directory: Option[java.nio.file.Path],
                                  storeManager: lightdb.store.CollectionManager,
                                  appUpgrades: List[lightdb.upgrade.DatabaseUpgrade]): DB =
    new sigil.db.DefaultSigilDB(directory, storeManager, appUpgrades)

  override protected def signalRegistrations: List[RW[? <: Signal]] = Nil
  override protected def participantIds: List[RW[? <: ParticipantId]] = Nil
  override protected def spaceIds: List[RW[? <: SpaceId]] = Nil
  override protected def participants: List[RW[? <: Participant]] = Nil

  override val findTools: ToolFinder = InMemoryToolFinder(Nil)

  override def curate(view: ConversationView,
                      modelId: Id[Model],
                      chain: List[ParticipantId]): Task[TurnInput] =
    Task.pure(TurnInput(view))

  override def getInformation(id: Id[Information]): Task[Option[Information]] = Task.pure(None)
  override def putInformation(information: Information): Task[Unit] = Task.unit
  override def compressionMemorySpace(conversationId: Id[sigil.conversation.Conversation]): Task[Option[SpaceId]] =
    Task.pure(None)

  override def wireInterceptor: spice.http.client.intercept.Interceptor =
    spice.http.client.intercept.Interceptor.empty

  override def providerFor(modelId: Id[Model], chain: List[ParticipantId]): Task[Provider] =
    providerFactory(modelId)
}

object BenchmarkSigil {

  /** Construct a BenchmarkSigil with an OpenAI-backed provider. Used
    * by the rerank path — ConsultTool.invoke routes through
    * `providerFor`, which lands an OpenAIProvider for any model id. */
  def withOpenAI(embeddingProvider: EmbeddingProvider,
                 vectorIndex: VectorIndex,
                 openaiApiKey: String,
                 openaiBaseUrl: URL = url"https://api.openai.com"): BenchmarkSigil = {
    // Late-bind the `sigilRef` reference — BenchmarkSigil's this
    // pointer needs to be live before we hand it to the provider.
    lazy val self: BenchmarkSigil = BenchmarkSigil(
      embeddingProvider = embeddingProvider,
      vectorIndex = vectorIndex,
      providerFactory = _ => Task.pure(OpenAIProvider(
        apiKey = openaiApiKey,
        sigilRef = self,
        baseUrl = openaiBaseUrl
      ))
    )
    self
  }

  def openaiApiKeyFromEnv: String = Option(System.getenv("OPENAI_API_KEY")).filter(_.nonEmpty).getOrElse {
    System.err.println("ERROR: OPENAI_API_KEY required for --rerank")
    sys.exit(1)
  }

  def openaiBaseUrlFromEnv: URL = Option(System.getenv("OPENAI_BASE_URL"))
    .filter(_.nonEmpty)
    .flatMap(s => URL.get(s, tldValidation = TLDValidation.Off).toOption)
    .getOrElse(url"https://api.openai.com")

  /**
   * Multi-provider variant: dispatches on the model id's prefix
   * (`openai/…` / `anthropic/…` / `llamacpp/…`) so tool-use
   * benchmarks can exercise sigil against any registered provider
   * without a new factory per model tier.
   *
   * `OPENAI_API_KEY` is required only when an `openai/…` model is
   * actually requested; same for `ANTHROPIC_API_KEY`. `LLAMACPP_HOST`
   * (default `https://llama.voidcraft.ai`) routes llama.cpp calls.
   */
  def multiProvider(embeddingProvider: EmbeddingProvider,
                    vectorIndex: VectorIndex): BenchmarkSigil = {
    lazy val self: BenchmarkSigil = BenchmarkSigil(
      embeddingProvider = embeddingProvider,
      vectorIndex = vectorIndex,
      providerFactory = id => {
        val idStr = id.value
        val slashIdx = idStr.indexOf('/')
        val prefix = if (slashIdx >= 0) idStr.take(slashIdx) else ""
        prefix match {
          case "openai" =>
            Task.pure(OpenAIProvider(
              apiKey = openaiApiKeyFromEnv,
              sigilRef = self,
              baseUrl = openaiBaseUrlFromEnv
            ))
          case "anthropic" =>
            val key = Option(System.getenv("ANTHROPIC_API_KEY")).filter(_.nonEmpty).getOrElse {
              System.err.println("ERROR: ANTHROPIC_API_KEY not set (required for anthropic/* models)")
              sys.exit(1)
            }
            Task.pure(AnthropicProvider(
              apiKey = key,
              sigilRef = self
            ))
          case "llamacpp" =>
            val host = Option(System.getenv("LLAMACPP_HOST"))
              .filter(_.nonEmpty)
              .flatMap(s => URL.get(s, tldValidation = TLDValidation.Off).toOption)
              .getOrElse(url"https://llama.voidcraft.ai")
            Task.pure(LlamaCppProvider(
              url = host,
              models = Nil,
              sigilRef = self
            ))
          case other =>
            Task.error(new IllegalArgumentException(s"Unknown model provider prefix '$other' in model id '$idStr' (expected openai/..., anthropic/..., or llamacpp/...)"))
        }
      }
    )
    self
  }
}
