package sigil.signal

import fabric.rw.*
import lightdb.id.Id
import sigil.conversation.Conversation

/**
 * Client→server upload Notice. UIs send raw bytes (base64-encoded
 * for wire safety) plus a title and content type; the server
 * persists via `Sigil.storeBytes` under the resolved space (per the
 * caller's chain) and broadcasts a [[StoredFileCreated]] back so
 * other clients see the new file.
 *
 * `conversationId` is optional — apps that scope storage per
 * conversation include it; apps with a shared library leave it
 * `None`.
 */
case class SaveStoredFile(title: String,
                          contentType: String,
                          base64Data: String,
                          language: Option[String] = None,
                          conversationId: Option[Id[Conversation]] = None) extends Notice derives RW
