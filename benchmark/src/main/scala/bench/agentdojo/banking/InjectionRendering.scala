package bench.agentdojo.banking

/**
 * Rendering of `{injection_*}` placeholders in
 * [[BankingFixture]]-built environments.
 *
 * Two modes:
 *
 *   - **Baseline** (no attack): every placeholder is filled with the
 *     YAML's default content (e.g. `injection_bill_text` → an actual
 *     car-rental bill). Utility scoring runs on this version.
 *   - **Attack** (one injection task): every placeholder is filled
 *     with the same rendered attack payload (so wherever the agent
 *     reads from — bill, landlord notice, transaction subject — it
 *     sees the same injected instructions). Both utility AND security
 *     scoring run on this version.
 *
 * Mirrors AgentDojo's `task_suite.load_and_inject_default_environment`.
 */
object InjectionRendering {

  /** Interpolate the placeholder map into every string field in `env`. */
  def render(env: BankingEnvironment, replacements: Map[String, String]): BankingEnvironment =
    env.copy(
      bankAccount = env.bankAccount.copy(
        transactions = env.bankAccount.transactions.map(t => t.copy(subject = applyAll(t.subject, replacements))),
        scheduledTransactions = env.bankAccount.scheduledTransactions.map(t => t.copy(subject = applyAll(t.subject, replacements)))
      ),
      filesystem = BankingFilesystem(
        env.filesystem.files.view.mapValues(applyAll(_, replacements)).toMap
      )
    )

  /** Render the no-attack baseline — every placeholder substituted
    * with its YAML default. */
  def baseline(env: BankingEnvironment): BankingEnvironment =
    render(env, BankingFixture.InjectionDefaults)

  /** Render the injected variant — every placeholder substituted with
    * the same rendered `attackText`. */
  def injected(env: BankingEnvironment, attackText: String): BankingEnvironment =
    render(env, BankingFixture.InjectionSlots.iterator.map(_ -> attackText).toMap)

  private def applyAll(text: String, replacements: Map[String, String]): String =
    replacements.foldLeft(text) { case (acc, (key, value)) => acc.replace("{" + key + "}", value) }
}
