package sigil.tooling

import fabric.rw.*
import fabric.io.JsonFormatter
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.{Event, Message, MessageRole, MessageVisibility}
import sigil.signal.EventState
import sigil.tool.model.ResponseContent

import java.io.File
import java.nio.file.{Files, Paths}

/**
 * Shared plumbing for the agent-facing LSP tools. Every tool follows
 * the same shape: look up a config by language id, resolve a project
 * root, get a session, open the target document so the server has it
 * indexed, run a single RPC, format the response. This trait
 * collapses that into [[withSession]] and [[withOpenDocument]] so
 * the per-tool body is just "call session.X, format the result."
 *
 * Sigil bug #85 — sets `toolchain = Some("lsp")` on every mixed-in
 * tool so [[sigil.Sigil.findCapabilities]]'s ranker can boost their
 * score when the conversation has an LSP runtime active (Metals
 * running, ts-server attached, …). Apps register `"lsp"` in their
 * [[sigil.Sigil.activeToolchains]] response when an LSP session is
 * live for the conversation's workspace.
 */
trait LspToolSupport extends sigil.tool.Tool {
  protected def manager: LspManager

  override def toolchain: Option[String] = Some("lsp")

  /** Run `body` against a session for `languageId` rooted appropriately
    * for `filePath`. Wraps every error path (no config / spawn failure
    * / RPC error) into a single Stream emission carrying an explanatory
    * Message — agents always receive a structured tool-result, never
    * an exception. */
  protected def withSession(languageId: String,
                            filePath: String,
                            context: TurnContext)
                           (body: (LspSession, String, String) => Task[String]): Stream[Event] = {
    val task = manager.configFor(languageId).flatMap {
      case None =>
        Task.pure(reply(context, s"No LspServerConfig persisted for '$languageId'.", isError = true))
      case Some(config) =>
        val root = manager.resolveRoot(filePath, config.rootMarkers)
        val uri = new File(filePath).toURI.toString
        manager.session(languageId, root).flatMap { session =>
          body(session, uri, root).map(text => reply(context, text, isError = false))
        }.handleError { e =>
          Task.pure(reply(context, s"LSP error: ${e.getMessage}", isError = true))
        }
    }
    Stream.force(task.map(Stream.emit))
  }

  /** Convenience around [[withSession]] that also calls `didOpen` on
    * the target file before running `body`, reading the file's bytes
    * fresh from disk. Most navigation / completion / hover tools
    * want this. Returns "" body if the file doesn't exist or can't
    * be read — the LSP call itself will fail in a meaningful way. */
  protected def withOpenDocument(languageId: String,
                                 filePath: String,
                                 context: TurnContext)
                                (body: (LspSession, String) => Task[String]): Stream[Event] =
    withSession(languageId, filePath, context) { (session, uri, _) =>
      val text = scala.util.Try(Files.readString(Paths.get(filePath))).toOption.getOrElse("")
      session.didOpen(uri, languageId, text).flatMap(_ => body(session, uri))
    }

  protected def reply(context: TurnContext, text: String, isError: Boolean): Event =
    Message(
      participantId = context.caller,
      conversationId = context.conversation.id,
      topicId = context.conversation.currentTopicId,
      content = Vector(ResponseContent.Text(text)),
      state = EventState.Complete,
      role = MessageRole.Tool,
      visibility = MessageVisibility.All
    )

  /** JSON-emitting variant of [[reply]] for tools that produce
    * structured [[sigil.tooling.types]] values. Serializes `value`
    * to compact JSON via fabric so apps consuming the wire receive
    * a typed-shape payload (Bug #9 phase 6) — no regex-parsing of
    * rendered text required.
    *
    * The tool's body becomes "compute the typed value, return it";
    * `replyJsonStream` wraps it into a Stream\[Event] for the typed
    * tool's `executeTyped` return. */
  protected def replyJson[T: RW](context: TurnContext, value: T): Event = {
    val rw   = summon[RW[T]]
    val json = rw.read(value)
    reply(context, JsonFormatter.Compact(json), isError = false)
  }

  /** Typed variant of [[withSession]] for tools that extend
    * `TypedOutputTool[I, O]`. Runs `body` against an open session
    * and returns its typed `Output`. Error paths (no config / spawn
    * failure / RPC error) get routed to the caller's `onError`
    * mapping — typically a sentinel variant on the tool's Output
    * enum. Lets each tool's typed shape carry its own error states
    * without forcing a generic envelope. */
  protected def withSessionTyped[Output](languageId: String,
                                         filePath: String,
                                         context: TurnContext,
                                         onError: String => Output)
                                        (body: (LspSession, String, String) => Task[Output]): Task[Output] =
    manager.configFor(languageId).flatMap {
      case None => Task.pure(onError(s"No LspServerConfig persisted for '$languageId'."))
      case Some(config) =>
        val root = manager.resolveRoot(filePath, config.rootMarkers)
        val uri = new File(filePath).toURI.toString
        manager.session(languageId, root).flatMap(body(_, uri, root))
          .handleError(e => Task.pure(onError(s"LSP error: ${e.getMessage}")))
    }

  /** Typed variant of [[withOpenDocument]] for `TypedOutputTool[I, O]`
    * tools. Calls `didOpen` on the target file before running `body`. */
  protected def withOpenDocumentTyped[Output](languageId: String,
                                              filePath: String,
                                              context: TurnContext,
                                              onError: String => Output)
                                             (body: (LspSession, String) => Task[Output]): Task[Output] =
    withSessionTyped(languageId, filePath, context, onError) { (session, uri, _) =>
      val text = scala.util.Try(Files.readString(Paths.get(filePath))).toOption.getOrElse("")
      session.didOpen(uri, languageId, text).flatMap(_ => body(session, uri))
    }
}
