package spec

import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{Conversation, ConversationView, TurnInput}
import sigil.script.ScriptTools
import sigil.tool.{InMemoryToolFinder, ToolExample, ToolInput, ToolName, TypedOutputTool}

class ScriptToolsSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  ScriptToolsTestSigil.initFor(getClass.getSimpleName)

  private val convId = Conversation.id("script-tools-spec-conv")

  private val testTopic = sigil.conversation.TopicEntry(
    sigil.conversation.Topic.id("script-tools-spec-topic"),
    "script-tools",
    "test topic"
  )

  private def turnCtx: TurnContext = TurnContext(
    sigil            = ScriptToolsTestSigil,
    chain            = List(TestScriptUser),
    conversation     = Conversation(topics = List(testTopic), _id = convId),
    conversationView = ConversationView(conversationId = convId),
    turnInput        = TurnInput(ConversationView(conversationId = convId))
  )

  "ScriptTools" should {
    "decode a typed-output tool's result via callTool[Out]" in {
      val helper = new ScriptTools(turnCtx)
      Task {
        val out = helper.callTool[EchoOutput]("echo", EchoInput("marker-42"))
        out.echoed shouldBe "marker-42"
        out.length shouldBe 9
      }
    }

    "expose the raw fabric Json via callToolJson when callers want untyped" in {
      val helper = new ScriptTools(turnCtx)
      Task {
        val json = helper.callToolJson("echo", EchoInput("hello"))
        json.get("echoed").map(_.asString) shouldBe Some("hello")
        json.get("length").map(_.asLong) shouldBe Some(5L)
      }
    }

    "report registration via has(name)" in {
      val helper = new ScriptTools(turnCtx)
      Task {
        helper.has("echo") shouldBe true
        helper.has("does_not_exist") shouldBe false
      }
    }

    "raise on an unregistered tool name" in {
      val helper = new ScriptTools(turnCtx)
      val attempt = Task(helper.callTool[EchoOutput]("nope", EchoInput("x"))).attempt
      attempt.map { result =>
        result.isFailure shouldBe true
        result.failed.get.getMessage should include("nope")
      }
    }

    "be invocable from an actual ExecuteScriptTool script via the default `tools` binding" in {
      // End-to-end: ScalaScriptExecutor evaluates a script that calls
      // tools.callToolJson("echo", ...) and returns the echoed text.
      // EchoInput / EchoOutput aren't in `sigil.tool.model.*`, so the
      // script imports the test package directly.
      val executor = new sigil.script.ScalaScriptExecutor
      val tool = new sigil.script.ExecuteScriptTool(executor)
      val script =
        """import spec.{EchoInput, EchoOutput}
          |val out = tools.callTool[EchoOutput]("echo", EchoInput("from-script"))
          |s"${out.echoed}/${out.length}"
          |""".stripMargin
      tool.execute(sigil.script.ScriptInput(code = script), turnCtx).toList.map { events =>
        val resultText = events.collectFirst {
          case r: sigil.script.ScriptResult => r.output.getOrElse(r.error.getOrElse(""))
        }.getOrElse(fail("script produced no ScriptResult"))
        resultText shouldBe "from-script/11"
      }
    }
  }

  "tear down" should {
    "dispose ScriptToolsTestSigil" in ScriptToolsTestSigil.shutdown.map(_ => succeed)
  }
}

case class EchoInput(text: String) extends ToolInput derives RW
case class EchoOutput(echoed: String, length: Int) derives RW

case object EchoTool extends TypedOutputTool[EchoInput, EchoOutput](
  name = ToolName("echo"),
  description = "Echo the input text back with its length.",
  examples = List(ToolExample("echo a string", EchoInput("hello")))
) {
  override protected def executeTyped(input: EchoInput, ctx: TurnContext): Task[EchoOutput] =
    Task.pure(EchoOutput(echoed = input.text, length = input.text.length))
}

object ScriptToolsTestSigil
  extends sigil.Sigil
  with sigil.script.ScriptSigil {
  override type DB = sigil.db.DefaultSigilDB
  override protected def buildDB(directory: Option[java.nio.file.Path],
                                  storeManager: lightdb.store.CollectionManager,
                                  upgrades: List[lightdb.upgrade.DatabaseUpgrade]): sigil.db.DefaultSigilDB =
    new sigil.db.DefaultSigilDB(directory, storeManager, upgrades)
  override def testMode: Boolean = true
  override protected def participantIds: List[RW[? <: sigil.participant.ParticipantId]] =
    List(RW.static(TestScriptUser))
  override val findTools: sigil.tool.ToolFinder = InMemoryToolFinder(List(EchoTool))
  override def staticTools: List[sigil.tool.Tool] = Nil
  override def curate(view: ConversationView,
                      modelId: lightdb.id.Id[sigil.db.Model],
                      chain: List[sigil.participant.ParticipantId]): Task[TurnInput] = Task.pure(TurnInput(view))
  override def getInformation(id: lightdb.id.Id[sigil.information.Information]): Task[Option[sigil.information.Information]] = Task.pure(None)
  override def putInformation(information: sigil.information.Information): Task[Unit] = Task.unit
  override def compressionMemorySpace(conversationId: lightdb.id.Id[Conversation]): Task[Option[sigil.SpaceId]] = Task.pure(None)
  override def providerFor(modelId: lightdb.id.Id[sigil.db.Model],
                            chain: List[sigil.participant.ParticipantId]): Task[sigil.provider.Provider] =
    Task.error(new RuntimeException("ScriptToolsTestSigil: no provider configured"))
  override val embeddingProvider: sigil.embedding.EmbeddingProvider = sigil.embedding.NoOpEmbeddingProvider
  override val vectorIndex: sigil.vector.VectorIndex = sigil.vector.NoOpVectorIndex

  def initFor(testClassName: String): Unit = {
    val name = testClassName.replace("$", "")
    val dbPath = java.nio.file.Path.of("db", "test", name)
    deleteRecursive(dbPath)
    profig.Profig.merge(fabric.obj("sigil" -> fabric.obj("dbPath" -> fabric.str(dbPath.toString))))
    instance.sync()
    ()
  }

  private def deleteRecursive(path: java.nio.file.Path): Unit = {
    if (java.nio.file.Files.exists(path)) {
      val s = java.nio.file.Files.walk(path)
      try {
        import scala.jdk.CollectionConverters.*
        s.iterator().asScala.toList.reverse.foreach(p => java.nio.file.Files.deleteIfExists(p))
      } finally s.close()
    }
  }
}
