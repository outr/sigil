package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.{Conversation, ConversationView, DiscoveredCapability, TopicEntry, TurnInput}
import sigil.provider.{ConversationMode, ConversationRequest, GenerationSettings, Instructions, ProviderCall}
import sigil.tool.{Tool, ToolContext, ToolInput, ToolName, TextToolOutput}
import sigil.tool.discovery.CapabilityType

import java.util.concurrent.atomic.AtomicReference

/**
 * Regression for sigil #299 — the system prompt's "Suggested tools"
 * and "Capabilities you've already discovered" sections used to be
 * rendered from raw projection / TurnContext sources, decoupled from
 * the wire `tools` array. When narrowing (#286/#287) or
 * `findTools.byName` ordering dropped a tool from the merged roster,
 * the prompt still claimed "X is in your roster — call it directly,"
 * the wire didn't carry X's schema, and the model fell back to
 * `respond(endsTurn=false)` indefinitely.
 *
 * The fix filters both prompt sections by the wire's `c.tools`
 * roster so the prompt cannot advertise a tool the wire doesn't
 * actually offer. This spec verifies the constraint on a synthetic
 * request whose `tools` array and `discoveredCapabilities` /
 * `suggestedTools` were deliberately mismatched.
 */
class PromptToolsConsistencySpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val convId: Id[Conversation] = Conversation.id(s"prompt-cons-${rapid.Unique()}")

  // Synthetic tools. Two are "on the wire" — they'll be in
  // `ProviderCall.tools`. Two are "discovered but not offered" —
  // mentioned via `discoveredCapabilities` / `suggestedTools` but
  // NOT in the wire roster. The prompt sections must omit the
  // discovered-but-not-offered names.
  case class StubInput() extends ToolInput derives fabric.rw.RW

  private def stubTool(n: String): Tool = new Tool {
    type Input  = StubInput
    type Output = TextToolOutput
    val inputRW  = summon[fabric.rw.RW[StubInput]]
    val outputRW = summon[fabric.rw.RW[TextToolOutput]]
    val name = ToolName(n)
    val description = s"stub tool $n"
    override def executeOutput(input: StubInput, context: ToolContext): Task[TextToolOutput] =
      Task.pure(TextToolOutput(s"$n ran"))
  }

  private val wireTool1 = stubTool("wired_tool_a")
  private val wireTool2 = stubTool("wired_tool_b")

  private def renderSystem(
    discoveredMatches: List[ToolName],
    projectionSuggested: List[ToolName],
    wireTools: Vector[Tool]
  ): String = {
    // Use TestSigil's openai provider stub via the existing public
    // API: construct a provider, hand it a synthetic ConversationRequest,
    // pluck the system prompt via reflection on `renderSystem`.
    val provider = new sigil.provider.Provider {
      override def `type`: _root_.sigil.provider.ProviderType = _root_.sigil.provider.ProviderType.OpenAI
      override protected def sigil: _root_.sigil.Sigil = TestSigil
      override def httpRequestFor(input: ProviderCall): rapid.Task[spice.http.HttpRequest] =
        rapid.Task.error(new UnsupportedOperationException)
      override def call(input: ProviderCall): rapid.Stream[_root_.sigil.provider.ProviderEvent] =
        rapid.Stream.empty
    }
    val ref = new AtomicReference[Map[String, DiscoveredCapability]](Map.empty)
    if (discoveredMatches.nonEmpty) {
      val now = lightdb.time.Timestamp()
      ref.set(Map("foo" -> DiscoveredCapability(matches = discoveredMatches, firstSeen = now, lastSeen = now)))
    }
    val proj = sigil.conversation.ParticipantProjection
      .empty(TestUser, convId)
      .copy(suggestedTools = projectionSuggested)
    val ti = TurnInput(
      conversationId         = convId,
      participantProjections = Map(TestUser -> proj)
    )
    val request = ConversationRequest(
      conversationId         = convId,
      model                  = TestSigil.defaultTestModel,
      instructions           = Instructions(),
      turnInput              = ti,
      currentMode            = ConversationMode,
      currentTopic           = TestTopicEntry,
      previousTopics         = Nil,
      generationSettings     = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0)),
      tools                  = wireTools,
      builtInTools           = Set.empty,
      chain                  = List(TestUser),
      roles                  = List(sigil.role.GeneralistRole),
      discoveredCapabilities = ref.get(),
      discoveredCapabilitiesRef = ref
    )
    // renderSystem takes the ConversationRequest directly — `c.tools`
    // is the wire roster the prompt sections must consistency-check
    // against.
    val m = classOf[sigil.provider.Provider].getDeclaredMethod(
      "renderSystem", classOf[ConversationRequest], classOf[sigil.provider.ResolvedReferences]
    )
    m.setAccessible(true)
    val resolved = sigil.provider.ResolvedReferences(
      criticalMemories = Vector.empty,
      memories         = Vector.empty,
      summaries        = Vector.empty
    )
    m.invoke(provider, request, resolved).asInstanceOf[String]
  }

  "system prompt filtering (sigil #299)" should {

    "filter 'Capabilities you've already discovered' matches to wire-offered names only" in Task {
      val discovered = List(
        ToolName("wired_tool_a"),     // offered — should appear
        ToolName("not_on_wire_x")     // discovered but not offered — must NOT appear
      )
      val prompt = renderSystem(
        discoveredMatches  = discovered,
        projectionSuggested = Nil,
        wireTools          = Vector(wireTool1, wireTool2)
      )
      prompt should include ("Capabilities you've already discovered")
      prompt should include ("wired_tool_a")
      prompt should not include ("not_on_wire_x")
    }

    "filter 'Suggested tools' to wire-offered names only" in Task {
      val suggested = List(
        ToolName("wired_tool_b"),     // offered — should appear
        ToolName("not_on_wire_y")     // suggested but not offered — must NOT appear
      )
      val prompt = renderSystem(
        discoveredMatches   = Nil,
        projectionSuggested = suggested,
        wireTools           = Vector(wireTool1, wireTool2)
      )
      prompt should include ("Suggested tools")
      prompt should include ("wired_tool_b")
      prompt should not include ("not_on_wire_y")
    }

    "omit the 'Capabilities you've already discovered' section entirely when no discovered matches are offered" in Task {
      val discovered = List(ToolName("not_on_wire_x"), ToolName("not_on_wire_y"))
      val prompt = renderSystem(
        discoveredMatches  = discovered,
        projectionSuggested = Nil,
        wireTools          = Vector(wireTool1, wireTool2)
      )
      // With every discovered match filtered out, the whole section
      // (including the DIRECTIVE sentence) is suppressed — the
      // prompt isn't allowed to claim "these tools are NOW in your
      // roster" for tools that aren't.
      prompt should not include ("Capabilities you've already discovered")
      prompt should not include ("DIRECTIVE: These tools are NOW in your roster")
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
