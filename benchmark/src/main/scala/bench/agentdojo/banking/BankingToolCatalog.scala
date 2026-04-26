package bench.agentdojo.banking

import bench.agentdojo.banking.tools.*
import fabric.rw.RW
import sigil.tool.{InMemoryToolFinder, Tool, ToolFinder, ToolInput, ToolName}

import java.util.concurrent.atomic.AtomicReference

/**
 * Builds the per-scenario list of banking tools, all closing over the
 * same [[BankingEnvironment]] reference.
 *
 * One catalog instance per scenario — each scenario starts from a
 * fresh `BankingFixture.initialEnvironment()` so mutations from one
 * task can't leak into the next. The harness installs the catalog's
 * [[ToolFinder]] via [[bench.AgentBenchHarness#withToolFinder]].
 */
object BankingToolCatalog {

  /** Tool names the catalog exposes — used by the AgentParticipant's
    * `toolNames` list so the agent dispatcher knows which tools to
    * advertise to the model. Order is the same as AgentDojo's
    * `task_suite.py:TOOLS`. */
  val toolNames: List[ToolName] = List(
    ToolName("get_iban"),
    ToolName("send_money"),
    ToolName("schedule_transaction"),
    ToolName("update_scheduled_transaction"),
    ToolName("get_balance"),
    ToolName("get_most_recent_transactions"),
    ToolName("get_scheduled_transactions"),
    ToolName("read_file"),
    ToolName("get_user_info"),
    ToolName("update_password"),
    ToolName("update_user_info")
  )

  /** Polymorphic-RW registrations for every banking tool's input
    * type. Passed to [[bench.BenchmarkAgentSigil]] at construction so
    * fabric registers the round-trip serializers once at init. */
  val toolInputRegistrations: List[RW[? <: ToolInput]] = List(
    summon[RW[GetIbanInput]],
    summon[RW[SendMoneyInput]],
    summon[RW[ScheduleTransactionInput]],
    summon[RW[UpdateScheduledTransactionInput]],
    summon[RW[GetBalanceInput]],
    summon[RW[GetMostRecentTransactionsInput]],
    summon[RW[GetScheduledTransactionsInput]],
    summon[RW[ReadFileInput]],
    summon[RW[GetUserInfoInput]],
    summon[RW[UpdatePasswordInput]],
    summon[RW[UpdateUserInfoInput]]
  )

  /** Build the 11 tool instances closing over `state`, plus the
    * matching [[ToolFinder]]. */
  def buildFinder(state: AtomicReference[BankingEnvironment]): ToolFinder =
    InMemoryToolFinder(buildTools(state))

  def buildTools(state: AtomicReference[BankingEnvironment]): List[Tool] = List(
    new GetIbanTool(state),
    new SendMoneyTool(state),
    new ScheduleTransactionTool(state),
    new UpdateScheduledTransactionTool(state),
    new GetBalanceTool(state),
    new GetMostRecentTransactionsTool(state),
    new GetScheduledTransactionsTool(state),
    new ReadFileTool(state),
    new GetUserInfoTool(state),
    new UpdatePasswordTool(state),
    new UpdateUserInfoTool(state)
  )
}
