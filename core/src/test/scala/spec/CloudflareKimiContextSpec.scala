package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.{GlobalSpace, Sigil}
import sigil.provider.{ConversationMode, ModelResolver, ProviderModel}
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

  override def modelResolver: ModelResolver = new ModelResolver {
    def resolve(modelId: lightdb.id.Id[sigil.db.Model]): Option[ProviderModel] = None
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
