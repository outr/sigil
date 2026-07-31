package sigil.browser.tool

import fabric.*
import fabric.rw.*
import rapid.Task
import robobrowser.RoboBrowser
import sigil.browser.BrowserStateDelta
import sigil.browser.WebBrowserMode
import sigil.tool.{
  DiscoverySpec,
  Effect,
  Freshness,
  ImageQuality,
  ImageToolOutput,
  Resolution,
  Tool,
  ToolExample,
  ToolIO,
  ToolName,
  ToolProfile,
  ToolResult,
  ToolSpec
}
import sigil.GlobalSpace
import sigil.tool.ToolContext

import java.nio.file.Files
import java.util.Base64
import scala.concurrent.duration.*

/**
 * Capture a PNG screenshot of the current page, persist it via
 * [[sigil.Sigil.storeBytes]] under [[GlobalSpace]], and resolve to an
 * [[ImageToolOutput]] so the framework lifts the capture into the
 * agent's visual context — the agent SEES the screenshot
 * on its next turn, matching this tool's contract, instead of a bare
 * file reference it can't render.
 *
 * The capture is also surfaced live to subscribers via the
 * [[BrowserStateDelta]] published during execution; the URL resolves
 * through the framework's storage route filter, so the backend
 * (local FS / S3 / future CDN) is invisible to the client.
 */
final class BrowserScreenshotTool extends Tool {
  type Input = BrowserScreenshotInput
  type Output = ImageToolOutput
  val io: ToolIO[BrowserScreenshotInput, ImageToolOutput] = ToolIO.derived[BrowserScreenshotInput, ImageToolOutput].withExamples(
    ToolExample("Default screenshot", BrowserScreenshotInput()),
    ToolExample("Wait 5s for animations", BrowserScreenshotInput(waitSeconds = 5)),
    ToolExample("Full-page capture", BrowserScreenshotInput(fullPage = true))
  )

  override val name = ToolName("browser_screenshot")
  override val description =
    """Take a screenshot of the current page. Returns the rendered image as part of the chat (both you and the user see it).
      |Use when text-only scraping isn't enough — graphical UIs, layout-dependent pages, error states.
      |Set `fullPage = true` to capture the entire scrollable page instead of just the visible viewport.""".stripMargin
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Volatile)),
    discovery = DiscoverySpec(
      keywords = Set("browser", "screenshot", "image", "capture", "render"),
      modes = Set(WebBrowserMode.id)
    )
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Explicit(executeResult)

  private def executeResult(input: BrowserScreenshotInput, ctx: ToolContext): Task[ToolResult[ImageToolOutput]] =
    for {
      controller <- BrowserToolBase.resolveController(ctx)
      // Resize viewport if requested.
      _ <- controller.run { browser =>
        (input.maxWidth, input.maxHeight) match {
          case (Some(w), Some(h)) => browser.setViewportSize(w, h)
          case _ => Task.unit
        }
      }
      // Capture bytes. `fullPage` goes straight through CDP
      // (captureBeyondViewport + content-size clip); the viewport case
      // captures to a tempfile via robobrowser's helper.
      bytes <- controller.run { browser =>
        if (input.fullPage)
          Task.sleep(input.waitSeconds.seconds)
            .flatMap(_ => BrowserScreenshotTool.captureFullPage(browser))
        else Task.defer {
          val tmp = Files.createTempFile("sigil-screenshot-", ".png")
          browser.screenshotAs(tmp, afterLoadDelay = Some(input.waitSeconds.seconds))
            .map { _ =>
              val read = Files.readAllBytes(tmp)
              try Files.deleteIfExists(tmp)
              catch { case _: Throwable => () }
              read
            }
        }
      }
      stored <- ctx.sigil.storeBytes(
        GlobalSpace,
        bytes,
        "image/png",
        metadata = Map(
          "kind" -> "browser-screenshot",
          "conversationId" -> ctx.conversation.id.value
        ))
      // Emit a delta on the BrowserState so subscribers see the new
      // screenshot reference.
      _ <- ctx.sigil.publish(BrowserStateDelta(
        target = controller.stateId,
        conversationId = ctx.conversation.id,
        screenshotFileId = Some(stored._id)
      ))
    } yield ToolResult.Success(ImageToolOutput(
      url = ctx.sigil.storageUrl(stored),
      alt = s"Browser screenshot at ${java.time.Instant.now}",
      text = Some("Screenshot of the current browser page. Examine the rendered page for " +
        "layout, content, error states, or differences from what you expected."),
      // a screenshot exists to be scrutinized (read text, compare
      // against expectations), so request the High tier rather than the
      // Low default. Combined with the area-based downscale this keeps a
      // tall full-page capture legible.
      quality =
        ImageQuality.High
    ))
}

object BrowserScreenshotTool {

  /**
   * Full-page capture via raw CDP: size the clip to the page's CSS
   * content box (from `Page.getLayoutMetrics`) and pass
   * `captureBeyondViewport` so Chrome renders past the visible
   * viewport — the same mechanism Playwright/Puppeteer use for their
   * `fullPage` option. Returns the decoded PNG bytes.
   */
  private[tool] def captureFullPage(browser: RoboBrowser): Task[Array[Byte]] =
    browser.send(method = "Page.getLayoutMetrics").flatMap { metrics =>
      val content = metrics.result("cssContentSize")
      val width = content("width").asDouble
      val height = content("height").asDouble
      browser.send(
        method = "Page.captureScreenshot",
        params = obj(
          "format" -> "png".json,
          "captureBeyondViewport" -> true.json,
          "clip" -> obj(
            "x" -> 0.json,
            "y" -> 0.json,
            "width" -> width.json,
            "height" -> height.json,
            "scale" -> 1.json
          )
        )
      ).map(shot => Base64.getDecoder.decode(shot.result("data").asString))
    }
}
