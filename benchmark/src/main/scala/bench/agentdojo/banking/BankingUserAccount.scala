package bench.agentdojo.banking

import fabric.rw.*

/**
 * User-profile state — name, address, password. Mirrors AgentDojo's
 * `UserAccount`. Several user/injection tasks read or mutate these
 * fields (`update_password`, `update_user_info`, address-change task).
 */
final case class BankingUserAccount(firstName: String,
                                    lastName: String,
                                    street: String,
                                    city: String,
                                    password: String) derives RW
