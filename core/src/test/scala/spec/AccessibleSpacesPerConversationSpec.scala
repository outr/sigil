package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.{GlobalSpace, Sigil, SpaceId}
import sigil.conversation.Conversation
import sigil.participant.ParticipantId

/**
 * Regression for bug #77 — `accessibleSpaces` takes `conversationId`
 * so apps can scope spaces per-conversation (per-workspace memory
 * pools, per-tenant isolation in multi-tenant apps, per-topic
 * spaces). Same chain, two different conversations, two different
 * accessible-spaces sets.
 *
 * Also verifies the backward-compat bridge: a Sigil that overrides
 * only the single-arg `accessibleSpaces(chain)` keeps working — the
 * default two-arg implementation delegates back, so framework call
 * sites that pass a conversationId observe the same set across all
 * conversations.
 */
class AccessibleSpacesPerConversationSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  /** A workspace-scoped space, per the bug doc's Sage example. */
  case class WorkspaceSpace(workspaceId: String) extends SpaceId {
    override val value: String = s"workspace:$workspaceId"
  }

  /** A Sigil that scopes accessible spaces per conversation: each
    * conversation id maps to a different workspace. */
  private object PerConversationSigil extends Sigil {
    override type DB = sigil.db.DefaultSigilDB
    override protected def buildDB(directory: Option[java.nio.file.Path],
                                    storeManager: lightdb.store.CollectionManager,
                                    appUpgrades: List[lightdb.upgrade.DatabaseUpgrade]): DB =
      new sigil.db.DefaultSigilDB(directory, storeManager, appUpgrades)
    override def providerFor(modelId: Id[sigil.db.Model], chain: List[ParticipantId]): Task[sigil.provider.Provider] =
      Task.error(new RuntimeException("not used"))

    override def accessibleSpaces(chain: List[ParticipantId],
                                  conversationId: Id[Conversation]): Task[Set[SpaceId]] =
      conversationId.value match {
        case "conv-A" => Task.pure(Set[SpaceId](GlobalSpace, WorkspaceSpace("repo-a")))
        case "conv-B" => Task.pure(Set[SpaceId](GlobalSpace, WorkspaceSpace("repo-b")))
        case _        => Task.pure(Set[SpaceId](GlobalSpace))
      }
  }

  /** A Sigil that ONLY overrides the single-arg method — verifies
    * the bridge keeps existing apps working. */
  private object LegacySingleArgSigil extends Sigil {
    override type DB = sigil.db.DefaultSigilDB
    override protected def buildDB(directory: Option[java.nio.file.Path],
                                    storeManager: lightdb.store.CollectionManager,
                                    appUpgrades: List[lightdb.upgrade.DatabaseUpgrade]): DB =
      new sigil.db.DefaultSigilDB(directory, storeManager, appUpgrades)
    override def providerFor(modelId: Id[sigil.db.Model], chain: List[ParticipantId]): Task[sigil.provider.Provider] =
      Task.error(new RuntimeException("not used"))

    override def accessibleSpaces(chain: List[ParticipantId]): Task[Set[SpaceId]] =
      Task.pure(Set[SpaceId](GlobalSpace))
  }

  "Sigil.accessibleSpaces(chain, conversationId)" should {
    "return different sets for different conversation ids with the same chain" in {
      val convA = Conversation.id("conv-A")
      val convB = Conversation.id("conv-B")
      for {
        a <- PerConversationSigil.accessibleSpaces(Nil, convA)
        b <- PerConversationSigil.accessibleSpaces(Nil, convB)
      } yield {
        a should contain (WorkspaceSpace("repo-a"))
        a should not contain WorkspaceSpace("repo-b")
        b should contain (WorkspaceSpace("repo-b"))
        b should not contain WorkspaceSpace("repo-a")
        a should contain (GlobalSpace.asInstanceOf[SpaceId])
        b should contain (GlobalSpace.asInstanceOf[SpaceId])
      }
    }

    "fall through to the single-arg override when the two-arg method isn't overridden" in {
      val convA = Conversation.id("conv-A")
      val convB = Conversation.id("conv-B")
      for {
        // Two-arg call to the legacy Sigil — delegates to the
        // single-arg override → same set across both conversations.
        a <- LegacySingleArgSigil.accessibleSpaces(Nil, convA)
        b <- LegacySingleArgSigil.accessibleSpaces(Nil, convB)
      } yield {
        a shouldBe Set[SpaceId](GlobalSpace)
        b shouldBe a
      }
    }
  }
}
