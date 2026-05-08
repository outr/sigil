package sigil.browser

import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import lightdb.util.Nowish
import rapid.Unique
import sigil.conversation.{Conversation, Topic}
import sigil.event.{Event, MessageRole, MessageVisibility}
import sigil.participant.ParticipantId
import sigil.signal.EventState
import sigil.storage.StoredFile

/**
 * Durable record of the browser's per-conversation state. Created
 * when a [[BrowserController]] opens for a conversation; settled to
 * `Complete` when the controller disposes. Mutated in place between
 * tool calls via [[BrowserStateDelta]] so streaming UIs render the
 * URL / title / loading transitions in real time.
 *
 *   - `url` / `title` — last-observed page identity. `None` on a
 *     fresh controller before the first navigate.
 *   - `loading` — `true` between a navigation request and the page's
 *     load event. UIs render a spinner.
 *   - `screenshotFileId` — id of the most-recently captured screenshot.
 *   - `htmlFileId` — id of the page's normalized (jSoup-cleaned)
 *     HTML in `SigilDB.storedFiles`. The agent's structural-query
 *     tools (`browser_xpath_query`, `browser_text_search`) take the
 *     id and operate against the persisted bytes — the LLM never
 *     receives the raw HTML in its context.
 */
case class BrowserState(participantId: ParticipantId,
                        conversationId: Id[Conversation],
                        topicId: Id[Topic],
                        timestamp: Timestamp = Timestamp(Nowish()),
                        url: Option[String] = None,
                        title: Option[String] = None,
                        loading: Boolean = false,
                        screenshotFileId: Option[Id[StoredFile]] = None,
                        htmlFileId: Option[Id[StoredFile]] = None,
                        state: EventState = EventState.Active,
                        role: MessageRole = MessageRole.Standard,
                        override val visibility: MessageVisibility = MessageVisibility.All,
                        override val origin: Option[Id[Event]] = None,
                        override val source: Option[String] = None,
                        override val contextFrame: Option[sigil.conversation.ContextFrame] = None,
                        _id: Id[Event] = Event.id())
  extends Event derives RW {
  override def withState(state: EventState): Event = copy(state = state)
  override def withOrigin(origin: Option[Id[Event]]): Event = copy(origin = origin)
  override def withContextFrame(contextFrame: Option[sigil.conversation.ContextFrame]): Event = copy(contextFrame = contextFrame)
  override def withConversationId(conversationId: lightdb.id.Id[sigil.conversation.Conversation]): sigil.event.Event = copy(conversationId = conversationId)
}

object BrowserState {
  def id(value: String = Unique()): Id[Event] = Id(value)
}
