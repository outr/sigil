package sigil.conversation

import fabric.rw.*

/**
 * Verdict returned by the consolidation consult
 * ([[sigil.tool.consult.ConsolidateMemoriesTool]]) for one
 * near-duplicate memory cluster:
 *
 *   - [[Merge]] — the cluster states one fact; supersede every member
 *     with a single merged record (the consult supplies the merged
 *     fact + label).
 *   - [[KeepSeparate]] — the members are distinct facts that merely
 *     embed near each other; leave every record untouched.
 */
enum ConsolidationVerdict derives RW {
  case Merge
  case KeepSeparate
}
