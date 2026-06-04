package sigil.heal

import fabric.rw.{PolyType, RW, listRW, stringRW}
import lightdb.id.Id
import sigil.event.Event

/**
 * Structured account of what's wrong with a conversation's durable
 * history. Each subtype names ONE specific shape of corruption — a
 * dangling tool call without a paired result, a tool result pointing
 * at a deleted origin, a memory-summary covering events that no
 * longer exist, …
 *
 * Heals produce one or more `CorruptionEvidence` rows during their
 * `detect` pass, then operate on those in `apply`. The audit event
 * stream (`ConversationCorruptionDetected` / `ConversationHealed`)
 * carries the evidence list verbatim so log aggregators / operators
 * can root-cause the underlying framework bug from the durable log
 * alone without rerunning the failing turn.
 *
 * Open trait — apps with their own corruption shapes add subtypes
 * and register them through fabric polymorphic RW. The framework
 * ships the cases the default strategies recognise.
 */
trait CorruptionEvidence

object CorruptionEvidence extends PolyType[CorruptionEvidence]()(using scala.reflect.ClassTag(classOf[CorruptionEvidence])) {

  /**
   * The conversation log has a `ToolInvoke` whose paired settle
   * (typed result + `Complete` state on the invoke, or the legacy
   * separate `ToolResults` event) is missing. Provider-side this
   * trips the "No tool output found for function call <id>" 400 from
   * OpenAI / equivalent Anthropic / Google phrasings; framework-side
   * it trips `BrokenHistoryException` at `Provider.renderFrames`.
   *
   * @param invokeId  durable event id of the orphan `ToolInvoke`
   * @param callId    wire-level `call_<hash>` the provider remembers
   * @param toolName  the tool name the invoke carried
   */
  case class MissingToolResult(invokeId: Id[Event], callId: String, toolName: String) extends CorruptionEvidence derives RW

  /**
   * A `Tool`-role Message / settle delta names an `origin` event id
   * that doesn't exist in the durable log (delete-conversation race,
   * partial migration, manual surgery). The current renderer would
   * either drop the message or render it without a paired tool_use.
   */
  case class DanglingToolResultOrigin(resultId: Id[Event], orphanOriginId: Id[Event]) extends CorruptionEvidence derives RW

  /**
   * A persisted summary covers events that no longer exist. The
   * curator would render the summary alongside a frame trail that
   * has gaps where the cited events used to be.
   */
  case class OrphanSummaryCoverage(summaryId: Id[sigil.conversation.ContextSummary],
                                   missingEventIds: List[Id[Event]])
    extends CorruptionEvidence derives RW
}
