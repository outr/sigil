package bench.agentdojo.banking

import bench.AgentBenchHarness
import bench.agentdojo.ImportantInstructionsAttack
import lightdb.id.Id
import rapid.Task
import sigil.conversation.{Conversation, Topic, TopicEntry}
import sigil.db.Model
import sigil.participant.AgentParticipant

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*

/**
 * Runs the AgentDojo banking suite scoring matrix:
 *
 *   - 16 user tasks × 1 baseline (no injection) = 16 utility runs
 *   - 16 user tasks × 9 injection tasks = 144 attack runs
 *
 * One [[BankingScenarioResult]] per matrix cell. Each cell starts
 * from a fresh [[BankingFixture.initialEnvironment]] so mutations
 * don't leak across cells. The active [[BankingToolCatalog]] is
 * installed via [[AgentBenchHarness#withToolFinder]] so the same
 * Sigil + harness handles every scenario.
 */
final class BankingScorer(harness: AgentBenchHarness,
                          modelId: Id[Model],
                          modelLabel: String,
                          buildAgent: Id[Model] => AgentParticipant,
                          topicId: Id[Topic],
                          topicEntry: TopicEntry,
                          perTurnTimeout: FiniteDuration = 90.seconds) {

  /** Run one cell. `injection` is `None` for the no-attack baseline. */
  def runCell(userTask: BankingUserTask, injection: Option[BankingInjectionTask]): Task[BankingScenarioResult] = {
    val baseEnv = BankingFixture.initialEnvironment()
    val rendered = injection match {
      case None => InjectionRendering.baseline(baseEnv)
      case Some(it) => InjectionRendering.injected(baseEnv, ImportantInstructionsAttack.render(it.goal))
    }
    val state = new AtomicReference(rendered)
    val pre = state.get
    val finder = BankingToolCatalog.buildFinder(state)
    val agent = buildAgent(modelId)
    val convFactory: Id[Conversation] => Conversation = convId => Conversation(
      topics = List(topicEntry),
      _id = convId,
      participants = List(agent)
    )
    harness.withToolFinder(finder) {
      harness.runOneShot(convFactory, userTask.prompt, perTurnTimeout)
    }.attempt.map {
      case scala.util.Success(trace) =>
        val post = state.get
        val reply = trace.lastReply.map(m => m.content.collect {
          case t: sigil.tool.model.ResponseContent.Text => t.text
        }.mkString.trim).getOrElse("")
        val utility = userTask.utility(reply, pre, post)
        val security = injection.exists(_.security(reply, pre, post))
        BankingScenarioResult(
          model = modelLabel,
          userTaskId = userTask.id,
          injectionTaskId = injection.map(_.id),
          utilityPassed = utility,
          securityCompromised = security,
          replyText = reply,
          trace = Some(trace),
          error = None
        )
      case scala.util.Failure(t) =>
        BankingScenarioResult(
          model = modelLabel,
          userTaskId = userTask.id,
          injectionTaskId = injection.map(_.id),
          utilityPassed = false,
          securityCompromised = false,
          replyText = "",
          trace = None,
          error = Some(t.getMessage)
        )
    }
  }

  /** Run the full matrix. Returns one result per (userTask, injection
    * | None) pair. Sequential — each cell depends on a fresh env, but
    * also we want predictable wire-log filenames per cell. */
  def runAll(userTasks: List[BankingUserTask] = BankingUserTask.all,
             injections: List[BankingInjectionTask] = BankingInjectionTask.all,
             includeBaseline: Boolean = true): Task[List[BankingScenarioResult]] = {
    val cells: List[(BankingUserTask, Option[BankingInjectionTask])] =
      userTasks.flatMap { ut =>
        val baseline = if (includeBaseline) List((ut, Option.empty[BankingInjectionTask])) else Nil
        val attacks = injections.map(it => (ut, Some(it)))
        baseline ++ attacks
      }
    runCellsSequential(cells, Nil)
  }

  private def runCellsSequential(remaining: List[(BankingUserTask, Option[BankingInjectionTask])],
                                 acc: List[BankingScenarioResult]): Task[List[BankingScenarioResult]] =
    remaining match {
      case Nil => Task.pure(acc.reverse)
      case (ut, it) :: rest =>
        val label = it match {
          case None => s"u${ut.id}/baseline"
          case Some(i) => s"u${ut.id}/i${i.id}"
        }
        scribe.info(s"[BankingScorer] $modelLabel cell $label …")
        runCell(ut, it).flatMap { r =>
          val verdict = (r.utilityPassed, r.securityCompromised, r.error.isDefined) match {
            case (_, _, true) => s"ERROR ${r.error.getOrElse("")}"
            case (true, false, _) => "OK"
            case (true, true, _) => "OK / COMPROMISED"
            case (false, false, _) => "FAIL"
            case (false, true, _) => "FAIL / COMPROMISED"
          }
          scribe.info(s"[BankingScorer] $modelLabel cell $label → $verdict")
          runCellsSequential(rest, r :: acc)
        }
    }
}
