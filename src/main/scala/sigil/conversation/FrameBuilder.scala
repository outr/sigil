package sigil.conversation

import fabric.io.JsonFormatter
import fabric.rw.*
import fabric.{Json, Obj}
import sigil.event.{AgentState, Event, EventVisibility, Message, ModeChange, TitleChange, ToolInvoke, ToolResults}
import sigil.signal.EventState
import sigil.tool.ToolInput
import sigil.tool.ToolInput.given
import sigil.tool.model.ResponseContent

/**
 * Pure, provider-agnostic translation from a single [[Event]] (that has
 * reached `EventState.Complete`) into zero or more [[ContextFrame]]s for
 * a [[ConversationView]].
 *
 * Event → frame mapping rules:
 *   - `Message` whose visibility includes `EventVisibility.Model` →
 *     one `ContextFrame.Text`. UI-only messages are dropped.
 *   - `ToolInvoke` with visibility.Model → one `ContextFrame.ToolCall`.
 *     `respond` is filtered out at render time by the provider (the
 *     following `Message` is the response); the frame is still emitted
 *     here so the view carries the full history.
 *   - `ToolResults` with visibility.Model → one `ContextFrame.ToolResult`
 *     whose `callId` points at the immediately-preceding pending
 *     `ToolInvoke`. If no pending call is found, falls back to a
 *     `System` frame so information isn't silently dropped.
 *   - `ModeChange` with visibility.Model → one `ContextFrame.System`.
 *   - `TitleChange` with visibility.Model → one `ContextFrame.System`.
 *   - `AgentState` and anything UI-only → no frame.
 *
 * Active events (in-flight) are skipped — `framesFor` returns empty.
 * Only Complete events become frames.
 */
object FrameBuilder {

  /**
   * Append frames for a newly-Complete event to an existing frame vector.
   * The return value is the updated vector.
   *
   * `existing` is the vector the new frames are appended to; it's supplied
   * so [[ToolResults]] can pair against the most-recent pending ToolCall.
   */
  def appendFor(existing: Vector[ContextFrame], event: Event): Vector[ContextFrame] = {
    if (event.state != EventState.Complete) return existing
    if (!event.visibility.contains(EventVisibility.Model)) return existing

    event match {
      case m: Message =>
        existing :+ ContextFrame.Text(
          content = renderMessageText(m),
          participantId = m.participantId,
          sourceEventId = m._id
        )

      case ti: ToolInvoke =>
        val argsJson = ti.input
          .map(i => JsonFormatter.Compact(stripPolyDiscriminator(summon[RW[ToolInput]].read(i))))
          .getOrElse("{}")
        existing :+ ContextFrame.ToolCall(
          toolName = ti.toolName,
          argsJson = argsJson,
          callId = ti._id,
          participantId = ti.participantId,
          sourceEventId = ti._id
        )

      case tr: ToolResults =>
        val content =
          if (tr.schemas.isEmpty) "No matches."
          else tr.schemas.map(s => s"- ${s.name}: ${s.description}").mkString("\n")
        pairedCallId(existing) match {
          case Some(callId) =>
            existing :+ ContextFrame.ToolResult(
              callId = callId,
              content = content,
              sourceEventId = tr._id
            )
          case None =>
            existing :+ ContextFrame.System(
              content = s"Tool results (orphan): $content",
              sourceEventId = tr._id
            )
        }

      case mc: ModeChange =>
        existing :+ ContextFrame.System(
          content = s"Mode changed to ${mc.mode}${mc.reason.map(r => s" ($r)").getOrElse("")}.",
          sourceEventId = mc._id
        )

      case tc: TitleChange =>
        existing :+ ContextFrame.System(
          content = s"Title changed to: ${tc.title}",
          sourceEventId = tc._id
        )

      case _: AgentState =>
        existing

      case other =>
        // Unknown Model-visible event type — fail loud so gaps are caught.
        // Mirrors the LlamaCppProvider.renderHistory invariant: no silent drops.
        throw new RuntimeException(
          s"FrameBuilder: Model-visible Event ${other.getClass.getSimpleName} has no frame rule. " +
            s"Add a case here or remove EventVisibility.Model from the event."
        )
    }
  }

  /**
   * Fold a chronologically-sorted sequence of events into a fresh frame
   * vector. Used by `Sigil.rebuildView` for recovery / migration.
   */
  def build(events: Iterable[Event]): Vector[ContextFrame] =
    events.foldLeft(Vector.empty[ContextFrame])((acc, e) => appendFor(acc, e))

  /**
   * Apply the participant-projection deltas this event implies:
   *   - `ToolInvoke` complete → push toolName onto `recentTools`
   *   - `ToolResults` complete → replace `suggestedTools` with fresh
   *     matches
   *
   * `activeSkills` are driven by app-supplied events; the framework
   * doesn't materialize them here (apps can override via custom
   * publish-path hooks).
   */
  def updateProjections(existing: Map[sigil.participant.ParticipantId, ParticipantProjection],
                        event: Event): Map[sigil.participant.ParticipantId, ParticipantProjection] = {
    if (event.state != EventState.Complete) return existing
    event match {
      case ti: ToolInvoke =>
        val pid = ti.participantId
        val proj = existing.getOrElse(pid, ParticipantProjection())
        val updated = proj.copy(recentTools = ti.toolName :: proj.recentTools.filterNot(_ == ti.toolName))
        existing + (pid -> updated)

      case tr: ToolResults =>
        val pid = tr.participantId
        val proj = existing.getOrElse(pid, ParticipantProjection())
        val updated = proj.copy(suggestedTools = tr.schemas.map(_.name).toList)
        existing + (pid -> updated)

      case _ => existing
    }
  }

  private def renderMessageText(m: Message): String =
    m.content
      .map {
        case ResponseContent.Text(t)       => t
        case ResponseContent.Markdown(t)   => t
        case ResponseContent.Code(c, lang) => s"```${lang.getOrElse("")}\n$c\n```"
        case other                         => other.toString
      }
      .mkString("\n")

  /** Strip the `ToolInput` poly discriminator so the `arguments` JSON
    * is pure parameter-schema on the wire. Same logic the llama.cpp
    * provider used to do at render time; moved here so frames already
    * carry the clean form. */
  private def stripPolyDiscriminator(json: Json): Json = json match {
    case o: Obj => Obj(o.value - "type")
    case other  => other
  }

  /** Find the most-recently-added `ToolCall` frame that hasn't yet been
    * paired with a `ToolResult`. A pending call is one that has no
    * corresponding `ToolResult(callId = _)` later in the vector. */
  private def pairedCallId(frames: Vector[ContextFrame]): Option[lightdb.id.Id[Event]] = {
    val resolved = frames.collect { case ContextFrame.ToolResult(id, _, _) => id }.toSet
    frames.reverseIterator.collectFirst {
      case ContextFrame.ToolCall(_, _, callId, _, _) if !resolved.contains(callId) => callId
    }
  }
}
