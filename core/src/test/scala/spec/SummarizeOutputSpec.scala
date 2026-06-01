package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.TurnContext
import sigil.conversation.{Conversation, ConversationView, TopicEntry, TurnInput}
import sigil.event.Event
import sigil.provider.{CallId, Provider, ProviderCall, ProviderEvent, ProviderType, StopReason}
import sigil.signal.{Signal, ToolDelta}
import sigil.tool.consult.SummarizationInput
import sigil.tool.fs.{FileSystemContext, GrepTool, LocalFileSystemContext, WriteFileTool}
import sigil.tool.model.{GrepInput, WriteFileInput}
import sigil.tool.output.{JsonPagedResult, SummarizeOutputInput, SummarizeOutputTool, ToolOutputReference}
import sigil.tool.{TextToolOutput, ToolContext, ToolName, ToolResult}
import spice.http.HttpRequest

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/**
 * Sigil #336 — the bulk-output ladder's on-demand rung. `summarize_output`
 * takes a prior bulk result's reference and returns an LLM summary without
 * the full set entering the agent's context (it map-reduces the
 * materialized container itself). Also pins the read-path soundness the
 * whole ladder depends on: a grep container drained in a worker
 * sub-conversation is resolvable by reference — from that conversation and
 * from its parent — with rows intact (the failure mode #336 reported was
 * reads coming back empty).
 */
class SummarizeOutputSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  /** Stub that answers the summarization consult with a fixed summary. */
  private final class SummarizingProvider(summary: String) extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("stub"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val callId = CallId(s"sum-${rapid.Unique()}")
      Stream.emits(List(
        ProviderEvent.ToolCallStart(callId, "summarize_conversation"),
        ProviderEvent.ToolCallComplete(callId, SummarizationInput(summary = summary, tokenEstimate = 20)),
        ProviderEvent.Done(StopReason.Complete)
      ))
    }
  }

  private def withTempDir[T](body: (FileSystemContext, Path) => Task[T]): Task[T] = Task.defer {
    val dir = Files.createTempDirectory("sigil-summarize-")
    val ctx = new LocalFileSystemContext(Some(dir))
    body(ctx, dir).guarantee(Task {
      val s = Files.walk(dir)
      try s.iterator().asScala.toList.reverse.foreach(p => Files.deleteIfExists(p))
      finally s.close()
    })
  }

  private def turnContext(convId: Id[Conversation]): TurnContext =
    TurnContext(
      sigil        = TestSigil,
      chain        = List(TestUser),
      conversation = Conversation(topics = List(TopicEntry(TestTopicId, "t", "t")), _id = convId),
      turnInput    = TurnInput(ConversationView(conversationId = convId)),
      model        = TestSigil.defaultTestModel
    )

  private def toolCtx(turn: TurnContext): ToolContext =
    ToolContext(turn, Event.id(), ToolName("probe"))

  private def firstPage(signals: List[Signal]): JsonPagedResult =
    signals.collectFirst { case d: ToolDelta if d.output.exists(_.isInstanceOf[JsonPagedResult]) =>
      d.output.get.asInstanceOf[JsonPagedResult]
    }.getOrElse(throw new RuntimeException(s"no paged result in $signals"))

  private def seed(fs: FileSystemContext, ctx: TurnContext): Task[Unit] =
    Task.sequence(List("a.scala" -> "needle\nx", "b.scala" -> "needle\ny", "c.scala" -> "needle\nz").map {
      case (name, body) => new WriteFileTool(fs).execute(WriteFileInput(name, body), ctx, Event.id()).toList
    }).unit

  "summarize_output" should {
    "return an LLM summary of a bulk result by reference (full set never enters context)" in withTempDir { (fs, _) =>
      val convId = Conversation.id(s"summarize-${rapid.Unique()}")
      val ctx    = turnContext(convId)
      val grepCall = Event.id()
      TestSigil.setProvider(Task.pure(new SummarizingProvider("3 Scala files each contain 'needle'.")))
      for {
        _    <- seed(fs, ctx)
        gOut <- new GrepTool(fs).execute(GrepInput(path = ".", pattern = "needle"), ctx, grepCall).toList
        page  = firstPage(gOut)
        res  <- SummarizeOutputTool.executeResult(SummarizeOutputInput(reference = page.callId.value), toolCtx(ctx))
      } yield res match {
        case ToolResult.Success(out: TextToolOutput) =>
          // Count prefix from the templated wrapper + the stub's summary.
          out.text should include ("records")
          out.text should include ("needle")
        case other => fail(s"expected a summary, got $other")
      }
    }

    "fail recoverably on an unresolvable reference" in {
      val convId = Conversation.id(s"summarize-miss-${rapid.Unique()}")
      val ctx    = turnContext(convId)
      TestSigil.setProvider(Task.pure(new SummarizingProvider("unused")))
      SummarizeOutputTool.executeResult(SummarizeOutputInput(reference = "no-such-reference"), toolCtx(ctx)).map {
        case f: ToolResult.Failure => f.message should include ("not found")
        case other                 => fail(s"expected a failure, got $other")
      }
    }
  }

  "the bulk-output read path" should {
    "make a grep container drained in a worker sub-conversation resolvable by reference — from the worker AND its parent" in withTempDir { (fs, _) =>
      val parentId = Conversation.id(s"read-parent-${rapid.Unique()}")
      val workerId = Conversation.id(s"read-worker-${rapid.Unique()}")
      val workerCtx = TurnContext(
        sigil        = TestSigil,
        chain        = List(TestUser),
        conversation = Conversation(
          topics               = List(TopicEntry(TestTopicId, "t", "t")),
          parentConversationId = Some(parentId),
          _id                  = workerId
        ),
        turnInput = TurnInput(ConversationView(conversationId = workerId)),
        model     = TestSigil.defaultTestModel
      )
      val grepCall = Event.id()
      for {
        // Persist both conversations so canReadConversation can verify the
        // parent/worker link.
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(
               Conversation(topics = List(TopicEntry(TestTopicId, "t", "t")), _id = parentId))))
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(workerCtx.conversation)))
        _ <- seed(fs, workerCtx)
        _ <- new GrepTool(fs).execute(GrepInput(path = ".", pattern = "needle"), workerCtx, grepCall).toList
        // Resolve from the worker conversation itself.
        fromWorker <- ToolOutputReference.resolve(TestSigil, workerId, grepCall.value)
        // Resolve from the parent (canReadConversation allows reading a worker).
        fromParent <- ToolOutputReference.resolve(TestSigil, parentId, grepCall.value, Some(workerId))
      } yield {
        fromWorker.toOption.map(_.rows.nonEmpty) shouldBe Some(true)
        fromParent.toOption.map(_.rows.nonEmpty) shouldBe Some(true)
      }
    }

    "query_tool_output resolves the owning conversation from the callId alone — no conversationId, cross-conv from a parent (#339)" in withTempDir { (fs, _) =>
      val parentId = Conversation.id(s"qto-parent-${rapid.Unique()}")
      val workerId = Conversation.id(s"qto-worker-${rapid.Unique()}")
      val workerCtx = TurnContext(
        sigil = TestSigil, chain = List(TestUser),
        conversation = Conversation(topics = List(TopicEntry(TestTopicId, "t", "t")),
                                    parentConversationId = Some(parentId), _id = workerId),
        turnInput = TurnInput(ConversationView(conversationId = workerId)), model = TestSigil.defaultTestModel)
      val parentCtx = turnContext(parentId)
      val grepCall = Event.id()
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(
               Conversation(topics = List(TopicEntry(TestTopicId, "t", "t")), _id = parentId))))
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(workerCtx.conversation)))
        _ <- seed(fs, workerCtx)
        _ <- new GrepTool(fs).execute(GrepInput(path = ".", pattern = "needle"), workerCtx, grepCall).toList
        // From the PARENT, query the worker's grep by callId only — no
        // conversationId supplied; the framework resolves it.
        res <- sigil.tool.output.QueryToolOutputTool.invoke(
                 sigil.tool.output.QueryToolOutputInput(callId = grepCall.value, level = Some(0)),
                 toolCtx(parentCtx))
      } yield res.totalCount.getOrElse(0) should be > 0
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
