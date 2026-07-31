package sigil.tool.util

import fabric.rw.*
import rapid.Task
import sigil.event.{Message, MessageRole}
import sigil.participant.ParticipantId
import sigil.signal.EventState
import sigil.tool.model.{RelayMessageInput, ResponseContent}
import sigil.tool.{DiscoverySpec, Effect, MutationTargeting, TextToolOutput, Tool, ToolContext, ToolName, ToolProfile, ToolResult, ToolSpec}

/**
 * Opt-in util-tier tool: the cross-conversation *write* half (sigil
 * #329) — the sibling of [[SearchConversationTool]]'s cross-conversation
 * read. Posts a Message authored by the calling agent into another
 * conversation it is a member of, via the normal `host.publish` pipeline
 * (so the relayed Message persists, fans out, triggers, and frames like
 * any other Message in the target conversation).
 *
 * This is the bridge for the agent-bridge delegation model (#327): one
 * agent identity holds membership in both a parent conversation `P` and
 * a worker sub-conversation `W`; a worker question surfaces
 * `W -> agent -> P -> user`, and the answer flows back
 * `user -> P -> agent -> W -> worker`. Each leg is a `relay_message`
 * into the *other* conversation.
 *
 * Authorization is membership-scoped: the emit is permitted only when
 * the calling agent is a participant of the target conversation. The
 * relayed Message is stamped with `source` provenance recording the
 * originating conversation so the bridge is auditable.
 */
case object RelayMessageTool extends Tool {
  type Input  = RelayMessageInput
  type Output = TextToolOutput
  val inputRW  = summon[RW[RelayMessageInput]]
  val outputRW = summon[RW[TextToolOutput]]
  override val name = ToolName("relay_message")
  override val description =
    """Post a message into ANOTHER conversation you are a participant of — the cross-conversation
      |write primitive that bridges a parent conversation and a worker sub-conversation.
      |
      |Use this when:
      |  - you are bridging a worker's question up to the user (relay into the parent conversation), or
      |  - you are relaying the user's answer back down to a worker (relay into the worker conversation), or
      |  - you need to surface a worker's result into the user-facing conversation.
      |
      |You may relay ONLY into a conversation you have joined — a relay into a conversation you are not a
      |member of is rejected.
      |
      |`conversationId` — the target conversation's id.
      |
      |`content` — the message text to post.
      |
      |`addressees` — optional list of participant-id values in the target conversation to direct the
      |message at (only those participants are woken). Omit to broadcast to everyone in the target.""".stripMargin
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.Mutating(MutationTargeting.none)),
    discovery = DiscoverySpec(keywords = Set("relay", "bridge", "forward", "cross-conversation", "parent", "worker", "surface"))
  )

  override def executeResult(input: RelayMessageInput, context: ToolContext): Task[ToolResult[TextToolOutput]] =
    context.sigil.withDB(_.conversations.transaction(_.get(input.conversationId))).flatMap {
      case None =>
        Task.pure(ToolResult.failure(
          message = s"relay_message: conversation `${input.conversationId.value}` not found.",
          hint    = Some("Relay only into a conversation that exists and you have joined.")
        ))
      case Some(target) if !target.participants.exists(_.id == context.caller) =>
        Task.pure(ToolResult.failure(
          message = s"relay_message: you are not a participant of conversation `${input.conversationId.value}`.",
          hint    = Some(
            "You may relay only into conversations you have joined (the bridge is membership-scoped). " +
              "If you delegated this work, you are a member of the worker conversation; the user-facing " +
              "conversation is your own."
          )
        ))
      case Some(target) =>
        resolveAddressees(input.addressees, target.participants.map(_.id)) match {
          case Left(unresolved) =>
            Task.pure(ToolResult.failure(
              message = s"relay_message: addressee(s) ${unresolved.mkString(", ")} are not participants of " +
                s"conversation `${input.conversationId.value}`.",
              hint = Some("Address only participants of the target conversation, or omit `addressees` to broadcast.")
            ))
          case Right(resolved) =>
            val msg = Message(
              participantId  = context.caller,
              conversationId = target._id,
              topicId        = target.currentTopicId,
              content        = Vector(ResponseContent.Text(input.content)),
              state          = EventState.Complete,
              role           = MessageRole.Standard,
              addressees     = resolved,
              source         = Some(s"relay:${context.conversation.id.value}")
            )
            context.sigil.publish(msg).map { _ =>
              val to = resolved.map(s => s" to ${s.map(_.value).mkString(", ")}").getOrElse("")
              ToolResult.Success(TextToolOutput(s"Relayed message into conversation '${target._id.value}'$to."))
            }
        }
    }

  /** Resolve requested addressee id-values against the target
    * conversation's participant ids. `None`/empty requested → `None`
    * (broadcast). Otherwise every requested value must match a
    * participant; unmatched values are returned `Left` so the caller
    * can't silently broadcast a message it meant to direct. */
  private def resolveAddressees(requested: Option[List[String]],
                                members: List[ParticipantId]): Either[List[String], Option[Set[ParticipantId]]] =
    requested.map(_.filter(_.nonEmpty)) match {
      case None             => Right(None)
      case Some(Nil)        => Right(None)
      case Some(values) =>
        val byValue = members.map(p => p.value -> p).toMap
        val unresolved = values.filterNot(byValue.contains)
        if (unresolved.nonEmpty) Left(unresolved)
        else Right(Some(values.flatMap(byValue.get).toSet))
    }
}
