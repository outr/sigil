package bench.agentdojo.banking

import fabric.rw.*

/**
 * Bank account state — balance, IBAN, and the two transaction lists
 * (settled and scheduled). Mirrors AgentDojo's `BankAccount`.
 */
final case class BankAccount(balance: Double,
                             iban: String,
                             transactions: List[BankingTransaction],
                             scheduledTransactions: List[BankingTransaction]) derives RW {

  /** Next transaction id — mirrors `next_id` in banking_client.py. */
  def nextId: Int =
    (transactions ++ scheduledTransactions).map(_.id).maxOption.getOrElse(0) + 1
}
