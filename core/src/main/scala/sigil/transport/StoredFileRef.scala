package sigil.transport

import fabric.rw.*
import lightdb.id.Id
import sigil.SpaceId
import sigil.conversation.Conversation
import sigil.storage.{StoredFile, StoredFileCategory}

/**
 * Typed payload that rides a chunked file transfer over the durable socket (see
 * [[DurableFileChannel]]). It carries exactly the routing/persistence intent the framework can't
 * derive from the bytes themselves — the rest of the envelope (content type, size, file name)
 * travels as HTTP-semantic headers on the transfer.
 *
 * On upload (client→server) `space`/`category`/`title`/`conversationId`/`language` direct where and
 * how the bytes are persisted; `fileId` is `None`. On download (server→client) `fileId` identifies
 * the source [[StoredFile]] and the descriptive fields populate the client's file chip.
 */
case class StoredFileRef(space: SpaceId,
                         category: StoredFileCategory = StoredFileCategory.UserAttachment,
                         fileId: Option[Id[StoredFile]] = None,
                         conversationId: Option[Id[Conversation]] = None,
                         title: Option[String] = None,
                         language: Option[String] = None) derives RW
