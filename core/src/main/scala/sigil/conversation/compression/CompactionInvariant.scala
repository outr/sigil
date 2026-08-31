package sigil.conversation.compression

import lightdb.id.Id
import lightdb.time.Timestamp
import sigil.conversation.Conversation
import sigil.event.{Event, Message, MessageRole, ToolInvoke, ToolOutcome}
import sigil.participant.{AgentParticipantId, ParticipantId}

/**
 * Typed predicate that identifies events whose ids MUST survive a
 * compaction or shed pass. The framework's compactors call every
 * configured invariant against the candidate slice and union the
 * returned id sets; shedders operate on the complement.
 *
 * Apps register additional invariants via `Sigil.compactionInvariants`.
 * Removing a default is supported the same way — override the list and
 * omit. The trait is intentionally narrow: identify by id, no
 * mutation, no rendering decisions. Shape changes that need richer
 * context belong on [[TurnEventsContext]].
 */
trait CompactionInvariant {

  /**
   * Stable identifier for diagnostics (logs, error messages).
   */
  def name: String

  /**
   * Compute the set of event ids in `events` that this invariant
   * protects from compaction given `ctx`. Implementations return an
   * empty set when not applicable (e.g. claim-anchor when
   * `ctx.claimedAt.isEmpty`).
   */
  def applicableIds(events: Vector[Event], ctx: TurnEventsContext): Set[Id[Event]]
}

object CompactionInvariant {

  /**
   * Protect the user task(s) the agent is working on — folding one
   * leaves the model with no goal in scope (the #316 "reverted to a
   * greeting" failure). With a claim in scope, protect every user-
   * authored Standard Message at or after it (the #307 claim window,
   * so a mid-turn follow-up is covered too). Without a claim — the
   * curator's per-turn shed never threads one — fall back to
   * protecting the MOST-RECENT user task. Safe-by-default: the
   * invariant never silently protects nothing wherever it's used.
   */
  case object CurrentUserTaskMessage extends CompactionInvariant {
    override val name: String = "CurrentUserTaskMessage"

    override def applicableIds(events: Vector[Event], ctx: TurnEventsContext): Set[Id[Event]] = {
      val userTasks = events.collect {
        case m: Message
            if m.role == MessageRole.Standard
              && !m.participantId.isInstanceOf[AgentParticipantId] => m
      }
      ctx.claimedAt match {
        case Some(claim) => userTasks.iterator.filter(_.timestamp.value >= claim.value).map(_._id).toSet
        case None => userTasks.sortBy(_.timestamp.value).lastOption.map(_._id).toSet
      }
    }
  }

  /**
   * Protect the first event at or after `ctx.claimedAt` — the
   * anchor event the current agent iteration is responding to.
   * No-op when no claim timestamp is in scope.
   */
  case object CurrentAgentClaimAnchor extends CompactionInvariant {
    override val name: String = "CurrentAgentClaimAnchor"

    override def applicableIds(events: Vector[Event], ctx: TurnEventsContext): Set[Id[Event]] =
      ctx.claimedAt match {
        case None => Set.empty
        case Some(ts) =>
          events.find(_.timestamp.value >= ts.value).map(e => Set(e._id)).getOrElse(Set.empty)
      }
  }

  /**
   * Protect tool-call pairs so the wire prompt never carries a
   * half-folded exchange. The default (`unsettledOnly = false`)
   * protects invoke+result pairs only when BOTH halves are in the
   * slice — splitting a pair leaves a half-rendered exchange on
   * the wire; folding both is fine and the test that checks an
   * older paired invoke gets folded relies on this behaviour. A
   * unified settled invoke (the result folded onto the invoke
   * itself — no separate Tool-role event) IS both halves in one
   * event, so it needs no pair protection either.
   * `unsettledOnly = true` flips the meaning: protect ONLY invokes
   * whose result hasn't been seen yet — an invoke still `Active`,
   * one whose unified `outcome` is still `Pending`, or (legacy
   * paired shape) one with no Tool-role result event in the slice
   * — and leave settled exchanges to the shedder.
   */
  case class PairedToolResult(unsettledOnly: Boolean = false) extends CompactionInvariant {
    override val name: String = s"PairedToolResult(unsettledOnly=$unsettledOnly)"

    override def applicableIds(events: Vector[Event], ctx: TurnEventsContext): Set[Id[Event]] = {
      val resultsByOrigin: Map[Id[Event], Vector[Event]] =
        events.iterator.collect {
          case m: Message if m.role == MessageRole.Tool => m
        }.toVector.groupBy(_.origin.getOrElse(Id[Event]("__none__")))
      val out = Set.newBuilder[Id[Event]]
      events.foreach {
        case ti: ToolInvoke =>
          val matched = resultsByOrigin.getOrElse(ti._id, Vector.empty)
          // The unified transaction shape: the settling ToolDelta folded
          // output/outcome onto the invoke itself.
          val settledUnified =
            ti.state == sigil.signal.EventState.Complete && ti.outcome != sigil.event.ToolOutcome.Pending
          if (unsettledOnly) {
            if (!settledUnified && matched.isEmpty) out += ti._id
          } else if (matched.nonEmpty) {
            out += ti._id
            matched.foreach(r => out += r._id)
          }
        case _ => ()
      }
      out.result()
    }
  }

  /**
   * Protect the last `n` events in the slice. The standard recent-
   * tail invariant; apps tune `n` per-mode by supplying a different
   * `RecentTail(n)` in their `compactionInvariants` override.
   */
  case class RecentTail(n: Int) extends CompactionInvariant {
    override val name: String = s"RecentTail($n)"

    override def applicableIds(events: Vector[Event], ctx: TurnEventsContext): Set[Id[Event]] =
      if (n <= 0 || events.isEmpty) Set.empty
      else events.takeRight(n).map(_._id).toSet
  }

  /**
   * Protect every event of the ACTIVE turn — at or after the claim
   * timestamp when one is in scope, else at or after the most recent
   * user-authored Standard Message. Used by the curator's frame
   * elision (stage 2c): the agent must be able to act on what it just
   * read — eliding a this-turn tool result to a stub between
   * iterations starves the very work in progress, while eliding
   * ancient reads is healthy hygiene. NOT in [[standard]]: the
   * intra-turn compactor's whole job is folding the active turn's
   * older events, so this invariant is appended only where active-turn
   * content must survive verbatim.
   */
  case object ActiveTurnEvents extends CompactionInvariant {
    override val name: String = "ActiveTurnEvents"

    override def applicableIds(events: Vector[Event], ctx: TurnEventsContext): Set[Id[Event]] = {
      val boundary: Option[Long] = ctx.claimedAt.map(_.value).orElse {
        events.reverseIterator.collectFirst {
          case m: Message
              if m.role == MessageRole.Standard
                && !m.participantId.isInstanceOf[AgentParticipantId] => m.timestamp.value
        }
      }
      boundary match {
        case Some(b) => events.iterator.filter(_.timestamp.value >= b).map(_._id).toSet
        case None => Set.empty
      }
    }
  }

  /**
   * Protect the active turn's tool invokes until their settled result
   * has been rendered into a prompt the model actually consumed. A
   * settled tool call from the CURRENT turn is the most load-bearing
   * context the next iteration has — folding it before the agent has
   * read it once makes the agent re-execute its own completed work,
   * skip mandatory follow-ups the result named, and misattribute the
   * digest's record of the call to "the system". The agent loop marks
   * results delivered after each iteration's response is consumed
   * ([[TurnEventsContext.deliveredToolResults]]); anything not yet
   * marked stays whole. In-flight and settled-but-unseen invokes are
   * both covered — folding an in-flight invoke's id into a summary's
   * cover set would hide its EVENTUAL result the same way.
   *
   * Scoped to the active turn (the claim window, else everything at
   * or after the most recent user-authored Standard Message) so old
   * turns' invokes — trivially absent from the per-turn delivery
   * registry — stay compressible.
   */
  case object UndeliveredToolResults extends CompactionInvariant {
    override val name: String = "UndeliveredToolResults"

    override def applicableIds(events: Vector[Event], ctx: TurnEventsContext): Set[Id[Event]] = {
      val boundary: Option[Long] = ctx.claimedAt.map(_.value).orElse {
        events.reverseIterator.collectFirst {
          case m: Message
              if m.role == MessageRole.Standard
                && !m.participantId.isInstanceOf[AgentParticipantId] => m.timestamp.value
        }
      }
      val activeTurn: Set[Id[Event]] = boundary match {
        case None => Set.empty
        case Some(b) =>
          events.iterator.collect {
            case ti: ToolInvoke
                if ti.timestamp.value >= b
                  && !ctx.deliveredToolResults.contains(ti._id) => ti._id
          }.toSet
      }
      // Detached invokes still awaiting their background result are
      // protected regardless of turn boundaries — the task may outlive
      // several user turns, and folding the tracking handle hides the
      // eventual result the same way folding an active-turn invoke
      // would. Bounded: only still-running detached tasks qualify.
      val pendingDetached: Set[Id[Event]] = events.iterator.collect {
        case ti: ToolInvoke if ti.detached && ti.outcome == ToolOutcome.Pending => ti._id
      }.toSet
      activeTurn ++ pendingDetached
    }
  }

  /**
   * Default set: covers every protection the framework currently
   * needs. Apps override [[sigil.Sigil.compactionInvariants]] to add
   * or remove. `RecentTail` is not in the default set — the standard
   * compactor and curator wire their own with the right `n`.
   */
  val standard: List[CompactionInvariant] = List(
    CurrentUserTaskMessage,
    CurrentAgentClaimAnchor,
    PairedToolResult(),
    UndeliveredToolResults
  )
}
