package sigil.event

import fabric.rw.*

/**
 * One page of conversation history, as returned by
 * [[sigil.Sigil.eventsFor]].
 *
 * `events` is the page's content in chronological order (oldest
 * first within the page) — every [[Event]] in the requested window
 * for one page slice. When [[sigil.Sigil.eventsFor]] was called with
 * a `maxMessages` cap the page holds up to that many [[Message]]
 * events PLUS every non-Message event interleaved between them
 * (ToolInvoke, ToolResults, ModeChange, TopicChange, ...). With no
 * cap the page holds every event in the window.
 *
 * `hasMore` is `true` when older events exist beyond this page —
 * the signal a paging UI uses to decide whether a "load older"
 * affordance applies. It is always `false` when no `maxMessages`
 * cap was supplied (an uncapped read returns the whole window in a
 * single page).
 */
case class EventsPage(events: List[Event], hasMore: Boolean) derives RW
