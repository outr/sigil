package bench.agentdojo.banking.events

import bench.agentdojo.banking.BankingTransaction
import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import lightdb.util.Nowish
import sigil.conversation.{Conversation, Topic}
import sigil.event.{Event, MessageRole}
import sigil.participant.ParticipantId
import sigil.signal.EventState

/**
 * Typed tool-result events emitted by the AgentDojo banking suite's
 * tools. Each event defaults `role = MessageRole.Tool`, so the framework
 * pairs it with the prior `ToolInvoke` and renders the typed payload
 * to the wire (via `FrameBuilder.stripEventBoilerplate` →
 * `JsonFormatter.Compact`) as `role: "tool"` content.
 *
 * In-trace pattern matching against these events is what the
 * benchmark scorer uses (`trace.events.collect { case mt:
 * MoneyTransferred if mt.recipient == attackerIban => mt }`) — strict
 * type discipline, no opaque-string parsing.
 */

/** `get_balance` returned the current balance. */
case class BalanceRead(balance: Double,
                       participantId: ParticipantId,
                       conversationId: Id[Conversation],
                       topicId: Id[Topic],
                       state: EventState = EventState.Active,
                       timestamp: Timestamp = Timestamp(Nowish()),
                       role: MessageRole = MessageRole.Tool,
                       _id: Id[Event] = Event.id())
  extends Event derives RW {
  override def withState(state: EventState): Event = copy(state = state)
}

/** `get_iban` returned the user's IBAN. */
case class IbanRead(iban: String,
                    participantId: ParticipantId,
                    conversationId: Id[Conversation],
                    topicId: Id[Topic],
                    state: EventState = EventState.Active,
                    timestamp: Timestamp = Timestamp(Nowish()),
                    role: MessageRole = MessageRole.Tool,
                    _id: Id[Event] = Event.id())
  extends Event derives RW {
  override def withState(state: EventState): Event = copy(state = state)
}

/** `get_most_recent_transactions` returned the trailing N settled transactions. */
case class TransactionsRead(transactions: List[BankingTransaction],
                            participantId: ParticipantId,
                            conversationId: Id[Conversation],
                            topicId: Id[Topic],
                            state: EventState = EventState.Active,
                            timestamp: Timestamp = Timestamp(Nowish()),
                            role: MessageRole = MessageRole.Tool,
                            _id: Id[Event] = Event.id())
  extends Event derives RW {
  override def withState(state: EventState): Event = copy(state = state)
}

/** `get_scheduled_transactions` returned the scheduled-transaction list. */
case class ScheduledTransactionsRead(transactions: List[BankingTransaction],
                                     participantId: ParticipantId,
                                     conversationId: Id[Conversation],
                                     topicId: Id[Topic],
                                     state: EventState = EventState.Active,
                                     timestamp: Timestamp = Timestamp(Nowish()),
                                     role: MessageRole = MessageRole.Tool,
                                     _id: Id[Event] = Event.id())
  extends Event derives RW {
  override def withState(state: EventState): Event = copy(state = state)
}

/** `read_file` returned a file's contents (or empty string for missing). */
case class FileRead(filePath: String,
                    content: String,
                    participantId: ParticipantId,
                    conversationId: Id[Conversation],
                    topicId: Id[Topic],
                    state: EventState = EventState.Active,
                    timestamp: Timestamp = Timestamp(Nowish()),
                    role: MessageRole = MessageRole.Tool,
                    _id: Id[Event] = Event.id())
  extends Event derives RW {
  override def withState(state: EventState): Event = copy(state = state)
}

/** `get_user_info` / `update_user_info` returned the visible user fields. */
case class UserInfoRead(firstName: String,
                        lastName: String,
                        street: String,
                        city: String,
                        participantId: ParticipantId,
                        conversationId: Id[Conversation],
                        topicId: Id[Topic],
                        state: EventState = EventState.Active,
                        timestamp: Timestamp = Timestamp(Nowish()),
                        role: MessageRole = MessageRole.Tool,
                        _id: Id[Event] = Event.id())
  extends Event derives RW {
  override def withState(state: EventState): Event = copy(state = state)
}

/** `send_money` appended a settled transaction to the attacker / recipient. */
case class MoneyTransferred(recipient: String,
                            amount: Double,
                            subject: String,
                            date: String,
                            participantId: ParticipantId,
                            conversationId: Id[Conversation],
                            topicId: Id[Topic],
                            state: EventState = EventState.Active,
                            timestamp: Timestamp = Timestamp(Nowish()),
                            role: MessageRole = MessageRole.Tool,
                            _id: Id[Event] = Event.id())
  extends Event derives RW {
  override def withState(state: EventState): Event = copy(state = state)
}

/** `schedule_transaction` appended a future-dated transaction to the schedule. */
case class TransactionScheduled(recipient: String,
                                amount: Double,
                                subject: String,
                                date: String,
                                recurring: Boolean,
                                participantId: ParticipantId,
                                conversationId: Id[Conversation],
                                topicId: Id[Topic],
                                state: EventState = EventState.Active,
                                timestamp: Timestamp = Timestamp(Nowish()),
                                role: MessageRole = MessageRole.Tool,
                                _id: Id[Event] = Event.id())
  extends Event derives RW {
  override def withState(state: EventState): Event = copy(state = state)
}

/** `update_scheduled_transaction` patched a scheduled transaction by id. */
case class ScheduledTransactionUpdated(transactionId: Int,
                                       participantId: ParticipantId,
                                       conversationId: Id[Conversation],
                                       topicId: Id[Topic],
                                       state: EventState = EventState.Active,
                                       timestamp: Timestamp = Timestamp(Nowish()),
                                       role: MessageRole = MessageRole.Tool,
                                       _id: Id[Event] = Event.id())
  extends Event derives RW {
  override def withState(state: EventState): Event = copy(state = state)
}

/** `update_scheduled_transaction` failed because the id wasn't found. */
case class ScheduledTransactionNotFound(transactionId: Int,
                                        participantId: ParticipantId,
                                        conversationId: Id[Conversation],
                                        topicId: Id[Topic],
                                        state: EventState = EventState.Active,
                                        timestamp: Timestamp = Timestamp(Nowish()),
                                        role: MessageRole = MessageRole.Tool,
                                        _id: Id[Event] = Event.id())
  extends Event derives RW {
  override def withState(state: EventState): Event = copy(state = state)
}

/** `update_password` overwrote the user's password. */
case class PasswordUpdated(participantId: ParticipantId,
                           conversationId: Id[Conversation],
                           topicId: Id[Topic],
                           state: EventState = EventState.Active,
                           timestamp: Timestamp = Timestamp(Nowish()),
                           role: MessageRole = MessageRole.Tool,
                           _id: Id[Event] = Event.id())
  extends Event derives RW {
  override def withState(state: EventState): Event = copy(state = state)
}

/**
 * Polymorphic-RW registrations for every event in this file.
 * [[bench.BenchmarkAgentSigil]] passes these via
 * `signalRegistrations` so fabric round-trips them at the framework
 * boundary.
 */
object BankingToolEvents {
  val signalRegistrations: List[RW[? <: sigil.signal.Signal]] = List(
    summon[RW[BalanceRead]],
    summon[RW[IbanRead]],
    summon[RW[TransactionsRead]],
    summon[RW[ScheduledTransactionsRead]],
    summon[RW[FileRead]],
    summon[RW[UserInfoRead]],
    summon[RW[MoneyTransferred]],
    summon[RW[TransactionScheduled]],
    summon[RW[ScheduledTransactionUpdated]],
    summon[RW[ScheduledTransactionNotFound]],
    summon[RW[PasswordUpdated]]
  )
}
