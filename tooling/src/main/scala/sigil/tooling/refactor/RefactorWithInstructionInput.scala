package sigil.tooling.refactor

import fabric.rw.*
import sigil.provider.Complexity
import sigil.tool.ToolInput

/**
 * Input for [[RefactorWithInstructionTool]] — the prepare step of
 * the three-tool refactor session. The framework dispatches a
 * per-file LLM worker, aggregates the worker decisions into a
 * draft [[ApplyWorkspaceEdit]], and parks the draft in the
 * per-Sigil session store. Disk is NOT touched at this step — the
 * agent inspects the returned diffs and either commits via the
 * apply tool or drops via the cancel tool.
 *
 * @param path         relative or absolute filesystem path; the
 *                     glob below restricts to files within this
 *                     root.
 * @param glob         optional file-set glob; when omitted every
 *                     file under `path` is eligible. The glob filter
 *                     restricts the candidate set by suffix or path
 *                     fragment (Scala sources only, Kotlin sources
 *                     under a sub-tree, and so on).
 * @param findPattern  regex; every file containing at least one
 *                     match becomes a paid LLM worker call. Pick
 *                     the narrowest expression that still captures
 *                     the change set — a generic `[A-Z]+` will spawn
 *                     a worker per file in the tree and exhaust the
 *                     cost cap before producing useful diffs.
 * @param instruction  natural-language description of the change to
 *                     apply at each match. Workers read this verbatim.
 * @param complexity   routing tier handed to the framework's
 *                     [[sigil.provider.ProviderStrategy]] when
 *                     resolving the worker model. The strategy's
 *                     `candidates(CodingWork)` list is filtered to
 *                     candidates whose `supportedComplexity` set
 *                     includes the requested tier, and the first
 *                     match wins. Pick the lowest tier that can
 *                     reliably make the call — straight find/replace
 *                     and mechanical rewrites belong on `Low`, while
 *                     edits that need to read context across the
 *                     file (rename a method and update every
 *                     callsite, with care for shadowing) belong on
 *                     `Medium` or higher. No default — the caller
 *                     must think about cost vs. capability.
 * @param workerModelId optional explicit model id to drive each
 *                     worker. When set, this wins over routing —
 *                     useful for pinning a known-good model for
 *                     debugging or for apps that don't want
 *                     `ProviderStrategy` to participate. When unset,
 *                     the framework routes through
 *                     [[sigil.provider.ProviderStrategy]] at the
 *                     input's `complexity` for `CodingWork`.
 * @param maxParallel  cap on simultaneous worker invocations.
 *                     Higher values shorten total wall time at the
 *                     cost of more concurrent LLM round-trips.
 * @param maxFiles     hard cost-cap on the number of files that
 *                     will be handed to workers — a 50K-file
 *                     refactor can't accidentally explode billing.
 *                     Default 10000. The scope preview surfaces the
 *                     resolved file count so the caller can narrow
 *                     `findPattern` before confirming when the cap
 *                     would otherwise bite.
 * @param confirmed    two-phase guard. The default `false` runs
 *                     grep + complexity routing and returns a
 *                     [[RefactorWithInstructionScope]] preview
 *                     without dispatching any worker; the caller
 *                     reviews the scope and re-invokes with
 *                     `confirmed = true` (and the same other
 *                     parameters) to dispatch the workers and
 *                     stage the session. The session id from the
 *                     scope preview is NOT required on the
 *                     confirm call — confirm re-greps from the
 *                     same parameters.
 */
case class RefactorWithInstructionInput(path: String,
                                        glob: Option[String] = None,
                                        findPattern: String,
                                        instruction: String,
                                        complexity: Complexity,
                                        workerModelId: Option[String] = None,
                                        maxParallel: Int = 5,
                                        maxFiles: Int = 10000,
                                        confirmed: Boolean = false) extends ToolInput derives RW
