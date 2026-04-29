package sigil.browser

import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import lightdb.util.Nowish
import sigil.conversation.Conversation
import sigil.event.Event
import sigil.signal.Delta
import sigil.storage.StoredFile

/**
 * Transient update to a [[BrowserState]] target. Tools emit one of
 * these after every action they perform on the live browser so
 * streaming subscribers see the URL / title / loading / screenshot /
 * persisted-html transitions without polling.
 *
 * Fields are `Option` because each delta typically updates only a
 * subset — a navigate emits `url + title + loading=false`, a save_html
 * emits only `htmlFileId`, a screenshot emits only `screenshotFileId`.
 * `None` means "leave the field untouched."
 */
case class BrowserStateDelta(target: Id[Event],
                             conversationId: Id[Conversation],
                             url: Option[String] = None,
                             title: Option[String] = None,
                             loading: Option[Boolean] = None,
                             screenshotFileId: Option[Id[StoredFile]] = None,
                             htmlFileId: Option[Id[StoredFile]] = None) extends Delta derives RW {
  override def apply(target: Event): Event = target match {
    case b: BrowserState =>
      b.copy(
        url = url.orElse(b.url),
        title = title.orElse(b.title),
        loading = loading.getOrElse(b.loading),
        screenshotFileId = screenshotFileId.orElse(b.screenshotFileId),
        htmlFileId = htmlFileId.orElse(b.htmlFileId),
        timestamp = Timestamp(Nowish())
      )
    case other => other
  }
}
