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
import sigil.provider.llamacpp.LlamaCppProvider
import sigil.provider.{GenerationSettings, Instructions, Provider}
import sigil.tool.core.CoreTools
import sigil.tool.model.ResponseContent
import spice.http.{HttpMethod, HttpRequest}

import java.nio.file.{Files, Paths}
import scala.concurrent.duration.*

/**
 * The same whole-loop probe against the local llama.cpp model, which is
 * free to run as many times as an answer needs. Two shapes: one round
 * of batched reads, and two rounds where the second batch follows a
 * settled first one.
 */
class ParallelBatchLlamaLoopSpec extends AnyWordSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  override def run(testName: Option[String], args: Args): Status =
    LiveProbe.requireSlowEnabled(this).getOrElse {
      LiveProbe.runGatedProbe(
        this,
        c => s"llama.cpp host unreachable ($c)",
        HttpRequest(method = HttpMethod.Get, url = host.withPath("/v1/models")))(super.run(testName, args))
    }

  private lazy val host = TestSigil.llamaCppHost
  private lazy val provider: Provider = LlamaCppProvider(host, Nil, TestSigil)
  private lazy val modelId: Id[Model] = LiveLlamaModel.resolve(TestSigil, host)

  private val runs = sys.env.get("SIGIL_REISSUE_RUNS").flatMap(_.toIntOption).getOrElse(4)
  private val outDir = Paths.get("target", "reissue-llama")

  private def agent = DefaultAgentParticipant(
    id = TestAgent,
    modelId = modelId,
    toolNames = ProbeReadTool.name :: CoreTools.coreToolNames,
    instructions = Instructions(),
    generationSettings = GenerationSettings(maxOutputTokens = Some(2048), temperature = Some(0.0))
  )

  private def invokeTrail(convId: Id[Conversation]): List[(String, String)] =
    TestSigil.withDB(_.events.transaction(_.list)).sync()
      .filter(_.conversationId == convId)
      .collect { case ti: ToolInvoke => ti }
      .sortBy(_.timestamp.value)
      .map(ti => ti.toolName.value -> ti.input.map(_.json.toString).getOrElse(""))

  private def runOnce(index: Int, prompt: String): List[(String, String)] = {
    TestSigil.setProvider(Task.pure(provider))
    TestSigil.setMaxAgentIterations(12)
    val convId = Conversation.id(s"llama-batch-$index-${rapid.Unique()}")
    val conv = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId)
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

  private def arm(label: String, prompt: String): Unit = {
    Files.createDirectories(outDir)
    val report = StringBuilder()
    val counts = (1 to runs).map { i =>
      val trail = runOnce(i, prompt)
      val probes = trail.filter(_._1 == ProbeReadTool.name.value).map(_._2)
      val duplicates = probes.size - probes.distinct.size
      report.append(s"run $i: ${trail.map { case (n, a) => s"$n$a" }.mkString("\n        ")}\n")
      report.append(s"run $i: ${probes.size} invokes, ${probes.distinct.size} distinct, $duplicates repeated\n\n")
      info(s"[$label] run $i: ${probes.size} probe_read invokes, ${probes.distinct.size} distinct, $duplicates repeated")
      duplicates
    }
    Files.writeString(outDir.resolve(s"$label-trails.txt"), report.toString)
    info(s"[$label] repeated invokes per run: ${counts.mkString(", ")}")
    withClue(s"a settled probe was re-issued\n$report\n") {
      counts.sum shouldBe 0
    }
  }

  "the local model, driving the whole loop live" should {

    "not re-issue a probe it already has the result for" in
      arm(
        "single-round",
        "Read the probes named 'alpha', 'bravo' and 'charlie' using probe_read, " +
          "then tell me all three values in one reply.")

    "not re-issue across two rounds of batched reads" in
      arm(
        "two-round",
        "First read the probes named 'alpha' and 'bravo'. Then read the probes named " +
          "'charlie' and 'delta'. Finally summarize all four values in one reply."
      )
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
