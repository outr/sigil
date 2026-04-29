package sigil.browser.tool

import rapid.{Stream, Task}
import sigil.{GlobalSpace, TurnContext}
import sigil.browser.BrowserStateDelta
import sigil.event.{Event, Message, MessageRole}
import sigil.signal.EventState
import sigil.storage.StoredFile
import sigil.tool.model.ResponseContent
import sigil.tool.{ToolExample, ToolName, TypedTool}

import java.nio.file.{Files, Path}
import scala.concurrent.duration.*

/**
 * Capture a PNG screenshot of the current page, persist it via
 * [[sigil.Sigil.storeBytes]] under [[GlobalSpace]], and emit a
 * `Message(role = Tool)` whose content is a single
 * [[ResponseContent.Image]] referencing the stored file.
 *
 * The screenshot lands in chat directly — both agent and user see it
 * — and the live URL the UI fetches resolves through the framework's
 * storage route filter, so the backend (local FS / S3 / future CDN)
 * is invisible to the client.
 */
final class BrowserScreenshotTool extends TypedTool[BrowserScreenshotInput](
  name = ToolName("browser_screenshot"),
  description =
    """Take a screenshot of the current page. Returns the rendered image as part of the chat (both you and the user see it).
      |Use when text-only scraping isn't enough — graphical UIs, layout-dependent pages, error states.""".stripMargin,
  examples = List(
    ToolExample("Default screenshot", BrowserScreenshotInput()),
    ToolExample("Wait 5s for animations", BrowserScreenshotInput(waitSeconds = 5))
  ),
  keywords = Set("browser", "screenshot", "image", "capture", "render")
) {

  override protected def executeTyped(input: BrowserScreenshotInput, ctx: TurnContext): Stream[Event] =
    Stream.force(
      for {
        controller <- BrowserToolBase.resolveController(ctx)
        // Resize viewport if requested.
        _          <- controller.run { browser =>
                        (input.maxWidth, input.maxHeight) match {
                          case (Some(w), Some(h)) => browser.setViewportSize(w, h)
                          case _ => Task.unit
                        }
                      }
        // Capture to a tempfile, read bytes, hand to Sigil.storeBytes.
        bytes      <- controller.run { browser =>
                        Task.defer {
                          val tmp = Files.createTempFile("sigil-screenshot-", ".png")
                          browser.screenshotAs(tmp, afterLoadDelay = Some(input.waitSeconds.seconds))
                            .map { _ =>
                              val read = Files.readAllBytes(tmp)
                              try Files.deleteIfExists(tmp) catch { case _: Throwable => () }
                              read
                            }
                        }
                      }
        stored     <- ctx.sigil.storeBytes(GlobalSpace, bytes, "image/png",
                        metadata = Map(
                          "kind" -> "browser-screenshot",
                          "conversationId" -> ctx.conversation.id.value
                        ))
        // Emit a delta on the BrowserState so subscribers see the new
        // screenshot reference.
        _          <- ctx.sigil.publish(BrowserStateDelta(
                        target           = controller.stateId,
                        conversationId   = ctx.conversation.id,
                        screenshotFileId = Some(stored._id)
                      ))
      } yield Stream.emit[Event](screenshotMessage(stored, ctx))
    )

  private def screenshotMessage(stored: StoredFile, ctx: TurnContext): Message = Message(
    participantId  = ctx.caller,
    conversationId = ctx.conversation.id,
    topicId        = ctx.conversation.currentTopicId,
    content        = Vector(ResponseContent.Image(
      url = ctx.sigil.storageUrl(stored),
      altText = Some(s"Browser screenshot at ${java.time.Instant.now}")
    )),
    state          = EventState.Complete,
    role           = MessageRole.Tool
  )
}
