package sigil.tool

import fabric.Json
import fabric.define.Definition
import fabric.io.{JsonFormatter, JsonParser}
import sigil.provider.{Reliability, SchemaDialect}

/**
 * Builds enriched [[ToolResult.Failure]] payloads for refusal paths so the
 * agent can retry correctly on the next iteration without re-discovering
 * the tool's shape.
 *
 * Every refusal payload should carry three things:
 *
 *   1. The specific rule violated (the caller-supplied `message`).
 *   2. The tool's canonical input schema, JSON-rendered inline. The
 *      offered roster's schema is in the system prompt today but a
 *      smaller / less-instruction-following model that fumbled the call
 *      once benefits from seeing the schema pinned alongside the failure.
 *   3. A worked example invocation. Drawn from the tool's
 *      [[Tool.examples]] field when present; otherwise synthesised from
 *      the schema's required fields with placeholder values matching each
 *      field's declared type. Either way the agent reads a syntactically-
 *      valid call shape next to the rejection.
 *
 * For hallucinated tool names, [[unknownTool]] additionally suggests the
 * closest match from the offered roster by edit distance and folds in
 * that tool's schema + example so the agent's next iteration can call the
 * intended tool directly.
 *
 * Schema and example are read from `tool.wireSurface`, projected
 * through the serving provider's [[SchemaDialect]] where the caller
 * has one (the orchestrator's arg-decode path) — the refusal shows the
 * schema the model was actually sent, not the canonical one with
 * constraints its grammar never saw. Callers without provider context
 * (tool-side logical refusals) use the [[SchemaDialect.Identity]]
 * default, which is the canonical schema.
 */
object RefusalPayload {

  /**
   * Render the tool's input schema as the serving dialect shaped it,
   * pretty-printed for inline display.
   */
  def schemaJson(tool: Tool, dialect: SchemaDialect = SchemaDialect.Identity): String =
    JsonFormatter.Default(dialect(tool))

  /**
   * Render a worked example invocation as JSON. Prefers an authored
   * example from [[Tool.examples]]; falls back to a synthesised
   * placeholder example.
   */
  def exampleJson(tool: Tool): String =
    JsonFormatter.Default(tool.wireSurface.example)

  /**
   * Build a synthetic example payload from a [[Definition]]. Picks one
   * placeholder value per primitive type, descends through objects,
   * arrays, and the first branch of polymorphic types. Strictly
   * best-effort: when the definition is `Json` or otherwise opaque, emits
   * a stub object the agent can fill in.
   *
   * Preserved for callers that have a `Definition` but no `Tool`.
   */
  def synthesizeExample(definition: Definition): Json =
    WireSurface.synthesizeExample(definition)

  /**
   * Build a refusal failure that carries the schema + example alongside
   * the rule. `hint` is an optional extra line rendered between the rule
   * and the schema block (e.g. `change_mode`'s "available modes: …").
   * `sentArgs` (the raw JSON the model emitted) is folded into the
   * `args` field of the underlying [[ToolResult.Failure]] when provided
   * so the agent can diff its emit against the worked example.
   */
  def schemaMismatch(tool: Tool,
                     rule: String,
                     hint: Option[String] = None,
                     sentArgs: Option[String] = None,
                     dialect: SchemaDialect = SchemaDialect.Identity,
                     reliability: Reliability = Reliability.Wobbly): ToolResult.Failure = {
    val body = buildBody(rule, tool, hint, dialect, reliability)
    ToolResult.Failure(message = body, hint = None, args = sentArgs)
  }

  /**
   * Build the unknown-tool refusal — names the missing tool, surfaces the
   * closest-name match from the offered roster, and folds in that
   * match's schema + example so the agent can call the intended tool
   * directly on its next iteration.
   */
  def unknownTool(invokedName: String,
                  offered: Iterable[Tool],
                  sentArgs: Option[String] = None,
                  carrier: Option[Tool] = None,
                  dialect: SchemaDialect = SchemaDialect.Identity): ToolResult.Failure = {
    val closest = closestMatch(invokedName, offered)
    val lead =
      s"Unknown tool '$invokedName'. The framework didn't dispatch this call because the name isn't " +
        "in this turn's available tool roster."
    val suggestion = closest match {
      case Some(t) =>
        s"\n\nClosest match in the offered roster: `${t.name.value}` — ${t.description}\n\n" +
          s"Schema for `${t.name.value}`:\n${schemaJson(t, dialect)}\n\n" +
          s"Example call for `${t.name.value}`:\n${exampleJson(t)}\n\n" +
          "If your intent was something else, call `find_capability` to discover the correct tool."
      case None =>
        "\n\nCall `find_capability` to discover the catalog, or call `respond` to tell the user what " +
          "you tried and what's missing."
    }
    val carrierBlock = carrier match {
      case Some(t) =>
        s"\n\nSchema for `${t.name.value}` (the tool you called):\n${schemaJson(t, dialect)}\n\n" +
          s"Example call for `${t.name.value}`:\n${exampleJson(t)}"
      case None => ""
    }
    ToolResult.Failure(message = lead + carrierBlock + suggestion, hint = None, args = sentArgs)
  }

  /**
   * Return the closest-name match by Levenshtein distance, ignoring
   * matches whose distance exceeds half the invoked name's length (no
   * suggestion is better than a wildly-different one). Ties broken by
   * first occurrence.
   */
  def closestMatch(invokedName: String, offered: Iterable[Tool]): Option[Tool] = {
    val candidates = offered.iterator.filterNot(_.name.value == invokedName).toList
    if (candidates.isEmpty) None
    else {
      val ranked = candidates.map(t => t -> levenshtein(invokedName, t.name.value))
      val (best, dist) = ranked.minBy(_._2)
      val maxAllowed = math.max(2, invokedName.length / 2)
      if (dist <= maxAllowed) Some(best) else None
    }
  }

  /**
   * Classic O(n*m) Levenshtein distance — small enough for tool-name
   * comparisons that allocation cost is negligible.
   */
  def levenshtein(a: String, b: String): Int = {
    val n = a.length
    val m = b.length
    if (n == 0) return m
    if (m == 0) return n
    val prev = new Array[Int](m + 1)
    val curr = new Array[Int](m + 1)
    var j = 0
    while (j <= m) { prev(j) = j; j += 1 }
    var i = 1
    while (i <= n) {
      curr(0) = i
      var k = 1
      while (k <= m) {
        val cost = if (a.charAt(i - 1) == b.charAt(k - 1)) 0 else 1
        curr(k) = math.min(
          math.min(curr(k - 1) + 1, prev(k) + 1),
          prev(k - 1) + cost
        )
        k += 1
      }
      System.arraycopy(curr, 0, prev, 0, m + 1)
      i += 1
    }
    prev(m)
  }

  /**
   * Enrich an existing free-form rule (e.g. the
   * [[sigil.provider.ToolCallAccumulator]]'s validator-error string) by
   * appending the tool's schema + example.
   */
  def enrichRule(tool: Tool,
                 rule: String,
                 sentArgs: Option[String] = None,
                 dialect: SchemaDialect = SchemaDialect.Identity,
                 reliability: Reliability = Reliability.Wobbly): String =
    buildBody(rule, tool, hint = None, dialect, reliability) +
      sentArgs.map(a => s"\n\nYou sent:\n$a").getOrElse("")

  /**
   * Render the refusal for a call whose args failed to parse or decode
   * (the `WireCall.Malformed` shape). A pure materialise failure
   * (every violation [[ViolationKind.Structural]]) keeps the
   * historical "Failed to parse args" phrasing; constraint violations
   * read as "violated schema constraints". The model's args ride the
   * body verbatim. `tool` is the resolved carrier when available so
   * the schema + example are appended.
   */
  def malformedArgs(tool: Option[Tool],
                    name: String,
                    error: DecodeError,
                    rawArgs: Json,
                    dialect: SchemaDialect = SchemaDialect.Identity,
                    reliability: Reliability = Reliability.Wobbly): String = {
    val argsText = rawArgs match {
      case fabric.Str(s, _) => s
      case other => fabric.io.JsonFormatter.Compact(other)
    }
    val rawSnippet = argsText.take(500)
    val truncated = if (argsText.length > 500) " (truncated)" else ""
    val pureMaterialise = error.violations.forall(_.kind == ViolationKind.Structural)
    val rule =
      if (pureMaterialise) s"Failed to parse args for tool $name: ${error.render}."
      else s"Args for tool $name violated schema constraints: ${error.render}"
    val sentArgs =
      if (pureMaterialise) Some(s"$rawSnippet$truncated")
      else Some(renderSentArgs(argsText))
    tool match {
      case Some(t) => enrichRule(t, rule, sentArgs, dialect, reliability)
      case None => rule + sentArgs.map(a => s"\n\nYou sent:\n$a").getOrElse("")
    }
  }

  /**
   * The refusal body. A model whose tool calls are
   * [[Reliability.Solid]] fumbled a constraint, not the call shape —
   * it already has the schema in its roster, so re-printing the schema
   * and a worked example spends tokens on hand-holding it doesn't
   * need. Wobbly / Unreliable emitters get the full block pinned next
   * to the rejection.
   */
  private def buildBody(rule: String,
                        tool: Tool,
                        hint: Option[String],
                        dialect: SchemaDialect,
                        reliability: Reliability): String = {
    val hintBlock = hint.map(h => s"\n\n$h").getOrElse("")
    reliability match {
      case Reliability.Solid => s"$rule$hintBlock"
      case _ =>
        s"$rule$hintBlock\n\n" +
          s"Schema for `${tool.name.value}`:\n${schemaJson(tool, dialect)}\n\n" +
          s"Example call for `${tool.name.value}`:\n${exampleJson(tool)}"
    }
  }

  /**
   * Convenience for tools whose refusal needs to mention the raw args
   * the model sent. Parses the raw text as JSON when possible so the
   * rendered value is faithful; on parse failure falls back to the raw
   * string.
   */
  def renderSentArgs(rawArgs: String): String =
    try JsonFormatter.Default(JsonParser(rawArgs))
    catch { case _: Throwable => rawArgs }
}
