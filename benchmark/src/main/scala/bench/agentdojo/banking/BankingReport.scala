package bench.agentdojo.banking

import java.io.{File, PrintWriter}

/**
 * Markdown + console reporting for the banking-suite scoring matrix.
 */
object BankingReport {

  /** Headline metrics extracted from a flat list of cell results. */
  final case class Summary(model: String,
                           includeBaseline: Boolean,
                           userTaskCount: Int,
                           injectionTaskCount: Int,
                           baselineUtilityRate: Double,    // utility on (user × no-injection)
                           injectedUtilityRate: Double,    // utility on (user × injection) — drop is the cost of attacks
                           tasr: Double,                   // attack success rate on (user × injection)
                           defenseRate: Double,            // 1 − tasr
                           errorCount: Int,
                           totalCells: Int)

  def summarize(results: List[BankingScenarioResult], model: String, includeBaseline: Boolean): Summary = {
    val (baseline, attacks) = results.partition(_.injectionTaskId.isEmpty)
    val baselineGood = baseline.filterNot(_.error.isDefined)
    val attacksGood = attacks.filterNot(_.error.isDefined)
    def rate(rs: List[BankingScenarioResult], p: BankingScenarioResult => Boolean): Double =
      if (rs.isEmpty) 0.0 else rs.count(p).toDouble / rs.size
    val errors = results.count(_.error.isDefined)
    val userIds = results.map(_.userTaskId).toSet
    val injIds = attacks.flatMap(_.injectionTaskId).toSet
    Summary(
      model = model,
      includeBaseline = includeBaseline,
      userTaskCount = userIds.size,
      injectionTaskCount = injIds.size,
      baselineUtilityRate = rate(baselineGood, _.utilityPassed),
      injectedUtilityRate = rate(attacksGood, _.utilityPassed),
      tasr = rate(attacksGood, _.securityCompromised),
      defenseRate = 1.0 - rate(attacksGood, _.securityCompromised),
      errorCount = errors,
      totalCells = results.size
    )
  }

  def consoleSummary(s: Summary): String = {
    val lines = List(
      s"=== AgentDojo banking — ${s.model} ===",
      s"User tasks: ${s.userTaskCount}    Injection tasks: ${s.injectionTaskCount}    Total cells: ${s.totalCells}    Errors: ${s.errorCount}",
      f"Baseline utility (no attack):   ${s.baselineUtilityRate * 100}%.1f%%",
      f"Injected  utility (with attack): ${s.injectedUtilityRate * 100}%.1f%%",
      f"TASR (attack success):           ${s.tasr * 100}%.1f%%",
      f"Defense rate (1 − TASR):         ${s.defenseRate * 100}%.1f%%"
    )
    lines.mkString("\n")
  }

  def writeMarkdown(path: String, s: Summary, results: List[BankingScenarioResult]): Unit = {
    val parent = new File(path).getParentFile
    if (parent != null && !parent.exists()) parent.mkdirs()
    val w = new PrintWriter(path)
    try {
      w.println(s"# AgentDojo banking — ${s.model}")
      w.println()
      w.println("AgentDojo `important_instructions` attack against the banking suite (v1_2 task definitions). Pinned per-cell scoring against the persisted post-environment.")
      w.println()
      w.println("## Headline")
      w.println()
      w.println(s"| Metric | Score |")
      w.println(s"|---|---|")
      w.println(f"| Baseline utility (no attack)   | ${s.baselineUtilityRate * 100}%.1f%% (${s.userTaskCount} user tasks) |")
      w.println(f"| Injected utility (with attack) | ${s.injectedUtilityRate * 100}%.1f%% (${s.userTaskCount} × ${s.injectionTaskCount} cells) |")
      w.println(f"| TASR (attack success rate)     | ${s.tasr * 100}%.1f%% |")
      w.println(f"| Defense rate (1 − TASR)        | ${s.defenseRate * 100}%.1f%% |")
      w.println(f"| Errors / total cells           | ${s.errorCount}/${s.totalCells} |")
      w.println()
      w.println("## Per-cell results")
      w.println()
      w.println("Cell key: `u<userId>/<scenario>` where `<scenario>` is `baseline` or `i<injectionId>`. `U` = utility pass, `S` = security compromised, `E` = error.")
      w.println()
      w.println("| User | Scenario | U | S | E |")
      w.println("|---|---|---|---|---|")
      results.foreach { r =>
        val scenario = r.injectionTaskId.fold("baseline")(i => s"i$i")
        val u = if (r.error.isDefined) "—" else if (r.utilityPassed) "✓" else " "
        val sc = if (r.error.isDefined) "—" else if (r.securityCompromised) "✗" else " "
        val e = if (r.error.isDefined) "✓" else " "
        w.println(s"| u${r.userTaskId} | $scenario | $u | $sc | $e |")
      }
      w.println()
      w.println("## Errors (truncated to first 20)")
      w.println()
      val errs = results.collect { case r if r.error.isDefined => r }.take(20)
      if (errs.isEmpty) w.println("_None._")
      else errs.foreach { r =>
        w.println(s"- u${r.userTaskId}/${r.injectionTaskId.fold("baseline")(i => s"i$i")} — `${r.error.getOrElse("?")}`")
      }
    } finally w.close()
  }
}
