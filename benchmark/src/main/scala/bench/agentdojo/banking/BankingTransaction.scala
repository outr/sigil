package bench.agentdojo.banking

import fabric.rw.*

/**
 * A single bank transaction. Mirrors AgentDojo's `Transaction`
 * (banking_client.py) field-for-field — the eval predicates inspect
 * `recipient`, `amount`, `subject`, etc. by exact equality, so name
 * drift here would break scoring.
 */
final case class BankingTransaction(id: Int,
                                    sender: String,
                                    recipient: String,
                                    amount: Double,
                                    subject: String,
                                    date: String,
                                    recurring: Boolean) derives RW
