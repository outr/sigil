package spec

import fabric.{Json, str}
import fabric.rw.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.{GlobalSpace, Sigil, SpaceId}
import sigil.conversation.{Conversation, Topic, TopicEntry}
import sigil.db.{DefaultSigilDB, Model, ModelArchitecture, ModelLinks, ModelPricing, ModelTopProvider, SigilDB}
import sigil.event.{Event, MessageRole, MessageVisibility}
import sigil.participant.{AgentParticipantId, ParticipantId, Participant, DefaultAgentParticipant}
import sigil.provider.{CallId, ConversationMode, GenerationSettings, Instructions, Provider, ProviderCall, ProviderEvent, ProviderType, StopReason}
import sigil.signal.Signal
import sigil.tool.core.CoreTools
import sigil.workflow.{JobStepInput, LoopStepInput, WorkflowCollections, WorkflowHost, WorkflowSigil, WorkflowTemplate}
import sigil.workflow.event.{WorkflowRunCompleted, WorkflowRunStarted, WorkflowStepCompleted}
import sigil.transport.{ResumeRequest, SignalSink, SignalTransport}

import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.*

/**
 * End-to-end integration test for the workflow runtime. Builds a
 * minimal [[WorkflowSigil]] over a local DB, schedules a one-step
 * workflow, and asserts the four lifecycle Events flow into the
 * originating conversation's signal stream as the run progresses.
 *
 * Doesn't exercise an LLM provider — the JobStepInput has neither
 * `prompt` nor `tool` set, so [[sigil.workflow.SigilJobStep]] emits
 * `Json.Null` and completes immediately. That's enough to drive
 * `onWorkflowStarted` → `onStepCompleted` → `onWorkflowCompleted`,
 * which is what we're verifying.
 */
class WorkflowEndToEndSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  TestWorkflowSigil.initFor(getClass.getSimpleName)

  "WorkflowSigil + manager" should {
    "publish all four lifecycle Events into the originating conversation when a workflow runs" in {
      val convId = Conversation.id("workflow-e2e-conv")
      val recorded = new ConcurrentLinkedQueue[Signal]()
      @volatile var running = true
      // Subscribe to the unfiltered signal stream so visibility /
      // viewer-filter quirks can't hide lifecycle Events from the
      // assertion. The Events themselves carry visibility = All so
      // both `signals` and `signalsFor(viewer)` see them in
      // production; using `signals` here is just diagnostic clarity.
      TestWorkflowSigil.signals
        .evalMap(s => Task { recorded.add(s); () })
        .takeWhile(_ => running)
        .drain
        .startUnit()
      Thread.sleep(100)  // give the subscription a moment to attach

      val template = WorkflowTemplate(
        name = "noop",
        description = Some("Single empty step — completes immediately"),
        steps = List(JobStepInput(id = "noop", name = Some("Noop step"))),
        space = GlobalSpace,
        createdBy = Some(WorkflowTestUser),
        conversationId = Some(convId)
      )

      // Conversation needs to exist so the manager can resolve
      // participants when publishing lifecycle Events.
      val conv = Conversation(
        topics = List(TopicEntry(WorkflowTestTopic.id, WorkflowTestTopic.label, WorkflowTestTopic.summary)),
        participants = List(DefaultAgentParticipant(
          id = WorkflowTestUser.asInstanceOf[AgentParticipantId],
          modelId = Model.id("test", "model"),
          toolNames = Nil,
          instructions = Instructions(),
          generationSettings = GenerationSettings()
        )),
        currentMode = ConversationMode,
        space = GlobalSpace,
        _id = convId
      )

      for {
        _      <- TestWorkflowSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _      <- TestWorkflowSigil.withDB(_.workflowTemplates.transaction(_.upsert(template)))
        _      <- sigil.workflow.WorkflowScheduler.scheduleTemplate(
                    TestWorkflowSigil, template
                  )
        _      <- waitForCompletion(recorded, 10.seconds)
      } yield {
        running = false
        import scala.jdk.CollectionConverters.*
        val all = recorded.iterator().asScala.toList
        val starts = all.collect { case e: WorkflowRunStarted   => e }
        val steps  = all.collect { case e: WorkflowStepCompleted => e }
        val ends   = all.collect { case e: WorkflowRunCompleted  => e }
        starts should have size 1
        steps should have size 1
        steps.head.success shouldBe true
        ends should have size 1
        ends.head.workflowName shouldBe "noop"
      }
    }

    // Sigil #388 — a prompt leaf must carry GenerationSettings into the
    // request (not a fresh empty one) and force reasoningMode = Off, so a
    // reasoning model can't burn the whole budget on the thinking channel and
    // return empty content. Here: capture the settings the provider receives
    // and assert the prompt leaf's call has reasoning off.
    "send reasoningMode = Off on a prompt leaf's provider request (#388)" in {
      val seen = new ConcurrentLinkedQueue[sigil.provider.ReasoningMode]()
      val capturing = new Provider {
        override def `type`: ProviderType = ProviderType.LlamaCpp
        override def models: List[Model] = Nil
        override protected def sigil: Sigil = TestWorkflowSigil
        override def httpRequestFor(input: ProviderCall): Task[spice.http.HttpRequest] =
          Task.error(new UnsupportedOperationException("no wire"))
        override def call(input: ProviderCall): Stream[ProviderEvent] = {
          seen.add(input.generationSettings.reasoningMode)
          Stream.emits(List[ProviderEvent](
            ProviderEvent.ContentBlockDelta(CallId("cap-0"), "done"),
            ProviderEvent.Done(StopReason.Complete)))
        }
      }
      TestWorkflowSigil.setProvider(Task.pure(capturing))

      val convId = Conversation.id(s"workflow-388-${rapid.Unique()}")
      val recorded = new ConcurrentLinkedQueue[Signal]()
      @volatile var running = true
      TestWorkflowSigil.signals.evalMap(s => Task { recorded.add(s); () }).takeWhile(_ => running).drain.startUnit()
      Thread.sleep(100)

      val template = WorkflowTemplate(
        name = "prompt-leaf",
        description = Some("Single prompt step"),
        steps = List(JobStepInput(id = "p", prompt = Some("Say ok."))),
        space = GlobalSpace,
        createdBy = Some(WorkflowTestUser),
        conversationId = Some(convId)
      )
      val conv = Conversation(
        topics = List(TopicEntry(WorkflowTestTopic.id, WorkflowTestTopic.label, WorkflowTestTopic.summary)),
        participants = List(DefaultAgentParticipant(
          id = WorkflowTestUser.asInstanceOf[AgentParticipantId], modelId = TestWorkflowSigil.testModelId,
          toolNames = CoreTools.coreToolNames, instructions = Instructions(), generationSettings = GenerationSettings())),
        currentMode = ConversationMode, space = GlobalSpace, _id = convId
      )

      for {
        _ <- TestWorkflowSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _ <- TestWorkflowSigil.withDB(_.workflowTemplates.transaction(_.upsert(template)))
        _ <- sigil.workflow.WorkflowScheduler.scheduleTemplate(TestWorkflowSigil, template)
        _ <- waitForCompletion(recorded, 10.seconds)
        _ <- Task.sleep(300.millis)
      } yield {
        running = false
        TestWorkflowSigil.resetProvider()
        import scala.jdk.CollectionConverters.*
        // The prompt leaf's request carried reasoning OFF (the woken agent's
        // turn may add other settings; at least one Off proves the leaf path).
        seen.iterator().asScala.toList should contain(sigil.provider.ReasoningMode.Off)
      }
    }

    // Sigil #390 — when a run reaches a terminal state, the scheduling
    // conversation's agent is woken so it can report the outcome to the user,
    // rather than leaving the conversation's last word as "running". The wake
    // posts an internal (Agents-visibility) `workflow-outcome` Message into the
    // scheduling conversation, addressed to the scheduling agent.
    "wake the scheduling conversation's agent with an outcome message when the run terminates" in {
      val convId = Conversation.id(s"workflow-wake-390-${rapid.Unique()}")
      val recorded = new ConcurrentLinkedQueue[Signal]()
      @volatile var running = true
      TestWorkflowSigil.signals.evalMap(s => Task { recorded.add(s); () }).takeWhile(_ => running).drain.startUnit()
      Thread.sleep(100)

      val template = WorkflowTemplate(
        name = "noop-wake",
        description = Some("Single empty step — completes immediately"),
        steps = List(JobStepInput(id = "noop", name = Some("Noop step"))),
        space = GlobalSpace,
        createdBy = Some(WorkflowTestUser),
        conversationId = Some(convId)
      )
      val conv = Conversation(
        topics = List(TopicEntry(WorkflowTestTopic.id, WorkflowTestTopic.label, WorkflowTestTopic.summary)),
        participants = List(DefaultAgentParticipant(
          id = WorkflowTestUser.asInstanceOf[AgentParticipantId],
          modelId = TestWorkflowSigil.testModelId,
          toolNames = CoreTools.coreToolNames,
          instructions = Instructions(),
          generationSettings = GenerationSettings()
        )),
        currentMode = ConversationMode,
        space = GlobalSpace,
        _id = convId
      )

      def outcomeSeen: Boolean = {
        import scala.jdk.CollectionConverters.*
        recorded.iterator().asScala.exists {
          case m: sigil.event.Message => m.conversationId == convId && m.source.contains("workflow-outcome")
          case _                      => false
        }
      }
      def awaitOutcome(deadline: Long): Task[Boolean] =
        if (outcomeSeen) Task.pure(true)
        else if (System.currentTimeMillis() > deadline) Task.pure(false)
        else Task.sleep(100.millis).flatMap(_ => awaitOutcome(deadline))

      for {
        _   <- TestWorkflowSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _   <- TestWorkflowSigil.withDB(_.workflowTemplates.transaction(_.upsert(template)))
        _   <- sigil.workflow.WorkflowScheduler.scheduleTemplate(TestWorkflowSigil, template)
        ok  <- awaitOutcome(System.currentTimeMillis() + 10_000L)
        // Let the woken agent's (trivial) turn settle before teardown.
        _   <- Task.sleep(300.millis)
      } yield {
        running = false
        import scala.jdk.CollectionConverters.*
        val outcome = recorded.iterator().asScala.collectFirst {
          case m: sigil.event.Message if m.conversationId == convId && m.source.contains("workflow-outcome") => m
        }
        withClue("a workflow-outcome message must be posted into the scheduling conversation on terminal: ") {
          outcome should not be empty
        }
        // Internal notice — addressed to the scheduling agent, hidden from the user.
        outcome.get.visibility shouldBe MessageVisibility.Agents
        outcome.get.addressees.exists(_.contains(WorkflowTestUser)) shouldBe true
      }
    }

    // Sigil #385 — a run scheduled from a bound conversation gets its own
    // sub-conversation (#376); its lifecycle Events therefore live on the
    // sub-conv with `parentConversationId = boundConv` (#381). A client is
    // subscribed (via SignalTransport) to the BOUND conversation only — it must
    // still receive those lifecycle Events so the activity bar surfaces the run.
    // Pre-fix the transport's conversation-scope filter dropped them (sub-conv
    // not in the subscribed set), so the pill never appeared.
    "deliver a #376 sub-conversation run's lifecycle Events to a SignalTransport subscriber scoped to the bound parent" in {
      val boundId = Conversation.id("workflow-e2e-bound-385")
      val recorded = new ConcurrentLinkedQueue[Signal]()
      val sink = new SignalSink {
        override def push(signal: Signal): Task[Unit] = Task { recorded.add(signal); () }
        override def close: Task[Unit] = Task.unit
      }
      val transport = new SignalTransport(TestWorkflowSigil)

      val template = WorkflowTemplate(
        name = "noop-385",
        description = Some("Single empty step — completes immediately"),
        steps = List(JobStepInput(id = "noop", name = Some("Noop step"))),
        space = GlobalSpace,
        createdBy = Some(WorkflowTestUser),
        conversationId = Some(boundId)
      )
      val conv = Conversation(
        topics = List(TopicEntry(WorkflowTestTopic.id, WorkflowTestTopic.label, WorkflowTestTopic.summary)),
        participants = List(DefaultAgentParticipant(
          id = WorkflowTestUser.asInstanceOf[AgentParticipantId],
          modelId = Model.id("test", "model"),
          toolNames = Nil,
          instructions = Instructions(),
          generationSettings = GenerationSettings()
        )),
        currentMode = ConversationMode,
        space = GlobalSpace,
        _id = boundId
      )

      for {
        _      <- TestWorkflowSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _      <- TestWorkflowSigil.withDB(_.workflowTemplates.transaction(_.upsert(template)))
        // Subscriber watches ONLY the bound conversation — not the run's
        // (not-yet-existent) sub-conversation.
        handle <- transport.attach(WorkflowTestUser, sink, ResumeRequest.None,
                                   conversations = Some(Set(boundId)))
        _      <- Task.sleep(100.millis)
        wf     <- sigil.workflow.WorkflowScheduler.scheduleTemplate(TestWorkflowSigil, template)
        _      <- waitForCompletion(recorded, 10.seconds)
        _      <- handle.detach
      } yield {
        import scala.jdk.CollectionConverters.*
        val all = recorded.iterator().asScala.toList
        // The run lives on its own sub-conversation, distinct from the bound conv.
        wf.conversationId.map(Id[Conversation](_)) should not contain boundId
        val starts = all.collect { case e: WorkflowRunStarted  => e }
        val ends   = all.collect { case e: WorkflowRunCompleted => e }
        withClue(s"parent-scoped subscriber received ${all.size} signals: ") {
          starts.map(_.workflowName) should contain ("noop-385")
          ends.map(_.workflowName) should contain ("noop-385")
          // And the lifecycle Events that arrived are the sub-conversation's,
          // surfaced to the parent via `additionalDeliveryScopes`.
          starts.head.parentConversationId shouldBe Some(boundId)
        }
      }
    }

    "publish a WorkflowStepCompleted for each loop body iteration (#353)" in {
      val convId = Conversation.id("workflow-e2e-loop-conv")
      val recorded = new ConcurrentLinkedQueue[Signal]()
      @volatile var running = true
      TestWorkflowSigil.signals
        .evalMap(s => Task { recorded.add(s); () })
        .takeWhile(_ => running)
        .drain
        .startUnit()
      Thread.sleep(100)

      val template = WorkflowTemplate(
        name = "loop-noop",
        description = Some("Loop over 3 items, each running a noop body step"),
        steps = List(LoopStepInput(
          id = "loop",
          over = "items",
          body = List(JobStepInput(id = "body", name = Some("body step")))
        )),
        space = GlobalSpace,
        createdBy = Some(WorkflowTestUser),
        conversationId = Some(convId)
      )
      val conv = Conversation(
        topics = List(TopicEntry(WorkflowTestTopic.id, WorkflowTestTopic.label, WorkflowTestTopic.summary)),
        participants = List(DefaultAgentParticipant(
          id = WorkflowTestUser.asInstanceOf[AgentParticipantId],
          modelId = Model.id("test", "model"),
          toolNames = Nil,
          instructions = Instructions(),
          generationSettings = GenerationSettings()
        )),
        currentMode = ConversationMode,
        space = GlobalSpace,
        _id = convId
      )

      for {
        _ <- TestWorkflowSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _ <- TestWorkflowSigil.withDB(_.workflowTemplates.transaction(_.upsert(template)))
        _ <- sigil.workflow.WorkflowScheduler.scheduleTemplate(
               TestWorkflowSigil, template,
               variables = Map("items" -> fabric.arr(str("a"), str("b"), str("c")))
             )
        _ <- waitForCompletion(recorded, 10.seconds)
      } yield {
        running = false
        import scala.jdk.CollectionConverters.*
        val all = recorded.iterator().asScala.toList
        val steps = all.collect { case e: WorkflowStepCompleted => e }
        val ends  = all.collect { case e: WorkflowRunCompleted  => e }
        ends should have size 1
        // #353 — before the fix a loop emitted ZERO step-completed events (start -> nothing -> end).
        // Now each of the 3 iterations' body step surfaces one (plus the loop container), so per-item
        // progress is observable in the activity bar.
        steps.count(_.success) should be >= 3
      }
    }

    "thread a tool step's typed output into its `output` variable (#354)" in {
      val convId = Conversation.id("workflow-e2e-tooloutput-conv")
      val recorded = new ConcurrentLinkedQueue[Signal]()
      @volatile var running = true
      TestWorkflowSigil.signals
        .evalMap(s => Task { recorded.add(s); () })
        .takeWhile(_ => running)
        .drain
        .startUnit()
      Thread.sleep(100)

      // echo_back returns a typed TextToolOutput("Echo: hi"). Before the fix the step
      // coalesced only Messages (none here, since ToolResults folds into the invoke) and
      // wrote {"ok":"done"}; and the top-level step's payload never reached a variable.
      val template = WorkflowTemplate(
        name = "tool-output",
        description = Some("Single echo_back step writing into variable r"),
        steps = List(JobStepInput(
          id = "echo",
          name = Some("echo step"),
          tool = Some("echo_back"),
          arguments = Some("""{"text":"hi"}"""),
          output = Some("r")
        )),
        space = GlobalSpace,
        createdBy = Some(WorkflowTestUser),
        conversationId = Some(convId)
      )
      val conv = Conversation(
        topics = List(TopicEntry(WorkflowTestTopic.id, WorkflowTestTopic.label, WorkflowTestTopic.summary)),
        participants = List(DefaultAgentParticipant(
          id = WorkflowTestUser.asInstanceOf[AgentParticipantId],
          modelId = Model.id("test", "model"),
          toolNames = Nil,
          instructions = Instructions(),
          generationSettings = GenerationSettings()
        )),
        currentMode = ConversationMode,
        space = GlobalSpace,
        _id = convId
      )

      for {
        _   <- TestWorkflowSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _   <- TestWorkflowSigil.withDB(_.workflowTemplates.transaction(_.upsert(template)))
        _   <- sigil.workflow.WorkflowScheduler.scheduleTemplate(TestWorkflowSigil, template)
        _   <- waitForCompletion(recorded, 10.seconds)
        run <- {
          import scala.jdk.CollectionConverters.*
          val runId = recorded.iterator().asScala.collectFirst { case e: WorkflowRunCompleted => e.runId }.get
          TestWorkflowSigil.withDB(_.workflows.transaction(_.get(lightdb.id.Id[strider.Workflow](runId))))
        }
      } yield {
        running = false
        val wf = run.get
        // The typed TextToolOutput rendered through outputRW — {"text":"Echo: hi"} — is the
        // step payload (proves tool-output capture, not the old {"ok":"done"}) AND is threaded
        // into the `r` variable for downstream {{r}} substitution (proves outputVariable).
        wf.payloads.values.toList should contain(fabric.obj("text" -> str("Echo: hi")))
        wf.variables.get("r") shouldBe Some(fabric.obj("text" -> str("Echo: hi")))
      }
    }
  }

    "compose discovery as a workflow STAGE — a Loop consumes a discovery step's output, no hand-built array (#372 / workflow-first)" in {
      // The keystone of workflow-first: the agent composes
      //   discover -> Loop[act on each] -> verify
      // up front, knowing only the SHAPE; the engine finds the particulars at
      // runtime. The discovery step's text output (grep/glob-shaped) must feed
      // the Loop with NO pre-built array and NO enumeration in the agent's
      // context. This drives exactly that: a `discovery_probe` step emits 3
      // items into `found`, and the Loop iterates its body over them.
      val convId = Conversation.id(s"workflow-first-${rapid.Unique()}")
      val recorded = new ConcurrentLinkedQueue[Signal]()
      @volatile var running = true
      TestWorkflowSigil.signals.evalMap(s => Task { recorded.add(s); () }).takeWhile(_ => running).drain.startUnit()
      Thread.sleep(100)

      val template = WorkflowTemplate(
        name = "find-then-act",
        description = Some("Discovery-as-a-stage: probe -> Loop over its output -> echo each"),
        steps = List(
          JobStepInput(id = "discover", tool = Some("discovery_probe"),
            arguments = Some("""{"count":3}"""), output = Some("found")),
          LoopStepInput(id = "loop", over = "found", itemVariable = "item", output = Some("processed"),
            body = List(JobStepInput(id = "act", tool = Some("echo_back"),
              arguments = Some("""{"text":"{{item}}"}"""))))
        ),
        space = GlobalSpace,
        createdBy = Some(WorkflowTestUser),
        conversationId = Some(convId)
      )
      val conv = Conversation(
        topics = List(TopicEntry(WorkflowTestTopic.id, WorkflowTestTopic.label, WorkflowTestTopic.summary)),
        participants = List(DefaultAgentParticipant(
          id = WorkflowTestUser.asInstanceOf[AgentParticipantId], modelId = Model.id("test", "model"),
          toolNames = Nil, instructions = Instructions(), generationSettings = GenerationSettings())),
        currentMode = ConversationMode, space = GlobalSpace, _id = convId
      )

      for {
        _   <- TestWorkflowSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _   <- TestWorkflowSigil.withDB(_.workflowTemplates.transaction(_.upsert(template)))
        // NOTE: no seeded `variables` — `found` is produced by the discovery STEP.
        _   <- sigil.workflow.WorkflowScheduler.scheduleTemplate(TestWorkflowSigil, template)
        _   <- waitForCompletion(recorded, 15.seconds)
        run <- {
          import scala.jdk.CollectionConverters.*
          val runId = recorded.iterator().asScala.collectFirst { case e: WorkflowRunCompleted => e.runId }.get
          TestWorkflowSigil.withDB(_.workflows.transaction(_.get(lightdb.id.Id[strider.Workflow](runId))))
        }
      } yield {
        running = false
        import scala.jdk.CollectionConverters.*
        val all = recorded.iterator().asScala.toList
        val bodyCompletions = all.collect { case e: WorkflowStepCompleted if e.success => e }
        val wf = run.get
        val processed = wf.variables.get("processed")
        withClue(s"processed=$processed; stepCompletions=${bodyCompletions.size}: ") {
          // The Loop iterated the body once per DISCOVERED item — discovery,
          // not a hand-built array, drove the loop.
          val items = processed.collect { case fabric.Arr(v, _) => v }.getOrElse(Vector.empty)
          items should have size 3
          items.map(_.toString).mkString should (include("item-1").and(include("item-2")).and(include("item-3")))
        }
      }
    }

    "iterate over a LARGE discovery output without the inline-overflow truncating it (#370/#372)" in {
      // The live failure was a ~65-path grep that overflowed inlineContentThreshold.
      // Inside a workflow the discovery output feeds a Loop variable, NOT the
      // agent's prompt — so the inline cap must not bound it, or the Loop sees
      // only the bounded head and silently processes a fraction of the set.
      val convId = Conversation.id(s"workflow-first-large-${rapid.Unique()}")
      val recorded = new ConcurrentLinkedQueue[Signal]()
      @volatile var running = true
      TestWorkflowSigil.signals.evalMap(s => Task { recorded.add(s); () }).takeWhile(_ => running).drain.startUnit()
      Thread.sleep(100)

      val bigCount = 50 // wide (path-shaped) items → ~12 KB, over the 8 KB inline cap
      val template = WorkflowTemplate(
        name = "find-then-act-large",
        description = Some("Large discovery-as-a-stage"),
        steps = List(
          JobStepInput(id = "discover", tool = Some("discovery_probe"),
            arguments = Some(s"""{"count":$bigCount}"""), output = Some("found")),
          LoopStepInput(id = "loop", over = "found", itemVariable = "item", output = Some("processed"),
            body = List(JobStepInput(id = "act", tool = Some("echo_back"),
              arguments = Some("""{"text":"{{item}}"}"""))))
        ),
        space = GlobalSpace, createdBy = Some(WorkflowTestUser), conversationId = Some(convId)
      )
      val conv = Conversation(
        topics = List(TopicEntry(WorkflowTestTopic.id, WorkflowTestTopic.label, WorkflowTestTopic.summary)),
        participants = List(DefaultAgentParticipant(
          id = WorkflowTestUser.asInstanceOf[AgentParticipantId], modelId = Model.id("test", "model"),
          toolNames = Nil, instructions = Instructions(), generationSettings = GenerationSettings())),
        currentMode = ConversationMode, space = GlobalSpace, _id = convId
      )

      for {
        _   <- TestWorkflowSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _   <- TestWorkflowSigil.withDB(_.workflowTemplates.transaction(_.upsert(template)))
        _   <- sigil.workflow.WorkflowScheduler.scheduleTemplate(TestWorkflowSigil, template)
        // #376 — the run now records a per-iteration transcript ToolInvoke on
        // top of the per-iteration lifecycle WorkflowStepCompleted (#353), so a
        // 50-item loop does ~2x the background publishes. They run fire-and-
        // forget (off the run's critical path) but still contend under the
        // suite's 4-JVM concurrent forks; give this stress case more headroom.
        _   <- waitForCompletion(recorded, 60.seconds)
        run <- {
          import scala.jdk.CollectionConverters.*
          val runId = recorded.iterator().asScala.collectFirst { case e: WorkflowRunCompleted => e.runId }.get
          TestWorkflowSigil.withDB(_.workflows.transaction(_.get(lightdb.id.Id[strider.Workflow](runId))))
        }
      } yield {
        running = false
        val processed = run.get.variables.get("processed")
        val items = processed.collect { case fabric.Arr(v, _) => v }.getOrElse(Vector.empty)
        withClue(s"processed ${items.size} items (expected $bigCount) — overflow truncated the discovery output: ") {
          items should have size bigCount
        }
      }
    }

  /** Poll the recorded queue until a `WorkflowRunCompleted` shows up
    * (or the timeout fires). Cheaper than a fixed sleep for fast
    * runs and bounded for slow ones. */
  private def waitForCompletion(recorded: ConcurrentLinkedQueue[Signal], timeout: FiniteDuration): Task[Unit] = {
    val deadline = System.currentTimeMillis() + timeout.toMillis
    def loop: Task[Unit] = Task.defer {
      import scala.jdk.CollectionConverters.*
      val seen = recorded.iterator().asScala.exists(_.isInstanceOf[WorkflowRunCompleted])
      if (seen || System.currentTimeMillis() > deadline) Task.unit
      else Task.sleep(100.millis).flatMap(_ => loop)
    }
    loop
  }

  "tear down" should {
    "dispose TestWorkflowSigil" in TestWorkflowSigil.shutdown.map(_ => succeed)
  }
}

case object WorkflowTestUser extends AgentParticipantId {
  override def value: String = "workflow-test-user"
}

object WorkflowTestTopic {
  val id: Id[Topic] = Id("workflow-test-topic")
  val label: String = "Workflow Test"
  val summary: String = "Synthetic topic for the workflow E2E spec."
}

/** Minimal `WorkflowSigil` instance for the integration test —
  * uses Sigil's default DB layout under a per-suite path, no
  * real provider, no participants list except the test user. */
object TestWorkflowSigil extends Sigil with WorkflowSigil {
  override type DB = TestWorkflowDB

  override protected def buildDB(directory: Option[java.nio.file.Path],
                                 storeManager: lightdb.store.CollectionManager,
                                 appUpgrades: List[lightdb.upgrade.DatabaseUpgrade]): TestWorkflowDB =
    new TestWorkflowDB(directory, storeManager, appUpgrades)

  override def testMode: Boolean = true

  /** Sigil #277 — tests don't need the OpenRouter catalog; rely on
    * synthetic / provider-merged models. Matches `TestSigil`. */
  override def loadOpenRouterModels: Boolean = false

  override protected def participantIds: List[RW[? <: ParticipantId]] =
    List(RW.static(WorkflowTestUser))

  /** A trivial provider that emits one text chunk + Done so any agent turn
    * settles cleanly — notably the scheduling agent woken on workflow terminal
    * (#390). The worker integration spec overrides via `setProvider` to plug in
    * llama.cpp. */
  private object TrivialProvider extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: Sigil = TestWorkflowSigil
    override def httpRequestFor(input: ProviderCall): Task[spice.http.HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] =
      Stream.emits(List[ProviderEvent](
        ProviderEvent.ContentBlockDelta(CallId("wf-trivial"), "ok"),
        ProviderEvent.Done(StopReason.Complete)))
  }

  /** Mutable provider hook — defaults to [[TrivialProvider]] so a woken agent
    * turn settles. The worker integration spec calls
    * `setProvider(Task.pure(realProvider))` to plug in llama.cpp. */
  private val providerRef = new java.util.concurrent.atomic.AtomicReference[() => Task[Provider]](
    () => Task.pure(TrivialProvider)
  )

  def setProvider(p: => Task[Provider]): Unit = providerRef.set(() => p)

  /** Restore the default trivial provider (specs that swap in a capturing /
    * real provider call this to avoid leaking it into later tests). */
  def resetProvider(): Unit = providerRef.set(() => Task.pure(TrivialProvider))

  /** The synthetic model the E2E participants reference, registered so a woken
    * scheduling agent resolves it instead of raising UnregisteredModelException. */
  val testModelId: Id[Model] = Model.id("test", "model")
  private def registerTestModel(): Unit = {
    val now = lightdb.time.Timestamp()
    cache.merge(List(Model(
      canonicalSlug = testModelId.value, huggingFaceId = "", name = testModelId.value,
      description = "Synthetic model for the workflow E2E spec.", contextLength = 32768L,
      architecture = ModelArchitecture("text->text", List("text"), List("text"), "GPT", None),
      pricing = ModelPricing(prompt = BigDecimal(0), completion = BigDecimal(0), webSearch = None, inputCacheRead = None),
      topProvider = ModelTopProvider(contextLength = Some(32768L), maxCompletionTokens = Some(8192L), isModerated = false),
      perRequestLimits = None,
      supportedParameters = Set("temperature", "max_tokens", "top_p", "tools", "tool_choice"),
      knowledgeCutoff = None, expirationDate = None, links = ModelLinks(details = ""),
      created = now, _id = testModelId
    ))).sync()
  }

  override def modelResolver: sigil.provider.ModelResolver = (modelId: Id[Model]) =>
    cache.find(modelId).map(sigil.provider.ProviderModel(providerRef.get()().sync(), _))

  /** Test-only tool surface — adds [[EchoBackTool]] (used by the
    * worker tool-dispatch coverage in [[LlamaCppWorkerSpec]]) and
    * [[FailingTool]] (worker error-handling coverage) on top of the
    * standard roster. The worker-delegation bridge tools
    * (`delegate_task`, `relay_message`, sigil #327) are added so the
    * delegation specs can resolve them by name. Other specs that don't
    * touch these ignore them. */
  override def staticTools: List[sigil.tool.Tool] =
    super.staticTools ++ List(
      EchoBackTool,
      FailingTool,
      DiscoveryProbeTool,
      sigil.tool.util.DelegateTaskTool,
      sigil.tool.util.RelayMessageTool
    )

  /** Per-suite init: wipes any prior DB at the per-suite path, points
    * `sigil.dbPath` at it, and forces the Sigil instance to start so
    * the workflow manager + Strider engine wire up before tests run. */
  def initFor(testClassName: String): Unit = {
    val name = testClassName.replace("$", "")
    val dbPath = java.nio.file.Path.of("db", "test", name)
    deleteRecursive(dbPath)
    profig.Profig.merge(fabric.obj("sigil" -> fabric.obj("dbPath" -> fabric.str(dbPath.toString))))
    instance.sync()
    registerTestModel()
    ()
  }

  private def deleteRecursive(path: java.nio.file.Path): Unit = {
    if (java.nio.file.Files.exists(path)) {
      import scala.jdk.CollectionConverters.*
      if (java.nio.file.Files.isDirectory(path)) {
        java.nio.file.Files.list(path).iterator().asScala.foreach(deleteRecursive)
      }
      java.nio.file.Files.delete(path)
    }
  }
}

class TestWorkflowDB(directory: Option[java.nio.file.Path],
                     storeManager: lightdb.store.CollectionManager,
                     upgrades: List[lightdb.upgrade.DatabaseUpgrade] = Nil)
  extends SigilDB(directory, storeManager, upgrades)
  with WorkflowCollections
