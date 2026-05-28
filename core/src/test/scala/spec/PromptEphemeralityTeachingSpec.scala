package spec

import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.{Conversation, ParticipantProjection, RecentToolInvocation, TurnInput}
import sigil.db.Model
import sigil.provider.{ConversationMode, ConversationRequest, GenerationSettings, Instructions}
import sigil.provider.llamacpp.LlamaCppProvider
import sigil.tool.{ToolName, core}
import sigil.tool.core.{CoreTools, FindCapabilityTool}

/**
 * Sigil #303 — verifies the system prompt teaches that tool
 * availability is ephemeral and that re-discovery is the recovery
 * path when a previously-used tool is no longer in the offered set.
 * Defense-in-depth at the prompt layer alongside #301's persistence
 * fix and #302's priority swap.
 */
class PromptEphemeralityTeachingSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private def requestWith(projection: ParticipantProjection,
                          convId: Id[Conversation]): ConversationRequest =
    ConversationRequest(
      conversationId         = convId,
      model                  = TestSigil.testModel(Model.id("test", "ephemerality-teaching-model")),
      instructions           = Instructions(),
      turnInput              = TurnInput(
        conversationId         = convId,
        participantProjections = Map(TestAgent -> projection)
      ),
      currentMode            = ConversationMode,
      currentTopic           = TestTopicEntry,
      previousTopics         = Nil,
      generationSettings     = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0)),
      tools                  = CoreTools.all,
      chain                  = List(TestUser, TestAgent)
    )

  private def renderSystem(req: ConversationRequest): Task[String] = {
    val provider = LlamaCppProvider(TestSigil.llamaCppHost, Nil, TestSigil)
    provider.requestConverter(req).map(_.content match {
      case Some(c: spice.http.content.StringContent) => c.value
      case _                                          => ""
    })
  }

  "Provider.renderSystem (ephemerality teaching, sigil #303)" should {

    "teach that tool availability is ephemeral and re-discovery is the recovery path" in {
      val convId = Conversation.id(s"ephem-teach-${rapid.Unique()}")
      val proj = ParticipantProjection.empty(TestAgent, convId)
      renderSystem(requestWith(proj, convId)).map { body =>
        // The teaching block must call out ephemerality explicitly —
        // the agent has to know that yesterday's `Recently used tools`
        // entry isn't a guarantee of current availability.
        body.toLowerCase should include ("ephemeral")
        // And it must point at find_capability as the recovery path
        // when a tool the agent remembers is missing from the offered
        // set. The model's natural failure mode without this — pick
        // the first-position tool and loop on it (Sage wire log
        // 2026-05-28 10:33:53 → 10:34:04: 3× change_mode("coding")).
        body should include ("find_capability")
        // Either "re-discovery" or "rediscover" — match the bug doc's
        // proposed wording without locking down exact phrasing.
        body.toLowerCase should (include ("re-discovery") or include ("rediscover") or include ("re-discover"))
      }
    }

    "soften the duplicate-call warning so re-calling find_capability is permitted when the roster has changed" in {
      // Construct a recentToolInvocations history where find_capability
      // was called twice with the same query (the "tool I needed
      // dropped off the roster, I'm re-searching" pattern). The
      // dedup-warning block fires for this; the refinement we're
      // testing is that the warning's language no longer flatly
      // discourages re-discovery — it conditions the discouragement
      // on "unless your tool roster has changed."
      val convId = Conversation.id(s"ephem-dedup-${rapid.Unique()}")
      val now = System.currentTimeMillis()
      val invocations = List(
        RecentToolInvocation(
          toolName    = FindCapabilityTool.name,
          argsHash    = "h-grep-search",
          argsPreview = """{"keywords":"grep search find"}""",
          invokedAt   = Timestamp(now - 30_000L)
        ),
        RecentToolInvocation(
          toolName    = FindCapabilityTool.name,
          argsHash    = "h-grep-search",
          argsPreview = """{"keywords":"grep search find"}""",
          invokedAt   = Timestamp(now - 5_000L)
        )
      )
      val proj = ParticipantProjection.empty(TestAgent, convId).copy(recentToolInvocations = invocations)
      renderSystem(requestWith(proj, convId)).map { body =>
        body should include ("Repeated tool calls")
        // Sigil #303 — the warning must include the roster-change
        // caveat so the agent isn't told identical re-calls are
        // pointless when the framework has narrowed the roster
        // since the prior call.
        body.toLowerCase should (include ("unless your tool roster has changed") or include ("unless your roster has changed"))
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
