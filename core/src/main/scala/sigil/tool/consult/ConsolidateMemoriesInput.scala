package sigil.tool.consult

import fabric.rw.*
import sigil.conversation.ConsolidationVerdict
import sigil.tool.ToolInput

/**
 * Input for [[ConsolidateMemoriesTool]] — the typed verdict the
 * consolidation sweep's cheap-tier consult returns for one
 * near-duplicate cluster.
 *
 *   - `verdict` — [[ConsolidationVerdict.Merge]] or
 *     [[ConsolidationVerdict.KeepSeparate]].
 *   - `mergedFact` — required when merging: one self-contained fact
 *     that preserves every non-redundant detail of the cluster.
 *   - `mergedLabel` — short human-readable label for the merged
 *     record; optional (the sweep falls back to the oldest member's
 *     label).
 */
case class ConsolidateMemoriesInput(verdict: ConsolidationVerdict,
                                    mergedFact: Option[String] = None,
                                    mergedLabel: Option[String] = None) extends ToolInput derives RW
