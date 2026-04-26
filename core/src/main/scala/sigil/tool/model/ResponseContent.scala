package sigil.tool.model

import fabric.rw.*
import sigil.security.SecretKind
import spice.net.URL

/**
 * Structured response content blocks produced by the LLM as part of an assistant
 * response. Each variant represents a different kind of content the UI can render.
 *
 * Discriminated by `type` in JSON form — auto-derived schema produces a
 * strict `oneOf` via DefinitionToSchema.
 */
enum ResponseContent derives RW {

  /**
   * Plain text.
   */
  case Text(text: String)

  /**
   * Source code, shell commands, or configuration.
   */
  case Code(code: String, language: Option[String] = None)

  /**
   * Structured table.
   */
  case Table(headers: List[String], rows: List[List[String]])

  /**
   * Code diff or patch.
   */
  case Diff(diff: String, filename: Option[String] = None)

  /**
   * Ordered or unordered list.
   */
  case ItemList(items: List[String], ordered: Boolean = false)

  /**
   * Navigable link.
   */
  case Link(url: URL, label: String)

  /**
   * A raster / vector image to display inline. `url` is either a
   * public URL or a `data:` URL carrying base64 bytes — both work for
   * renderers. `altText` is a short textual description for
   * accessibility + screen readers (and as a fallback when the
   * image can't be rendered). Emitted by providers with native image
   * generation (OpenAI GPT-5.4, Gemini) and by app-side tools that
   * produce images.
   */
  case Image(url: URL, altText: Option[String] = None)

  /**
   * Citation / source reference.
   */
  case Citation(source: String, excerpt: Option[String] = None, url: Option[URL] = None)

  /**
   * Formatted prose — last resort, only when no other type fits.
   */
  case Markdown(text: String)

  /**
   * Section or card heading.
   */
  case Heading(text: String)

  /**
   * Labeled key/value field — the workhorse for card-shaped content (news
   * items, status summaries, product cards). Renderers decide whether to
   * display as a horizontal row, an icon-prefixed line, or a Slack field
   * element. The optional `icon` is a semantic hint — renderers map it to
   * whatever icon set they support.
   */
  case Field(label: String, value: String, icon: Option[String] = None)

  /**
   * Visual separator between logical sections of a response. Carries no
   * content — each renderer translates to its native separator (HR in HTML,
   * a blank Block Kit divider in Slack, `---` in plain text).
   */
  case Divider

  /**
   * A structured set of options the user can choose from. Lets an agent ask a
   * bounded multiple-choice question as part of a respond message — typically
   * alongside prose (e.g. `▶Text` + `▶Options`).
   *
   * Semantics:
   *   - `allowMultiple = false` (default) — exactly one option may be selected.
   *   - `allowMultiple = true` — zero or more options may be selected; any option
   *     marked `exclusive = true` cannot be combined with others (useful for a
   *     "None of the above" escape hatch).
   *
   * A free-text answer is always available — the user can ignore the options
   * and reply in natural language. This block only describes the structured
   * choices the agent is offering.
   *
   * UI renders the list in the order given; numbering is implicit.
   */
  case Options(prompt: String, options: List[SelectOption], allowMultiple: Boolean = false)

  /**
   * Signal that the agent could not complete the task. Use this content type
   * when responding with a failure — the orchestrator can pattern-match on it
   * to decide whether to retry, alert, or surface the message as an error UI.
   *
   * `recoverable = true` indicates the failure may succeed on retry (transient
   * issues like network errors). `false` indicates the failure is permanent
   * for this request (missing permissions, unsupported input, etc.).
   */
  case Failure(reason: String, recoverable: Boolean = false)

  /**
   * Typed text-input form field. Renders a single-line text field
   * inline in the conversation. The user's submitted value comes back
   * as part of a normal Message reply — apps consuming the wire
   * decide how to associate the form with its reply (typically by
   * `id`).
   *
   *   - `id` — stable identifier the agent assigned to this field.
   *     Apps use it to correlate the user's reply with the form.
   *   - `placeholder` — hint shown when the field is empty.
   *   - `defaultValue` — pre-filled value the user can edit or clear.
   */
  case TextInput(label: String, id: String,
                 placeholder: Option[String] = None,
                 defaultValue: Option[String] = None)

  /**
   * Sensitive-value form field. Renders a password-style field
   * inline. The submitted value flows through the wire as a
   * [[sigil.signal.Signal]] handled by the `sigil-secrets` module's
   * `SecretCaptureTransform` (which writes to `SecretStore` and
   * replaces the in-flight signal with a Message-from-user
   * containing a [[SecretRef]] — so the plaintext never enters
   * `SigilDB.events`).
   *
   *   - `secretId` — stable identifier under which the value will
   *     be stored. Match the same id when later reading via
   *     `SecretStore.get` / `verify` / `delete`.
   *   - `kind` — [[SecretKind.Encrypted]] (retrievable, e.g. API
   *     tokens) or [[SecretKind.Hashed]] (verify-only, e.g.
   *     passwords).
   *
   * Apps that don't load `sigil-secrets` can still emit this content
   * type — without `SecretCaptureTransform` installed, the wire
   * value will not be processed and the field has no server-side
   * effect.
   */
  case SecretInput(label: String, secretId: String, kind: SecretKind)

  /**
   * Reference to a previously-stored secret. Renders as a
   * sensitive-value pill (`••••••••` + Copy button — the Copy button
   * fetches the plaintext via the app's `secretStore.get` REST
   * endpoint over TLS). Doesn't carry the value itself; safe to
   * include in conversation history, events, and replays.
   *
   *   - `secretId` — the stored secret's id. Matches the id used at
   *     `SecretInput`-submission time (or set elsewhere via
   *     `SecretStore.setEncrypted` / `setHashed`).
   *   - `label` — human-readable label for the UI.
   */
  case SecretRef(secretId: String, label: String)
}
