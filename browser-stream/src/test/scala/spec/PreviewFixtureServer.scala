package spec

import rapid.Task
import spice.http.content.Content
import spice.http.server.MutableHttpServer
import spice.http.server.config.HttpServerListener
import spice.net.ContentType

/**
 * Per-suite server hosting a continuously-animating page. The CDP
 * screencast is change-driven — a static page streams almost nothing —
 * so the preview specs need something repainting every frame to prove
 * frames actually flow (and keep flowing past Chrome's un-acked-frame
 * ceiling).
 *
 * It also declares a viewport and a narrow breakpoint, so a render target
 * that implies a phone can be shown to have actually laid the page out as
 * one (`window.mobile`) rather than merely cropping a desktop render.
 */
final class PreviewFixtureServer {

  private val html: String =
    """<!doctype html>
      |<html lang="en">
      |<head><title>Preview fixture</title>
      |<meta name="viewport" content="width=device-width, initial-scale=1">
      |<style>@media (max-width: 500px) { #bar { background: #fa4 } }</style>
      |</head>
      |<body style="margin:0;background:#101014;color:#f0f0f0;font:48px monospace">
      |  <div id="clock">starting</div>
      |  <div id="bar" style="height:80px;width:0;background:#4af"></div>
      |  <script>
      |    Object.defineProperty(window, 'mobile', {
      |      get: () => window.matchMedia('(max-width: 500px)').matches
      |    });
      |    let n = 0;
      |    setInterval(() => {
      |      n = (n + 7) % 100;
      |      document.getElementById('clock').textContent = 'frame ' + Date.now();
      |      document.getElementById('bar').style.width = n + '%';
      |    }, 30);
      |  </script>
      |</body>
      |</html>""".stripMargin

  private val server: MutableHttpServer = {
    val s = new MutableHttpServer
    s.config.clearListeners().addListeners(HttpServerListener(port = None))
    s.handler.handle { exchange =>
      exchange.modify { response =>
        Task(response.withContent(Content.string(html, ContentType.`text/html`)))
      }
    }
    s
  }

  def start(): Task[Unit] = Task { server.start().sync(); () }

  def stop(): Task[Unit] = Task {
    try server.stop().sync()
    catch { case _: Throwable => () }
  }

  /**
   * Explicit IPv4 — spice's listener binds 127.0.0.1 only, and Chrome
   * resolves `localhost` to ::1 first on some runners.
   */
  def url: String = s"http://127.0.0.1:${server.config.listeners().head.port.getOrElse(0)}/"
}
