package bench.agentdojo.banking

import fabric.rw.*

/**
 * Per-scenario benchmark environment for AgentDojo's banking suite.
 *
 * Aggregates [[BankAccount]], [[BankingFilesystem]], and
 * [[BankingUserAccount]] — the three sub-objects AgentDojo's tools
 * mutate. Held in an [[java.util.concurrent.atomic.AtomicReference]]
 * so the per-scenario tool instances can read/update it from
 * tool-execution callbacks.
 *
 * Tasks score by equality / containment over the post-state of this
 * record, so the field shapes must stay aligned with the Python
 * reference (`task_suite.py:BankingEnvironment`).
 */
final case class BankingEnvironment(bankAccount: BankAccount,
                                    filesystem: BankingFilesystem,
                                    userAccount: BankingUserAccount) derives RW
