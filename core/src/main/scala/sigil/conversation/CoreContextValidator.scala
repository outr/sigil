package sigil.conversation

import lightdb.id.Id
import rapid.Task
import sigil.{Sigil, SpaceId}
import sigil.db.Model
import sigil.signal.CriticalMemoryShare
import sigil.tokenize.{HeuristicTokenizer, Tokenizer}

/**
 * Pure helper that estimates the inviolable "core context" tokens
 * (Critical memories + a static system-prompt overhead allowance)
 * and validates them against [[Sigil.coreContextShareLimit]] for a
 * given target model.
 *
 * Used at write time by `Sigil.persistMemory` / `upsertMemoryByKey`
 * to reject Critical-memory operations that would push the inviolable
 * share over the cap. The cap default (50%) leaves the auto-shedding
 * machinery enough room to fit any reasonable conversation; apps with
 * thinner pinned sets can tighten it, apps with rich personas can
 * loosen.
 */
object CoreContextValidator {

  /** Token allowance reserved for the static system-prompt sections
    * (Instructions block, ModeBlock, Topic block, framework framing
    * prefix). Approximate; the actual rendered overhead varies by
    * model + topic length. Phase 0 measurements: ~420 tokens
    * baseline across scenarios. We use 500 to leave headroom. */
  val SystemPromptOverheadTokens: Int = 500

  case class CoreContextEstimate(criticalTokens: Int,
                                 systemOverheadTokens: Int,
                                 contributors: List[CriticalMemoryShare]) {
    def total: Int = criticalTokens + systemOverheadTokens
  }

  /** Estimate the current core-context cost for a given space set.
    * Critical memories are looked up via `Sigil.findCriticalMemories`
    * scoped to the supplied spaces. Token count uses `summary || fact`
    * to mirror the renderer's policy. */
  def estimate(sigil: Sigil,
               spaces: Set[SpaceId],
               tokenizer: Tokenizer = HeuristicTokenizer): Task[CoreContextEstimate] =
    sigil.findCriticalMemories(spaces).map { memories =>
      val contributors = memories.map { m =>
        val rendered = if (m.summary.trim.nonEmpty) m.summary else m.fact
        val key = if (m.key.nonEmpty) m.key else m._id.value
        CriticalMemoryShare(key, tokenizer.count(rendered))
      }
      CoreContextEstimate(
        criticalTokens = contributors.iterator.map(_.tokens).sum,
        systemOverheadTokens = SystemPromptOverheadTokens,
        contributors = contributors.sortBy(-_.tokens)
      )
    }

  /** What would the core-context total be if `proposed` were added (or
    * replaced an existing record at the same key)? */
  def projectedTotal(current: CoreContextEstimate,
                     proposed: ContextMemory,
                     tokenizer: Tokenizer = HeuristicTokenizer): Int = {
    val proposedRendered = if (proposed.summary.trim.nonEmpty) proposed.summary else proposed.fact
    val proposedTokens = tokenizer.count(proposedRendered)
    val proposedKey = if (proposed.key.nonEmpty) proposed.key else proposed._id.value
    val replacingExisting = current.contributors.find(_.key == proposedKey).map(_.tokens).getOrElse(0)
    current.total - replacingExisting + proposedTokens
  }

  /** Per-model token cap for the core-context section. */
  def limitFor(model: Model, share: Double): Int =
    math.max(0, (model.contextLength.toDouble * share).toInt)

  /** Chooses the smallest contextLength among registered models — used
    * when validating a `persistMemory` call without a conversation
    * context (no specific model in scope). The cap holds for every
    * model the app might use. */
  def smallestModelContext(sigil: Sigil): Option[Model] = {
    val models = sigil.cache.find()
    if (models.isEmpty) None else Some(models.minBy(_.contextLength))
  }
}
