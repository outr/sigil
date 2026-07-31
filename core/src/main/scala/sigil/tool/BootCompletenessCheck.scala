package sigil.tool

import fabric.rw.RW

/**
 * Startup verification of the registered tool roster, run at the end
 * of `Sigil.polymorphicRegistrations` (tool list + registered RWs
 * only — no store access, so codegen-only flows run it too):
 *
 *   - every tool's probe input AND output round-trips through the
 *     POLYMORPHIC `RW[ToolInput]` / `RW[ToolOutput]` (authored
 *     examples where present, synthesized from the definition
 *     otherwise) — a forgotten registration fails startup naming the
 *     type instead of crashing at first wire render or persistence;
 *   - tool names are roster-wide unique;
 *   - every `suggestedNextTools` reference resolves against the
 *     registered set.
 *
 * All violations are collected and raised as one
 * [[ToolRegistrationException]]. The unknown/orphan fallback types
 * remain downstream — as guards for genuinely-removed tools, not as
 * the primary safety net.
 */
object BootCompletenessCheck {

  /** Run the full pass. `secondRead` re-invokes the app's
    * `staticTools` override once so a structurally different second
    * result (the documented fresh-state footgun class) is surfaced as
    * a loud warning naming the difference. */
  def run(tools: List[Tool], secondRead: => List[Tool]): Unit = {
    warnOnStructuralDrift(tools, secondRead)
    val violations = collectViolations(tools)
    if (violations.nonEmpty) throw new ToolRegistrationException(violations)
  }

  private def warnOnStructuralDrift(first: List[Tool], second: List[Tool]): Unit = {
    val a = first.map(_.name.value)
    val b = second.map(_.name.value)
    if (a != b) {
      val onlyFirst = a.diff(b)
      val onlySecond = b.diff(a)
      val detail =
        (if (onlyFirst.nonEmpty) s" only-in-first-read: ${onlyFirst.mkString(", ")};" else "") +
          (if (onlySecond.nonEmpty) s" only-in-second-read: ${onlySecond.mkString(", ")};" else "") +
          (if (onlyFirst.isEmpty && onlySecond.isEmpty) s" same names, different order: first=${a.mkString(", ")} second=${b.mkString(", ")}" else "")
      scribe.warn(
        "staticTools returned a structurally different list on a second invocation — the override must be a " +
          s"stable value (hoist stateful construction to a lazy val).$detail The first read is memoized and used everywhere."
      )
    }
  }

  /** Collect every violation without throwing — the testable core. */
  def collectViolations(tools: List[Tool]): List[String] = {
    val names = tools.map(_.name.value)
    // Re-listing the SAME tool value twice (super.staticTools already
    // carried it) is benign — registration and the sync upgrade dedupe
    // by identity. Two DIFFERENT tools sharing a name is the real
    // collision: by-name resolution silently picks one.
    val duplicates = tools.groupBy(_.name.value).collect {
      case (n, occurrences) if occurrences.distinct.size > 1 => n
    }.toList.sorted
    val duplicateViolations = duplicates.map(n => s"duplicate tool name '$n' in the registered roster")
    val nameSet = names.toSet
    val danglingSuggestions = tools.flatMap { t =>
      t.suggestedNextTools.collect {
        case suggested if !nameSet.contains(suggested.value) =>
          s"tool '${t.name.value}' declares suggestedNextTools '${suggested.value}', which does not resolve " +
            "against the registered tool set"
      }
    }
    val ioViolations = tools.flatMap(probeRoundTrip)
    duplicateViolations ++ danglingSuggestions ++ ioViolations
  }

  private def probeRoundTrip(tool: Tool): List[String] =
    probeInput(tool) ++ probeOutput(tool)

  private def probeInput(tool: Tool): List[String] = {
    val inputClass = tool.inputRW.definition.className.getOrElse(tool.inputRW.definition.defType.toString)
    try {
      val typed: ToolInput = tool.examples.headOption.map(_.input).getOrElse {
        val synthesized = tool.wireSurface.normalize(WireSurface.synthesizeExample(tool.inputDefinition))
        tool.inputRW.write(synthesized)
      }
      val polyJson = summon[RW[ToolInput]].read(typed)
      summon[RW[ToolInput]].write(polyJson)
      Nil
    } catch {
      case t: Throwable =>
        List(s"tool '${tool.name.value}' input $inputClass failed the polymorphic RW[ToolInput] round-trip: " +
          s"${t.getClass.getSimpleName}: ${t.getMessage}")
    }
  }

  private def probeOutput(tool: Tool): List[String] = {
    val basePolyClass = summon[RW[ToolOutput]].definition.className
    val outputDefinition = tool.outputRW.definition
    // A tool whose declared Output IS the open ToolOutput (an MCP-style
    // result that may be text OR an image) carries the base poly RW —
    // there is no single concrete class to probe.
    if (outputDefinition.className == basePolyClass) Nil
    else {
      val outputClass = outputDefinition.className.getOrElse(outputDefinition.defType.toString)
      try {
        val synthesized = WireSurface.synthesizeExample(outputDefinition)
        val typed: ToolOutput = tool.outputRW.write(synthesized)
        val polyJson = summon[RW[ToolOutput]].read(typed)
        summon[RW[ToolOutput]].write(polyJson)
        Nil
      } catch {
        case t: Throwable =>
          List(s"tool '${tool.name.value}' output $outputClass failed the polymorphic RW[ToolOutput] round-trip: " +
            s"${t.getClass.getSimpleName}: ${t.getMessage}")
      }
    }
  }
}
