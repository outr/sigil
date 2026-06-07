package spec

import java.util.concurrent.atomic.AtomicReference
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.db.{Model, ModelArchitecture, ModelLinks, ModelPricing, ModelTopProvider}
import sigil.{GlobalSpace, Sigil}
import sigil.provider.{ConversationMode, ModelResolver, Provider, ProviderModel}
import sigil.tool.{DiscoveryRequest, Tool}
import sigil.tool.fs.{GlobTool, GrepTool, LocalFileSystemContext}
import sigil.tool.util.{SearchConversationTool, SemanticSearchTool}

/**
 * Poisoned-context scenarios that keep surfacing as "Kimi degenerated" but
 * are really the framework feeding the model a misleading context.
 *
 * Scenario 1 (Sage wire log entry 179, the `search_conversation` runaway):
 * the user asked to remove bug references in code. The agent issued
 * `find_capability("grep search find text pattern match")` → got `grep`,
 * but the SAME discovery surfaced `search_conversation` and
 * `semantic_search` — they keyword-match the generic terms "search" / "find".
 * Those landed in the prompt's "Suggested tools", and the model (steered off
 * the already-repeated `grep`) latched onto `search_conversation` and spammed
 * it 63× with empty args. The poison is that discovery surfaces low-relevance
 * tools at all; a code-search query must not advertise a conversation-search
 * tool as a peer of `grep`.
 *
 * This drives Sigil's real discovery (`findCapabilities`) over a real tool
 * catalog — it tests how Sigil maintains the context, not a hand-built prompt.
 */
class CloudflareKimiContextSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  KimiContextSigil.initFor(getClass.getSimpleName)

  "find_capability discovery for a filesystem code-search task" should {
    "surface grep, NOT conversation/semantic-search tools that only match generic 'search'/'find'" in {
      val request = DiscoveryRequest(
        keywords     = "grep search find text pattern match",
        chain        = List(TestUser, TestAgent),
        mode         = ConversationMode,
        callerSpaces = Set(GlobalSpace)
      )
      KimiContextSigil.findCapabilities(request).map { matches =>
        val names = matches.map(_.name)
        withClue(s"surfaced: ${names.mkString(", ")}: ") {
          names should contain("grep")
          names should not contain "search_conversation"
          names should not contain "semantic_search"
        }
      }
    }
  }

  "tear down" should {
    "dispose KimiContextSigil" in KimiContextSigil.shutdown.map(_ => succeed)
  }
}

/** Minimal Sigil whose catalog includes the filesystem + search tools the
  * Sage scenario exercised, so `findCapabilities` ranks/surfaces them for
  * real. No provider — discovery never calls the model. */
object KimiContextSigil extends Sigil {
  override type DB = sigil.db.DefaultSigilDB
  override protected def buildDB(directory: Option[java.nio.file.Path],
                                 storeManager: lightdb.store.CollectionManager,
                                 appUpgrades: List[lightdb.upgrade.DatabaseUpgrade]): DB =
    new sigil.db.DefaultSigilDB(directory, storeManager, appUpgrades)

  override def testMode: Boolean = true
  override def loadOpenRouterModels: Boolean = false

  override protected def participantIds: List[fabric.rw.RW[? <: sigil.participant.ParticipantId]] =
    List(fabric.rw.RW.static(TestUser), fabric.rw.RW.static(TestAgent))

  // No provider by default — the deterministic discovery spec never
  // calls the model. The live harness ([[CloudflareKimiLiveContextSpec]])
  // wires a real CloudflareProvider via `setProvider` and registers the
  // Kimi model via `registerModel`, so the agent loop resolves it.
  private val providerRef = new AtomicReference[Option[() => Provider]](None)
  def setProvider(p: => Provider): Unit = providerRef.set(Some(() => p))

  override def modelResolver: ModelResolver = new ModelResolver {
    def resolve(modelId: Id[Model]): Option[ProviderModel] =
      providerRef.get().flatMap(p => cache.find(modelId).map(ProviderModel(p(), _)))
  }

  /** Register a Model record so the agent loop can resolve the live
    * Kimi id against the cache. Idempotent. */
  def registerModel(modelId: Id[Model]): Model =
    cache.find(modelId).getOrElse {
      val now = lightdb.time.Timestamp()
      val m = Model(
        canonicalSlug       = modelId.value,
        huggingFaceId       = "",
        name                = modelId.value,
        description         = s"Live model fixture for ${modelId.value}.",
        contextLength       = 131072L,
        architecture        = ModelArchitecture(
          modality         = "text->text",
          inputModalities  = List("text"),
          outputModalities = List("text"),
          tokenizer        = "GPT",
          instructType     = None
        ),
        pricing             = ModelPricing(prompt = BigDecimal(0), completion = BigDecimal(0), webSearch = None, inputCacheRead = None),
        topProvider         = ModelTopProvider(contextLength = Some(131072L), maxCompletionTokens = Some(16000L), isModerated = false),
        perRequestLimits    = None,
        supportedParameters = Set("temperature", "max_tokens", "top_p", "tools", "tool_choice"),
        knowledgeCutoff     = None,
        expirationDate      = None,
        links               = ModelLinks(details = ""),
        created             = now,
        _id                 = modelId
      )
      cache.merge(List(m)).sync()
      m
    }

  private val fs = LocalFileSystemContext(basePath = None)

  override def staticTools: List[Tool] =
    super.staticTools ++ List(
      new GrepTool(fs),
      new GlobTool(fs),
      SearchConversationTool,
      SemanticSearchTool
    )

  def initFor(testClassName: String): Unit = {
    val name = testClassName.replace("$", "")
    val dbPath = java.nio.file.Path.of("db", "test", name)
    deleteRecursive(dbPath)
    profig.Profig.merge(fabric.obj("sigil" -> fabric.obj("dbPath" -> fabric.str(dbPath.toString))))
    instance.sync()
    ()
  }

  private def deleteRecursive(path: java.nio.file.Path): Unit =
    if (java.nio.file.Files.exists(path)) {
      import scala.jdk.CollectionConverters.*
      if (java.nio.file.Files.isDirectory(path))
        java.nio.file.Files.list(path).iterator().asScala.foreach(deleteRecursive)
      java.nio.file.Files.delete(path)
    }
}
