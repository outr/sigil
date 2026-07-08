package sigil.conversation.compression

import sigil.conversation.{ContextFrame, ContextMemory, ContextSummary, ToolCallState}
import sigil.information.InformationSummary
import sigil.tokenize.{HeuristicTokenizer, Tokenizer}

/**
 * Pre-call token estimator — used by the curator to decide whether a
 * prospective turn will fit under the [[ContextBudget]]. Backed by a
 * pluggable [[Tokenizer]]; heuristic by default (4 chars/token, errs
 * conservative), per-provider tokenizers (jtokkit cl100k_base) when
 * apps wire one through.
 */
object TokenEstimator {

  /** Estimate tokens used by a collection of conversation frames. */
  def estimateFrames(frames: Vector[ContextFrame], tokenizer: Tokenizer = HeuristicTokenizer): Int =
    frames.iterator.map {
      case ContextFrame.Text(c, _, _, _, _)               => tokenizer.count(c)
      case tc: ContextFrame.ToolCall                      =>
        // Sigil #261 — unified ToolCall(state) frame carries both
        // call args AND (when Complete) the result content. Token
        // budget reflects both halves of the wire-rendered pair.
        val argTokens = tokenizer.count(tc.argsJson)
        val resultTokens = tc.state match {
          // Sigil #382 — image frames cost real (bounded) tokens on the
          // wire (~22/350/1600 for Claude by quality tier), so account
          // for them here; otherwise images never pressure the budget and
          // the curator's pressure-triggered image ceiling can't trigger.
          case ToolCallState.Complete(content, images) =>
            tokenizer.count(content) + images.iterator.map(TokenEstimator.imageTokens).sum
          case ToolCallState.Active                     => 0
        }
        argTokens + resultTokens
      case ContextFrame.System(c, _, _)                   => tokenizer.count(c)
      case ContextFrame.Reasoning(_, summary, _, _, _, _) => tokenizer.count(summary.mkString("\n"))
    }.sum

  /** Estimate tokens used by resolved memory records. Mirrors the
    * `summary || fact` policy the renderer applies — the per-turn
    * cost reflects what actually gets sent on the wire. */
  def estimateMemories(memories: Vector[ContextMemory], tokenizer: Tokenizer = HeuristicTokenizer): Int =
    memories.iterator.map { m =>
      val text = if (m.summary.trim.nonEmpty) m.summary else m.fact
      tokenizer.count(text)
    }.sum

  /** Estimate tokens used by resolved summary records. */
  def estimateSummaries(summaries: Vector[ContextSummary], tokenizer: Tokenizer = HeuristicTokenizer): Int =
    summaries.iterator.map(s => tokenizer.count(s.text)).sum

  /** Estimate tokens used by Information catalog entries (id + summary
    * lines as `Provider.renderSystem` emits them). */
  def estimateInformation(infos: Vector[InformationSummary], tokenizer: Tokenizer = HeuristicTokenizer): Int =
    infos.iterator.map(i => tokenizer.count(s"${i.id.value} [${i.informationType.name}]: ${i.summary}")).sum

  /** Sum the curator-controlled sections of a tentative TurnInput.
    * System prompt overhead + tool roster are added by the provider's
    * pre-flight gate; this is the curator's portion only. */
  def estimateCuratorSections(frames: Vector[ContextFrame],
                              criticalMemories: Vector[ContextMemory],
                              memories: Vector[ContextMemory],
                              summaries: Vector[ContextSummary],
                              information: Vector[InformationSummary],
                              tokenizer: Tokenizer): Int =
    estimateFrames(frames, tokenizer) +
      estimateMemories(criticalMemories, tokenizer) +
      estimateMemories(memories, tokenizer) +
      estimateSummaries(summaries, tokenizer) +
      estimateInformation(information, tokenizer)

  /** Sigil #382 — approximate wire token cost of one rendered image by
    * its quality tier (parsed off the URL's `_q` param). A provider's
    * tokenizer caps an image regardless of byte size (~1600 for Claude
    * at the 1568px high tier); the lower tiers scale down from there. */
  def imageTokens(url: spice.net.URL): Int = sigil.tool.ImageQuality.fromUrl(url) match {
    case sigil.tool.ImageQuality.Thumbnail => 22
    case sigil.tool.ImageQuality.Low       => 350
    case sigil.tool.ImageQuality.High      => 1600
  }
}
