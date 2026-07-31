package sigil.tooling

import rapid.Task
import sigil.tool.ToolContext
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
 * collapses that into [[withSessionTyped]] and [[withOpenDocumentTyped]]
 * so the per-tool body is just "call session.X, format the result."
 */
trait LspToolSupport extends sigil.tool.Tool {
  protected def manager: LspManager

  /** Guard the LSP tool's input against schema-leaked placeholder
    * values ("filePath": "string", etc.) — see
    * [[sigil.tool.PlaceholderInputDetector]]. Returns a placeholder-
    * rejection failure when any of `fields` is a recognised
    * placeholder; returns `None` to let the tool run. Tools call this
    * in their `executeResult` prelude before `withOpenDocumentTyped` /
    * `withSessionTyped`. */
  protected def validatePlaceholders(fields: (String, String)*): Option[String] =
    sigil.tool.PlaceholderInputDetector.validateNoPlaceholders(fields*)

  protected def reply(context: ToolContext, text: String, isError: Boolean): Event =
    Message(
      participantId = context.caller,
      conversationId = context.conversation.id,
      topicId = context.conversation.currentTopicId,
      content = Vector(ResponseContent.Text(text)),
      state = EventState.Complete,
      role = MessageRole.Tool,
      visibility = MessageVisibility.All
    )

  /** Typed-session entrypoint for tools whose `executeResult`
    * returns a typed `Output`. Runs `body` against an open session
    * and returns its typed `Output`. Error paths (no config / spawn
    * failure / RPC error) get routed to the caller's `onError`
    * mapping — typically a sentinel variant on the tool's Output
    * enum. Lets each tool's typed shape carry its own error states
    * without forcing a generic envelope. */
  protected def withSessionTyped[Output](languageId: String,
                                         filePath: String,
                                         context: ToolContext,
                                         onError: String => Output)
                                        (body: (LspSession, String, String) => Task[Output]): Task[Output] =
    validatePlaceholders("languageId" -> languageId, "filePath" -> filePath) match {
      case Some(reason) => Task.pure(onError(reason))
      case None         => withSessionTypedResolved(languageId, filePath, context, onError)(body)
    }

  private def withSessionTypedResolved[Output](languageId: String,
                                               filePath: String,
                                               context: ToolContext,
                                               onError: String => Output)
                                              (body: (LspSession, String, String) => Task[Output]): Task[Output] =
    manager.configFor(languageId).flatMap {
      case None => Task.pure(onError(s"No LspServerConfig persisted for '$languageId'."))
      case Some(config) =>
        val root = manager.resolveRoot(filePath, config.rootMarkers)
        val uri = new File(filePath).toURI.toString
        manager.session(languageId, root).flatMap { session =>
          session.setStatusCallback(Some(text =>
            context.reportProgress(text).handleError(_ => Task.unit).startUnit()
          ))
          body(session, uri, root).map { result =>
            session.setStatusCallback(None)
            result
          }.handleError { t =>
            session.setStatusCallback(None)
            Task.error(t)
          }
        }.handleError(e => Task.pure(onError(s"LSP error: ${e.getMessage}")))
    }

  /** Open-document variant of [[withSessionTyped]] for tools whose
    * `executeResult` returns a typed `Output`. Calls `didOpen` on the
    * target file before running `body`. */
  protected def withOpenDocumentTyped[Output](languageId: String,
                                              filePath: String,
                                              context: ToolContext,
                                              onError: String => Output)
                                             (body: (LspSession, String) => Task[Output]): Task[Output] =
    withSessionTyped(languageId, filePath, context, onError) { (session, uri, _) =>
      val text = scala.util.Try(Files.readString(Paths.get(filePath))).toOption.getOrElse("")
      session.didOpen(uri, languageId, text).flatMap(_ => body(session, uri))
    }

  /** [[withSessionTyped]] with the error path baked in to throw a
    * `RuntimeException`. Read-only LSP tools whose typed `Output`
    * has no sentinel error variant let the framework's agent-loop
    * error handler render the failure instead of carrying it in the
    * result shape. */
  protected def withSessionOrThrow[Output](languageId: String,
                                           filePath: String,
                                           context: ToolContext)
                                          (body: (LspSession, String, String) => Task[Output]): Task[Output] =
    withSessionTyped[Output](languageId, filePath, context, onError = msg => throw new RuntimeException(msg))(body)

  /** [[withOpenDocumentTyped]] with the error path baked in to throw
    * a `RuntimeException`. Read-only navigation / inspection tools
    * whose typed `Output` has no sentinel error variant let the
    * framework's agent-loop error handler render the failure instead
    * of carrying it in the result shape. */
  protected def withOpenDocumentOrThrow[Output](languageId: String,
                                                filePath: String,
                                                context: ToolContext)
                                               (body: (LspSession, String) => Task[Output]): Task[Output] =
    withOpenDocumentTyped[Output](languageId, filePath, context, onError = msg => throw new RuntimeException(msg))(body)
}
