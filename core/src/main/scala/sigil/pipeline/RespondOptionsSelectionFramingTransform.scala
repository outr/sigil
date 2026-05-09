package sigil.pipeline

import lightdb.id.Id
import rapid.Task
import sigil.Sigil
import sigil.conversation.Conversation
import sigil.event.{Message, MessageRole}
import sigil.participant.AgentParticipantId
import sigil.signal.{EventState, Signal}
import sigil.tool.model.ResponseContent

/**
 * Pre-persist transform that frames a user's reply when it
 * matches the option values from the agent's most-recent
 * `respond_options` Message. Sigil bug #72.
 *
 * Without framing, a respond_options selection arrives at the
 * next agent iteration as a bare token (`"start_metals"`).
 * Small / local models routinely misinterpret as "user told me
 * their preference" rather than "user asked me to take that
 * action" and reply with `no_response` — the user-visible
 * symptom is the chat going silent.
 *
 * The fix rewrites the user message's [[ResponseContent.Text]]
 * to:
 *
 * {{{
 *   I'd like to:
 *   - <option label> (value: <option value>)
 *     <option description, when present>
 *   (Selected from: '<original prompt>')
 * }}}
 *
 * Multi-select ("a, b, c") expands to one bullet per match.
 * Non-matching free-form replies (the user typed something
 * outside the option set) pass through unchanged so an agent
 * with built-in NL handling still sees the user's actual words.
 *
 * Idempotent: if the message already carries the framed
 * format, the transform is a no-op (a re-publish through
 * `inboundTransforms` doesn't double-frame).
 */
object RespondOptionsSelectionFramingTransform extends InboundTransform {

  override def apply(signal: Signal, self: Sigil): Task[Signal] = signal match {
    case m: Message
      if m.state == EventState.Complete
      && m.role == MessageRole.Standard
      && !m.participantId.isInstanceOf[AgentParticipantId]
      && userText(m).exists(_.nonEmpty)
      && !alreadyFramed(m) =>
        recentOptionsBlock(self, m.conversationId, m).flatMap {
          case Some(opts) =>
            val text = userText(m).get
            tryFrame(text, opts) match {
              case Some(framed) => Task.pure(replaceText(m, framed))
              case None         => Task.pure(m)
            }
          case None => Task.pure(m)
        }
    case other => Task.pure(other)
  }

  /** Concatenated `Text` content of the user's message — the only
    * shape selections arrive in. Other content kinds (Markdown,
    * Code, …) imply a free-form reply that doesn't need framing. */
  private def userText(m: Message): Option[String] = {
    val txt = m.content.collect { case ResponseContent.Text(t) => t }.mkString("\n").trim
    if (txt.isEmpty) None else Some(txt)
  }

  private def alreadyFramed(m: Message): Boolean =
    userText(m).exists(_.startsWith("I'd like to:"))

  /** Walk persisted Messages newest-first; the first agent message
    * carrying a `ResponseContent.Options` block is the prompt the
    * user is replying to. Stops scanning at the first non-agent
    * message to avoid mis-attributing across user turns. */
  private def recentOptionsBlock(self: Sigil,
                                 conversationId: Id[Conversation],
                                 incoming: Message): Task[Option[ResponseContent.Options]] =
    self.withDB(_.events.transaction(_.list)).map { events =>
      val priorMessages = events.iterator
        .collect { case msg: Message => msg }
        .filter(_.conversationId == conversationId)
        .filter(_._id != incoming._id)
        .toList
        .sortBy(_.timestamp.value)
      var found: Option[ResponseContent.Options] = None
      val it = priorMessages.reverseIterator
      var stopped = false
      while (!stopped && it.hasNext) {
        val msg = it.next()
        if (msg.participantId.isInstanceOf[AgentParticipantId]) {
          msg.content.collectFirst { case o: ResponseContent.Options => o } match {
            case Some(opts) =>
              found = Some(opts); stopped = true
            case None => () // earlier agent message with no options — keep scanning back
          }
        } else {
          // Hit another non-agent message before finding an Options
          // block — the current incoming reply isn't the immediate
          // response to a respond_options. Stop.
          stopped = true
        }
      }
      found
    }

  /** Best-effort: attempt to parse `text` as one of the option
    * values (case-insensitive, trimmed) or a comma-separated
    * multi-select; `None` when nothing matches. */
  private def tryFrame(text: String, opts: ResponseContent.Options): Option[String] = {
    val tokens = text.split(",").iterator.map(_.trim).filter(_.nonEmpty).toList
    val matched = tokens.flatMap { tok =>
      opts.options.find(o => o.value.equalsIgnoreCase(tok) || o.label.equalsIgnoreCase(tok))
    }
    if (matched.isEmpty || matched.size != tokens.size) None
    else {
      val bullets = matched.map { opt =>
        val head = s"- ${opt.label} (value: ${opt.value})"
        opt.description.filter(_.nonEmpty) match {
          case Some(d) => s"$head\n  $d"
          case None    => head
        }
      }.mkString("\n")
      Some(
        s"""I'd like to:
           |$bullets
           |(Selected from: '${opts.prompt}')""".stripMargin
      )
    }
  }

  private def replaceText(m: Message, framed: String): Message = {
    val nonText = m.content.filterNot(_.isInstanceOf[ResponseContent.Text])
    m.copy(content = ResponseContent.Text(framed) +: nonText)
  }
}
