package bench

import lightdb.id.Id
import sigil.conversation.Conversation
import sigil.event.{Event, Message, ModeChange, ToolInvoke}
import sigil.tool.ToolName
import sigil.tool.model.ResponseContent

/**
 * Full trace of a benchmark conversation driven by [[AgentBenchHarness]].
 *
 * A trace is the materialized record of one or more user → agent turns:
 * every [[Event]] settled in `SigilDB.events` during each turn (with
 * `state == Complete`), plus the final persisted [[Conversation]] (so
 * scorers can read `currentMode`, `topics`, etc. without re-querying
 * the DB).
 *
 * Scorers are expected to pattern-match against this — there is no
 * "score" field; each benchmark defines its own scoring rule over the
 * trace shape (BFCL: tool-call AST equality; AgentDojo: did the agent
 * call the targeted attack tool; τ-bench: final-database equality;
 * MemoryAgentBench: memory-recall accuracy; …).
 */
final case class ConversationTrace(conversationId: Id[Conversation],
                                   turns: List[TurnTrace],
                                   finalConversation: Conversation) {

  /** Tool invocations across every turn in fire order. */
  def allToolInvokes: Vector[ToolInvoke] = turns.toVector.flatMap(_.toolInvokes)

  /** All mode changes across every turn in chronological order. */
  def allModeChanges: Vector[ModeChange] = turns.toVector.flatMap(_.modeChanges)

  /** The agent's most-recent settled reply across the conversation, or
    * `None` if no turn produced one (typically a no-response path). */
  def lastReply: Option[Message] = turns.reverse.flatMap(_.finalReply).headOption
}

/**
 * One user → agent round inside a [[ConversationTrace]].
 *
 *   - `userMessage` is the request the harness published into Sigil.
 *   - `events` are every Sigil [[Event]] (`state == Complete`) that
 *     settled in this turn's window — the user message itself, every
 *     agent `Message`, every `ToolInvoke`, every `ModeChange`,
 *     `TopicChange`, and the bracketing `AgentState` records — in
 *     chronological order.
 *   - `toolInvokes` is the `ToolInvoke` subset of `events` in fire
 *     order. Scorers that need matching `ToolResults` pattern-match
 *     `events` (only `find_capability`-style discovery emits them).
 *   - `modeChanges` is the `ModeChange` subset of `events`.
 *   - `finalReply` is the agent's last `Complete` Message in the turn —
 *     the user-visible reply for assertion sites that don't care about
 *     intermediate tool calls.
 */
final case class TurnTrace(userMessage: Message,
                           events: Vector[Event],
                           toolInvokes: Vector[ToolInvoke],
                           modeChanges: Vector[ModeChange],
                           finalReply: Option[Message]) {

  /** Concatenated text content of the final reply, or empty string. */
  def replyText: String = finalReply.map(TurnTrace.textOf).getOrElse("")

  /** Names of the tools called this turn, in fire order. */
  def toolCallNames: Vector[ToolName] = toolInvokes.map(_.toolName)
}

object TurnTrace {

  /** Concatenate all `ResponseContent.Text` chunks of a Message into a
    * single trimmed string. Mirrors `ConversationSession.textOf`. */
  def textOf(m: Message): String =
    m.content.collect { case t: ResponseContent.Text => t.text }.mkString.trim
}
