package spec

import fabric.rw.Convertible
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{Args, Status}
import rapid.Task
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.{Message, ToolInvoke}
import sigil.participant.DefaultAgentParticipant
import sigil.provider.anthropic.AnthropicProvider
import sigil.provider.{GenerationSettings, Instructions, Provider}
import sigil.tool.ToolName
import sigil.tool.core.CoreTools
import sigil.tool.model.ResponseContent

import java.nio.file.{Files, Paths}
import scala.concurrent.duration.*

/**
 * The whole loop, live: the field model decides every iteration against
 * a task that invites reading several independent values at once. The
 * assertion is on the invoke trail — a call the model already holds the
 * result for must not appear twice. Two arms, since reaching a tool
 * through `find_capability` leaves a standing directive in the prompt
 * that the direct roster does not.
 */
class ParallelBatchLiveLoopSpec extends AnyWordSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val LiveModelSlug = sys.env.getOrElse("ANTHROPIC_TEST_MODEL", "claude-sonnet-5")

  override def run(testName: Option[String], args: Args): Status =
    AnthropicLiveSupport.runGatedForModel(this, testName, args, LiveModelSlug)(super.run(testName, args))

  private val modelId: Id[Model] = Model.id("anthropic", LiveModelSlug)
  TestSigil.testModel(modelId)

  private val runs = sys.env.get("SIGIL_REISSUE_RUNS").flatMap(_.toIntOption).getOrElse(4)
  private val outDir = Paths.get("target", "reissue-loop")

  private lazy val provider: Provider =
    AnthropicProvider(apiKey = AnthropicLiveSupport.apiKey.getOrElse("none"), sigilRef = TestSigil)

  private def agent(toolNames: List[ToolName]) = DefaultAgentParticipant(
    id = TestAgent,
    modelId = modelId,
    toolNames = toolNames,
    instructions = Instructions(),
    generationSettings = GenerationSettings(maxOutputTokens = Some(2048))
  )

  /** Every probe_read invoke of one conversation, in order, tagged with
    * the args it carried. */
  private def invokeTrail(convId: Id[Conversation]): List[(String, String)] =
    TestSigil.withDB(_.events.transaction(_.list)).sync()
      .filter(_.conversationId == convId)
      .collect { case ti: ToolInvoke => ti }
      .sortBy(_.timestamp.value)
      .map(ti => ti.toolName.value -> ti.input.map(_.json.toString).getOrElse(""))

  private def runOnce(label: String, index: Int, toolNames: List[ToolName], prompt: String): List[(String, String)] = {
    TestSigil.setProvider(Task.pure(provider))
    TestSigil.setMaxAgentIterations(12)
    val convId = Conversation.id(s"$label-$index-${rapid.Unique()}")
    val conv = Conversation(topics = TestTopicStack, participants = List(agent(toolNames)), _id = convId)
    val task = for {
      _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      _ <- TestSigil.publish(Message(
             participantId = TestUser,
             conversationId = convId,
             topicId = TestTopicEntry.id,
             content = Vector(ResponseContent.Text(prompt)),
             state = sigil.signal.EventState.Complete
           ))
      _ <- TestSigil.awaitSettled(convId, timeout = 300.seconds)
    } yield ()
    task.sync()
    invokeTrail(convId)
  }

  private def arm(label: String, toolNames: List[ToolName], prompt: String): Unit = {
    Files.createDirectories(outDir)
    val report = StringBuilder()
    val reissueCounts = (1 to runs).map { i =>
      val trail = runOnce(label, i, toolNames, prompt)
      val probes = trail.filter(_._1 == ProbeReadTool.name.value).map(_._2)
      val duplicates = probes.size - probes.distinct.size
      report.append(s"run $i: ${trail.map { case (n, a) => s"$n$a" }.mkString("\n        ")}\n")
      report.append(s"run $i: ${probes.size} probe_read invokes, ${probes.distinct.size} distinct, $duplicates repeated\n\n")
      info(s"[$label] run $i: ${probes.size} probe_read invokes, ${probes.distinct.size} distinct, $duplicates repeated")
      duplicates
    }
    Files.writeString(outDir.resolve(s"$label-trails.txt"), report.toString)
    info(s"[$label] repeated invokes per run: ${reissueCounts.mkString(", ")}")
    withClue(s"a settled probe was re-issued\n$report\n") {
      reissueCounts.sum shouldBe 0
    }
  }

  "the field model, driving the whole loop live" should {

    "not re-issue a probe it already has the result for" in {
      arm("direct", ProbeReadTool.name :: CoreTools.coreToolNames,
        "Read the probes named 'alpha', 'bravo' and 'charlie' using probe_read, " +
          "then tell me all three values in one reply.")
    }

    "not re-issue after discovering the tool through find_capability" in {
      // The discovery directive ("invoke it on THIS iteration") is
      // re-rendered on every iteration of the turn, including the one
      // that follows the batch it already produced.
      arm("discovered", CoreTools.coreToolNames,
        "Find a capability that reads probe values, then read the probes named " +
          "'alpha', 'bravo' and 'charlie' and tell me all three values in one reply.")
    }
  }

  "tear down" should {
    "dispose TestSigil" in {
      TestSigil.resetMaxAgentIterations()
      TestSigil.reset()
      TestSigil.shutdown.sync()
      succeed
    }
  }
}
