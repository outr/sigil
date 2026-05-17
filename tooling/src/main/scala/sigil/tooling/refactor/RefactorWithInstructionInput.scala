package sigil.tooling.refactor

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for [[RefactorWithInstructionTool]] — a single tool call
 * that drives a per-file LLM worker to apply a natural-language
 * instruction to every grep match in a path glob, then commits
 * the aggregated edits atomically.
 *
 * @param path         relative or absolute filesystem path; the
 *                     glob below restricts to files within this
 *                     root.
 * @param glob         optional file-set glob; when omitted every
 *                     file under `path` is eligible. The glob filter
 *                     restricts the candidate set by suffix or path
 *                     fragment (Scala sources only, Kotlin sources
 *                     under a sub-tree, and so on).
 * @param findPattern  regex; files containing at least one match
 *                     are considered for refactoring.
 * @param instruction  natural-language description of the change to
 *                     apply at each match. Workers read this verbatim.
 * @param dryRun       when true, run the worker pass + aggregate
 *                     edits + return the report WITHOUT writing
 *                     to disk.
 * @param workerModelId optional explicit model id to drive each
 *                     worker. When unset, the framework's routing
 *                     picks the cheapest available candidate at
 *                     `Low` complexity for `CodingWork`.
 * @param maxParallel  cap on simultaneous worker invocations.
 *                     Higher values shorten total wall time at the
 *                     cost of more concurrent LLM round-trips.
 * @param maxWorkers   hard cost-cap on total worker invocations —
 *                     a 50K-file refactor can't accidentally
 *                     explode billing. Default 1000.
 */
case class RefactorWithInstructionInput(path: String,
                                        glob: Option[String] = None,
                                        findPattern: String,
                                        instruction: String,
                                        dryRun: Boolean = false,
                                        workerModelId: Option[String] = None,
                                        maxParallel: Int = 5,
                                        maxWorkers: Int = 1000) extends ToolInput derives RW
