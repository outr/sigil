package sigil.tool.discovery

import sigil.tool.ToolName

/**
 * Sigil bug #283 — synthesise [[TaskShapeHint]]s from the COMPOSITION
 * of a `find_capability` result set plus the original query.
 *
 * The hints exist because BM25 ranking on its own can't recognise
 * task-SHAPE: a "remove all bug references across files" task ranks
 * `grep` at the top against grep-shaped tokens (`search`, `pattern`,
 * `text`), but the better primitive is `dispatch_workers` running a
 * compile-checked action over each match. The agent reads top-to-
 * bottom and commits to the top hit before any prompt-side guidance
 * could intervene; surfacing the recommendation IN the result is the
 * narrowest fix.
 *
 * Two shapes ship by default:
 *
 *   - `multi_file_transformation` — fires when the result set
 *     contains BOTH a textual primitive (grep / edit_file / …) AND
 *     `dispatch_workers`, AND the query mentions "do X to many
 *     things" verbs.
 *
 *   - `semantic_navigation` — fires when the result set contains a
 *     navigation-flavoured `lsp_*` tool AND `grep`, AND the query
 *     mentions navigation verbs (caller / definition / reference /
 *     implementation). Recommends the LSP tool over grep — the LSP
 *     reads the language's type-aware index instead of textual
 *     pattern matching.
 *
 * Apps with additional task shapes can layer their own synthesiser
 * over the framework's output by post-processing
 * `FindCapabilityOutput.taskShapeHints` (append or replace).
 */
object TaskShapeHints {

  private val textualPrimitives: Set[String] =
    Set("grep", "edit_file", "edit_at_range", "lsp_did_change", "read_file")

  private val transformVerbs: Set[String] =
    Set(
      "remove",
      "delete",
      "rename",
      "replace",
      "transform",
      "classify",
      "summarize",
      "summarise",
      "score",
      "update",
      "refactor",
      "batch",
      "bulk",
      "across",
      "every",
      "all",
      "many"
    )

  private val navigationVerbs: Set[String] =
    Set(
      "caller",
      "callers",
      "callee",
      "callees",
      "definition",
      "definitions",
      "reference",
      "references",
      "implementation",
      "implementations",
      "usage",
      "usages",
      "declaration",
      "declarations"
    )

  /**
   * Compute hints for a result set. `query` is the (already-
   * normalised, lowercased, space-separated) keyword string the
   * agent passed to find_capability; `matches` is the ranked result
   * list.
   *
   * Returns hints in stable order; safe to call on every find_capability
   * resolution — synthesis is pure and bounded by `matches.size`.
   */
  def synthesize(query: String, matches: List[CapabilityMatch]): List[TaskShapeHint] = {
    val toolNames: Set[String] = matches.iterator
      .filter(_.capabilityType == CapabilityType.Tool)
      .map(_.name)
      .toSet
    if (toolNames.isEmpty) return Nil
    val tokens: Set[String] = query.toLowerCase
      .split("\\s+")
      .iterator
      .filter(_.nonEmpty)
      .toSet
    val hints = scala.collection.mutable.ListBuffer.empty[TaskShapeHint]
    multiFileTransformationHint(tokens, toolNames).foreach(hints += _)
    semanticNavigationHint(tokens, toolNames).foreach(hints += _)
    hints.toList
  }

  private def multiFileTransformationHint(tokens: Set[String],
                                          toolNames: Set[String]): Option[TaskShapeHint] = {
    if (!toolNames.contains("dispatch_workers")) return None
    if (!toolNames.exists(textualPrimitives.contains)) return None
    if (tokens.intersect(transformVerbs).isEmpty) return None
    Some(TaskShapeHint(
      shape = "multi_file_transformation",
      recommended = ToolName("dispatch_workers"),
      context =
        "Your query suggests a multi-file transformation. `dispatch_workers` runs a " +
          "compile-checked Scala action over every matched item in parallel — typically the " +
          "right primitive for 'do X to many things' shapes (remove / rename / replace / " +
          "refactor across files) even when textual primitives like `grep` rank higher on " +
          "keyword overlap. Use `dispatch_workers(itemsId, action)` instead of " +
          "`grep + per-file edit_file`."
    ))
  }

  private def semanticNavigationHint(tokens: Set[String],
                                     toolNames: Set[String]): Option[TaskShapeHint] = {
    if (tokens.intersect(navigationVerbs).isEmpty) return None
    if (!toolNames.contains("grep")) return None
    // Prefer the most specific LSP nav tool when multiple are present.
    val ranked = List(
      "lsp_find_references",
      "lsp_find_implementations",
      "lsp_go_to_definition",
      "lsp_workspace_symbols",
      "lsp_document_symbols"
    )
    ranked.find(toolNames.contains).map { lspName =>
      TaskShapeHint(
        shape = "semantic_navigation",
        recommended = ToolName(lspName),
        context =
          s"Your query mentions navigation verbs (callers / definitions / references / " +
            s"implementations). `$lspName` reads the language server's type-aware index — more " +
            "accurate than `grep` for these queries: it follows imports / overloads / inheritance " +
            "and excludes textual false positives (comments, strings, unrelated identifiers)."
      )
    }
  }
}
