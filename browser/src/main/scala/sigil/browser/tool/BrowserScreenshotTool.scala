package sigil.browser.tool

import fabric.rw.*
import rapid.Task
import sigil.browser.BrowserStateDelta
import sigil.browser.WebBrowserMode
import sigil.tool.{Tool, ToolExample, ToolName, ToolResult}
import sigil.GlobalSpace
import sigil.tool.ToolContext

import java.nio.file.Files
import scala.concurrent.duration.*

/**
 * Capture a PNG screenshot of the current page, persist it via
 * [[sigil.Sigil.storeBytes]] under [[GlobalSpace]], and resolve to a
 * [[BrowserScreenshotOutput]] carrying the stored file id and a
 * storage-route URL.
 *
 * The capture is surfaced live to subscribers via the
 * [[BrowserStateDelta]] published during execution; the URL resolves
 * through the framework's storage route filter, so the backend
 * (local FS / S3 / future CDN) is invisible to the client.
 */
final class BrowserScreenshotTool extends Tool {
  type Input  = BrowserScreenshotInput
  type Output = BrowserScreenshotOutput
  val inputRW  = summon[RW[BrowserScreenshotInput]]
  val outputRW = summon[RW[BrowserScreenshotOutput]]

  val name = ToolName("browser_screenshot")
  val description =
    """Take a screenshot of the current page. Returns the rendered image as part of the chat (both you and the user see it).
      |Use when text-only scraping isn't enough — graphical UIs, layout-dependent pages, error states.""".stripMargin
  override val examples = List(
    ToolExample("Default screenshot", BrowserScreenshotInput()),
    ToolExample("Wait 5s for animations", BrowserScreenshotInput(waitSeconds = 5))
  )
  override val modes = Set(WebBrowserMode.id)
  override val keywords = Set("browser", "screenshot", "image", "capture", "render")

  override def executeResult(input: BrowserScreenshotInput,
                             ctx: ToolContext): Task[ToolResult[BrowserScreenshotOutput]] =
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
    } yield ToolResult.Success(BrowserScreenshotOutput(
      fileId  = stored._id.value,
      url     = ctx.sigil.storageUrl(stored).toString,
      altText = s"Browser screenshot at ${java.time.Instant.now}"
    ))
}
