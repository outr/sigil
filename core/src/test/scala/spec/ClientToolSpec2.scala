package spec

import fabric.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, FiberOps, Task}
import sigil.conversation.{Conversation, TopicEntry, TurnInput, ConversationView}
import sigil.event.{Event, ToolInvoke, ToolOutcome}
import sigil.provider.ConversationMode
import sigil.signal.{ClientToolResult, ClientToolsRegistered, EventState, RegisterClientTools, Signal, ToolDelta, UnregisterClientTools}
import sigil.tool.client.ClientToolSpec
import sigil.tool.{DiscoveryRequest, JsonInput, ToolName}
import sigil.TurnContext

import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * UI-registered interaction tools: the frontend registers its
 * screens / panels / actions on conversation load (a
 * [[RegisterClientTools]] Notice), they become discoverable through
 * `find_capability` and resolvable into the roster, and execution is
 * the inversion of a server tool — the durable ToolInvoke broadcast
 * IS the dispatch, observed by the UI on its signal stream.
 *
 * Verifies:
 *   1. Registration via handleNotice → ack Notice; discoverable in
 *      its own conversation only; resolvable via `resolveToolFor`.
 *   2. Server-tool name collisions and invalid names are rejected
 *      with reasons in the ack.
 *   3. Fire-and-forget execution settles immediately with an ack.
 *   4. Round-trip execution parks until [[ClientToolResult]] answers;
 *      an error answer settles a recoverable Failure.
 *   5. Round-trip timeout settles a recoverable Failure, never a
 *      fabricated success.
 *   6. Deregistration (explicit + whole-session) withdraws the tools;
 *      executing after detach settles a client-gone Failure.
 */
class ClientToolSpec2 extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private def freshConv(): Id[Conversation] = {
    val convId = Conversation.id(s"client-tools-${rapid.Unique()}")
    TestSigil.withDB(_.conversations.transaction(_.upsert(
      Conversation(topics = TestTopicStack, _id = convId)))).sync()
    convId
  }

  private def spec(name: String,
                   expectsResult: Boolean = false,
                   description: String = "Open the ingredient editor panel for the given id.",
                   keywords: Set[String] = Set("ingredient", "editor", "panel", "open")): ClientToolSpec =
    ClientToolSpec(
      name          = name,
      description   = description,
      keywords      = keywords,
      inputSchema   = obj(
        "type" -> str("object"),
        "properties" -> obj("id" -> obj("type" -> str("string"))),
        "required" -> arr(str("id"))
      ),
      expectsResult = expectsResult
    )

  private def recorder(viewer: sigil.participant.ParticipantId): (ConcurrentLinkedQueue[Signal], java.util.concurrent.atomic.AtomicBoolean) = {
    val q = new ConcurrentLinkedQueue[Signal]()
    val running = new java.util.concurrent.atomic.AtomicBoolean(true)
    TestSigil.signalsFor(viewer)
      .takeWhile(_ => running.get())
      .evalMap(s => Task { q.add(s); () })
      .drain
      .startUnit()
    (q, running)
  }

  private def waitFor(timeout: FiniteDuration)(cond: => Boolean): Task[Unit] = {
    val deadline = System.currentTimeMillis() + timeout.toMillis
    def loop: Task[Unit] =
      if (cond || System.currentTimeMillis() > deadline) Task.unit
      else Task.sleep(50.millis).flatMap(_ => loop)
    loop
  }

  private def turnContext(convId: Id[Conversation]): TurnContext =
    TurnContext(
      sigil = TestSigil,
      chain = List(TestUser, TestAgent),
      conversation = Conversation(topics = TestTopicStack, _id = convId),
      turnInput = TurnInput(ConversationView(conversationId = convId)),
      model = TestSigil.defaultTestModel
    )

  private def discovery(convId: Id[Conversation], keywords: String): DiscoveryRequest =
    DiscoveryRequest(
      keywords       = keywords,
      chain          = List(TestUser, TestAgent),
      mode           = ConversationMode,
      callerSpaces   = Set.empty,
      conversationId = Some(convId)
    )

  "client tool registration" should {

    "register via handleNotice, ack, and scope discovery to the conversation" in {
      val convId = freshConv()
      val otherConv = freshConv()
      val (recorded, running) = recorder(TestUser)
      for {
        _ <- Task.sleep(150.millis)
        _ <- TestSigil.handleNotice(
               RegisterClientTools(convId, sessionId = "tab-1", tools = List(spec("open_ingredient_editor"))),
               TestUser)
        _ <- waitFor(5.seconds)(recorded.iterator().asScala.exists(_.isInstanceOf[ClientToolsRegistered]))
        matches      <- TestSigil.findCapabilities(discovery(convId, "ingredient editor"))
        otherMatches <- TestSigil.findCapabilities(discovery(otherConv, "ingredient editor"))
        resolved     <- TestSigil.resolveToolFor(convId, ToolName("open_ingredient_editor"))
        elsewhere    <- TestSigil.resolveToolFor(otherConv, ToolName("open_ingredient_editor"))
      } yield {
        running.set(false)
        val ack = recorded.iterator().asScala.collectFirst { case a: ClientToolsRegistered => a }.get
        ack.accepted shouldBe List("open_ingredient_editor")
        ack.rejected shouldBe empty
        matches.map(_.name) should contain ("open_ingredient_editor")
        otherMatches.map(_.name) should not contain "open_ingredient_editor"
        resolved.map(_.name.value) shouldBe Some("open_ingredient_editor")
        elsewhere shouldBe None
      }
    }

    "reject server-tool collisions and invalid names with reasons in the ack" in {
      val convId = freshConv()
      val (recorded, running) = recorder(TestUser)
      for {
        _ <- Task.sleep(150.millis)
        _ <- TestSigil.handleNotice(
               RegisterClientTools(convId, sessionId = "tab-1", tools = List(
                 spec("respond"),
                 spec("Bad-Name!"),
                 spec("open_settings")
               )),
               TestUser)
        _ <- waitFor(5.seconds)(recorded.iterator().asScala.exists(_.isInstanceOf[ClientToolsRegistered]))
      } yield {
        running.set(false)
        val ack = recorded.iterator().asScala.collectFirst { case a: ClientToolsRegistered => a }.get
        ack.accepted shouldBe List("open_settings")
        ack.rejected.keySet shouldBe Set("respond", "Bad-Name!")
        ack.rejected("respond") should include ("collides")
        ack.rejected("Bad-Name!") should include ("invalid name")
      }
    }
  }

  "client tool execution" should {

    "settle a fire-and-forget call immediately with a dispatch acknowledgment" in {
      val convId = freshConv()
      for {
        _ <- TestSigil.clientTools.register(convId, "tab-1", List(spec("open_ingredient_editor"))).map(_ => ())
        tool <- TestSigil.resolveToolFor(convId, ToolName("open_ingredient_editor")).map(_.get)
        signals <- tool.execute(JsonInput(obj("id" -> str("bacopa"))), turnContext(convId), Event.id()).toList
      } yield {
        val settle = signals.collect { case d: ToolDelta => d }.find(_.state.contains(EventState.Complete)).get
        settle.outcome shouldBe Some(ToolOutcome.Success)
        settle.output.map(_.toString).getOrElse("") should include ("Dispatched")
      }
    }

    "park a round-trip call until ClientToolResult answers" in {
      val convId = freshConv()
      val invokeId = Event.id()
      for {
        _ <- TestSigil.clientTools.register(convId, "tab-1", List(spec("read_current_screen", expectsResult = true))).map(_ => ())
        tool <- TestSigil.resolveToolFor(convId, ToolName("read_current_screen")).map(_.get)
        fiber = tool.execute(JsonInput(obj("id" -> str("x"))), turnContext(convId), invokeId).toList.start()
        // Give the execution a beat to park, then answer as the UI would.
        _ <- Task.sleep(300.millis)
        _ <- TestSigil.handleNotice(ClientToolResult(convId, invokeId, content = "Ingredient list, 34 rows, bacopa selected"), TestUser)
        signals <- fiber
      } yield {
        val settle = signals.collect { case d: ToolDelta => d }.find(_.state.contains(EventState.Complete)).get
        settle.outcome shouldBe Some(ToolOutcome.Success)
        settle.output.map(_.toString).getOrElse("") should include ("bacopa selected")
      }
    }

    "settle an error answer as a recoverable failure" in {
      val convId = freshConv()
      val invokeId = Event.id()
      for {
        _ <- TestSigil.clientTools.register(convId, "tab-1", List(spec("read_current_screen", expectsResult = true))).map(_ => ())
        tool <- TestSigil.resolveToolFor(convId, ToolName("read_current_screen")).map(_.get)
        fiber = tool.execute(JsonInput(obj()), turnContext(convId), invokeId).toList.start()
        _ <- Task.sleep(300.millis)
        _ <- TestSigil.handleNotice(ClientToolResult(convId, invokeId, content = "screen not mounted", isError = true), TestUser)
        signals <- fiber
      } yield {
        val settle = signals.collect { case d: ToolDelta => d }.find(_.state.contains(EventState.Complete)).get
        settle.outcome match {
          case Some(ToolOutcome.Failure(reason, recoverable)) =>
            reason should include ("screen not mounted")
            recoverable shouldBe true
          case other => fail(s"expected recoverable Failure, got $other")
        }
      }
    }

    "settle a recoverable failure on timeout — never a fabricated success" in {
      val convId = freshConv()
      TestSigil.setClientToolResultTimeoutMs(500L)
      (for {
        _ <- TestSigil.clientTools.register(convId, "tab-1", List(spec("read_current_screen", expectsResult = true))).map(_ => ())
        tool <- TestSigil.resolveToolFor(convId, ToolName("read_current_screen")).map(_.get)
        signals <- tool.execute(JsonInput(obj()), turnContext(convId), Event.id()).toList
      } yield {
        val settle = signals.collect { case d: ToolDelta => d }.find(_.state.contains(EventState.Complete)).get
        settle.outcome match {
          case Some(ToolOutcome.Failure(reason, recoverable)) =>
            reason should include ("did not answer")
            recoverable shouldBe true
          case other => fail(s"expected recoverable Failure, got $other")
        }
      }).guarantee(Task(TestSigil.resetClientToolResultTimeoutMs()))
    }
  }

  "client tool lifecycle" should {

    "withdraw tools on explicit unregister and on session detach" in {
      val convId = freshConv()
      for {
        _ <- TestSigil.clientTools.register(convId, "tab-1", List(spec("open_ingredient_editor"), spec("open_settings")))
        _ <- TestSigil.handleNotice(
               UnregisterClientTools(convId, "tab-1", names = Some(Set("open_settings"))), TestUser)
        afterPartial <- TestSigil.resolveToolFor(convId, ToolName("open_settings"))
        stillLive    <- TestSigil.resolveToolFor(convId, ToolName("open_ingredient_editor"))
        _ <- TestSigil.clientTools.deregisterSession("tab-1")
        afterDetach  <- TestSigil.resolveToolFor(convId, ToolName("open_ingredient_editor"))
      } yield {
        afterPartial shouldBe None
        stillLive should not be None
        afterDetach shouldBe None
      }
    }

    "settle a client-gone failure when the tool executes after deregistration" in {
      val convId = freshConv()
      for {
        _ <- TestSigil.clientTools.register(convId, "tab-1", List(spec("open_ingredient_editor")))
        tool <- TestSigil.resolveToolFor(convId, ToolName("open_ingredient_editor")).map(_.get)
        _ <- TestSigil.clientTools.deregisterSession("tab-1")
        signals <- tool.execute(JsonInput(obj("id" -> str("x"))), turnContext(convId), Event.id()).toList
      } yield {
        val settle = signals.collect { case d: ToolDelta => d }.find(_.state.contains(EventState.Complete)).get
        settle.outcome match {
          case Some(ToolOutcome.Failure(reason, recoverable)) =>
            reason should include ("no longer connected")
            recoverable shouldBe true
          case other => fail(s"expected recoverable Failure, got $other")
        }
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
