package sigil.transport

import fabric.rw.*
import lightdb.id.Id
import rapid.{Stream, Task}
import sigil.Sigil
import sigil.participant.ParticipantId
import sigil.signal.{RequestStoredFile, Signal}
import sigil.storage.StoredFile
import spice.http.durable.{DurableSocket, ReceivedFile}

import java.nio.file.Files

/**
 * Per-connection bridge between a [[DurableSocket]]'s typed file facet and Sigil's stored-file
 * store. Inbound transfers (client→server uploads) are persisted via [[Sigil.storeBytes]] under the
 * payload's space; [[send]] streams a stored file's bytes back out (server→client download), each
 * chunked over the binary channel so neither end is bound by a single websocket-frame cap.
 *
 * This complements — does not replace — the one-shot base64 [[sigil.signal.SaveStoredFile]] /
 * [[sigil.signal.StoredFileContent]] signals, which remain the small-file fast path.
 *
 * Following the framework's transport split (framework owns the wire primitive; apps drive routing),
 * apps construct one of these per attached viewer alongside their [[DurableSocketSink]] and route
 * [[RequestStoredFile]] to [[handleRequest]]. `chain` is the viewer's participant chain, used to
 * authorize downloads through [[Sigil.fetchStoredFile]].
 */
final class DurableFileChannel[C: RW, Info: RW](host: Sigil,
                                                socket: DurableSocket[C, Signal, Info],
                                                chain: List[ParticipantId]) {
  private val channel = socket.files[StoredFileRef]

  // Persist inbound uploads as each completes; a failure is logged, never propagated to the socket.
  channel.onFile.attach { received =>
    persist(received).handleError { t =>
      scribe.warn(s"Failed to persist uploaded file ${received.transferId}: ${t.getMessage}")
      Task.unit
    }.start()
  }

  /** Persist a received upload under its payload's space, then delete the receiver's temp spool. */
  def persist(received: ReceivedFile[StoredFileRef]): Task[StoredFile] = Task.defer {
    val bytes = Files.readAllBytes(received.path)
    val contentType = received.contentType.getOrElse("application/octet-stream")
    val ref = received.value
    val metadata = Map.newBuilder[String, String]
    ref.title.foreach(t => metadata += "title" -> t)
    ref.language.foreach(l => metadata += "language" -> l)
    ref.conversationId.foreach(c => metadata += "conversationId" -> c.value)
    host.storeBytes(ref.space, bytes, contentType, metadata = metadata.result(), category = ref.category).map { sf =>
      Files.deleteIfExists(received.path)
      sf
    }
  }

  /** Stream a stored file's bytes to the peer (server→client download), chunked over the binary
    * channel. Fails if the file doesn't exist or `chain` isn't authorized for its space. */
  def send(fileId: Id[StoredFile]): Task[String] =
    host.fetchStoredFile(fileId, chain).flatMap {
      case None => Task.error(new RuntimeException(s"Stored file $fileId not found or not accessible"))
      case Some((file, bytes)) =>
        val ref = StoredFileRef(
          space = file.space,
          category = file.category,
          fileId = Some(file._id),
          title = file.metadata.get("title"),
          language = file.metadata.get("language")
        )
        channel.send(file._id.value, file.contentType, Stream.emits(bytes.toIndexedSeq), ref)
    }

  /** Route a [[RequestStoredFile]] pull to a chunked download. */
  def handleRequest(request: RequestStoredFile): Task[Unit] = send(request.fileId).unit
}
