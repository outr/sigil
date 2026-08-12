# sigil-browser-stream

Live preview streaming for a conversation's browser. Adds WebRTC — hardware
H.264 off a virtual display, with viewer input routed back over the session's
DataChannel — and degrades to the CDP screencast on hosts that can't do it.

Opt-in, like every non-core module: add the dependency, mix in
`StreamBrowserSigil`, and keep the `BrowserCollections` + `SecretsCollections`
mix-ins `BrowserSigil` already required.

```scala
libraryDependencies += "com.outr" %% "sigil-browser-stream" % sigilVersion
```

```scala
class MyAppSigil extends StreamBrowserSigil {
  override type DB = MyAppDB       // SigilDB & BrowserCollections & SecretsCollections
  // ...
}

// Anywhere a viewer opens the preview panel:
sigil.previewStreamFor(conversationId).map {
  case s: PreviewStreamSession.WebRtc     => // negotiate over PreviewSignal / PreviewSignalReply
  case s: PreviewStreamSession.Screencast => // push s.frames over your own transport
}
```

The preview runs on its **own** per-conversation browser — headful, kiosk-mode on
a dedicated Xvfb display — so the headless browsers the agent's tools drive are
untouched. `streamBrowserConfig` defaults to `BrowserSigil.browserConfig` made
headful with a 1080p `virtualDisplay`; override it for a different resolution or
launch flags.

## Preview size

`StreamConfig.width`/`height` pick the size the preview renders and captures at,
independently of the display behind it: the page lays out at exactly that size
and exactly that rectangle is streamed, so `previewStreamFor(convId,
StreamConfig(width = Some(390), height = Some(844)))` gives a portrait,
mobile-layout preview with no letterboxing — on both rungs, since a WebRTC
session crops its display capture to the target while the screencast applies the
same size as a device-metrics override. `resizePreview(convId, width, height)`
changes it mid-preview: a WebRTC session renegotiates and its fresh offer arrives
as another `PreviewSignal` on the stream id the viewer is already answering on,
and a screencast session restarts capture behind the same `frames` stream. With
no live preview it warns and does nothing. A browser launched for a sized request
gets a display large enough for both the request and `streamBrowserConfig`'s own
size (Xvfb can't resize a running display), so resizing back up later still fits.

## The fallback ladder

`previewStreamFor` consults `robobrowser`'s availability probe and picks a rung:

| Condition | Result |
|---|---|
| Virtual display + GStreamer + a usable H.264 encoder | `PreviewStreamSession.WebRtc` |
| Anything missing, `streamFallbackToScreencast = true` (default) | `PreviewStreamSession.Screencast` on the same browser; the reason is logged at info |
| Anything missing, `streamFallbackToScreencast = false` | fails with `StreamUnavailableException(reason)` |

`previewStreamAvailability(convId)` returns the reason (or `None`) without
starting a session, so a UI can say *why* a preview is degraded instead of
guessing.

Each call yields an independent session — several viewers can watch one
conversation. `session.stop` ends that session only; the browser stays up for a
reconnecting viewer and is disposed by `StreamBrowserIdleReaper` after
`streamBrowserIdleTimeoutMs` with nobody watching (a controller with a live
session is never idle), on `deleteConversation`, or on `Sigil.shutdown`.

## Signaling

WebRTC signaling rides the notice vocabulary rather than a bespoke socket — the
same shape as the client-tools transport. The server always offers; the viewer
answers; ICE trickles both ways.

- **Server → client:** `PreviewSignal(conversationId, streamId, message)` is
  published for every `Offer` / `Ice` / `Error` / `Bye` the session produces. It
  is a `ConversationNotice`, so it reaches only the viewers subscribed to that
  conversation.
- **Client → server:** `PreviewSignalReply(conversationId, streamId, message)`
  arrives over the normal inbound-notice path (`SessionBridge` →
  `Sigil.handleNotice`) and is routed into the addressed session. `Bye` stops any
  session; an unknown `streamId` is warned about and dropped.

`message` is `robobrowser.stream.SignalMessage` end to end — typed on both sides
of the wire, never a loose JSON blob. A browser viewer is about a hundred lines
of `RTCPeerConnection` plus a `<video>` element.

The screencast fallback uses **no** signaling. Consumers pull
`PreviewStreamSession.Screencast.frames` (a `rapid.Stream[PreviewFrame]` of
base64 images plus scroll/viewport metadata) and push them over whatever
transport they already have. Frames are acked to Chrome as they arrive — before
they reach the stream — so a slow consumer costs frame rate, never a stalled
screencast; the buffer sheds oldest-first at `previewFrameBuffer` frames.

## Runtime requirements

The WebRTC rung is the only part with native dependencies. Miss any of these and
the module still works — it just always lands on the screencast.

- **GStreamer 1.x**, with the `base`, `good` and `bad` plugin sets. The elements
  actually required are `ximagesrc`, `webrtcbin`, `h264parse` and `rtph264pay`.
  Debian/Ubuntu: `gstreamer1.0-plugins-{base,good,bad}`. Arch:
  `gst-plugins-{base,good,bad}`.
- **An H.264 encoder.** Probed hardware-first: `vah264enc` / `vaapih264enc`
  (VAAPI), `nvh264enc` (NVENC), then `x264enc` in software. Presence is not
  enough — each candidate is smoke-tested, so a `vah264enc` without a usable
  render node is skipped rather than failing at negotiation. The selected
  encoder is reported in `WebRtc.stats`.
- **Xvfb** (`xvfb` on Debian/Ubuntu, `xorg-server-xvfb` on Arch). The display is
  allocated per session by `robobrowser`; nothing needs to be running
  beforehand, and `DISPLAY` on the JVM is irrelevant.
- **A STUN/TURN path.** WebRTC media is UDP (DTLS/SRTP) and does **not** traverse
  the HTTP tunnel your signaling rides. The default `StreamConfig.stunServer`
  suffices when both ends can reach each other directly; behind symmetric NAT or
  a corporate firewall you must run a TURN server and set
  `StreamConfig.turnServers` — without one, negotiation completes and no video
  ever arrives. Budget for TURN bandwidth: it relays the full stream.

Two limitations inherited from the streaming layer: bitrate is fixed at
`StreamConfig.maxBitrate` (no congestion-driven adaptation yet), and DRM
(Widevine) content may capture as black frames.

For pipeline-level debugging — encoder probes, `GST_DEBUG` traces, the reference
viewer page — see `STREAMING.md` and the "Live streaming (WebRTC)" section in the
`robobrowser` repository. This module does not duplicate them.

## Clean capture by default

`streamBrowserConfig` ships a clean-capture profile: the save-password bubble is suppressed at the profile level (`passwordManager = false` — no `--enable-automation`, so no automation infobar), `--test-type` hides the infobar and first-run/security prompts, Translate and PasswordLeakDetection features are off, and crash-restore/first-run/default-browser bubbles are suppressed. Display capture records everything Chrome draws — without this, any page that submits a form paints Chrome UI into the stream. Override `streamBrowserConfig` to relax any of it; the pooled headless automation browsers are untouched.
