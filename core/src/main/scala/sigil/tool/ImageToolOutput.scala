package sigil.tool

import fabric.rw.*
import spice.net.URL

/**
 * Standard [[ToolOutput]] for tools whose result is a rendered image —
 * a preview, a screenshot, a generated diagram, an OCR'd page, etc.
 * Sigil bug #280.
 *
 * Before this type shipped, tools producing visual output had no way
 * to deliver the image into the agent's visual context. The workaround
 * was to return a [[TextToolOutput]] containing a markdown `![alt](url)`
 * link — which renders for the human in the chat UI but stringifies to
 * the agent's next-turn prompt as plain text. The agent saw a URL it
 * couldn't fetch and had no way to actually look at the image.
 *
 * Providers with multimodal tool_result support (Anthropic Messages,
 * OpenAI Responses, Google Gemini) render the [[url]] as an image
 * content block alongside any accompanying [[text]] / [[alt]]:
 * `Sigil.urlImageFor(url)` resolves a `sigil://storage/…` URL to a
 * `data:` URL with base64 bytes, so the agent's next turn sees the
 * actual pixels.
 *
 * Providers without multimodal tool_result support gracefully degrade
 * to the markdown-link form so the chat UI still renders the image
 * for the human user.
 *
 * @param url  the image's location. Typically a `sigil://storage/<id>`
 *             URL produced by [[sigil.Sigil.storageUrl]] after the tool
 *             called [[sigil.Sigil.storeBytes]] to upload the bytes —
 *             but any URL the framework can resolve to bytes (public
 *             HTTPS, `data:`) works.
 * @param alt  short textual description for accessibility + screen
 *             readers and as a fallback when the image can't be
 *             rendered (older clients, text-only audit logs).
 * @param text optional caption / descriptor rendered alongside the
 *             image. Tools producing semantic context for the agent
 *             ("Preview of `/pages/analog-standard` at 1280×800")
 *             should set this so the agent has prose to ground its
 *             reasoning against, not just pixels.
 */
case class ImageToolOutput(url: URL,
                           alt: String = "",
                           text: Option[String] = None) extends ToolOutput derives RW
