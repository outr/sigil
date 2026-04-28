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

  /** Concatenate the user-visible textual content of a Message into a
    * single trimmed string. Covers every [[ResponseContent]] variant
    * via an exhaustive match — adding a new variant to the enum will
    * surface a non-exhaustive-match warning here so the rendering rule
    * is decided deliberately. Mirrors `ConversationSession.textOf`. */
  def textOf(m: Message): String =
    m.content.map {
      case ResponseContent.Text(text)                => text
      case ResponseContent.Markdown(text)            => text
      case ResponseContent.Heading(text)             => text
      case ResponseContent.Code(code, _)             => code
      case ResponseContent.Diff(diff, _)             => diff
      case ResponseContent.Citation(source, exc, _)  => exc.fold(source)(e => s"$source: $e")
      case ResponseContent.ItemList(items, _)        => items.mkString("\n")
      case ResponseContent.Table(headers, rows)      =>
        (headers :: rows).map(_.mkString(" | ")).mkString("\n")
      case ResponseContent.Link(url, label)          => s"$label ($url)"
      case ResponseContent.Image(url, alt)           => alt.fold(url.toString)(a => s"$a ($url)")
      case ResponseContent.Field(label, value, _)    => s"$label: $value"
      case ResponseContent.Options(prompt, opts, _)  =>
        s"$prompt\n" + opts.map(o => s"${o.label}: ${o.value}").mkString("\n")
      case ResponseContent.Failure(reason, _)        => reason
      case ResponseContent.TextInput(label, _, _, _) => label
      case ResponseContent.SecretInput(label, _, _)  => label
      case ResponseContent.SecretRef(_, label)       => label
      case ResponseContent.Divider                   => ""
    }.filter(_.nonEmpty).mkString("\n").trim
}
