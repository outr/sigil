package sigil.tool.model

import fabric.Json
import fabric.rw.*
import lightdb.id.Id
import sigil.security.SecretKind
import sigil.storage.StoredFile
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
   *
   * Apps with inline bytes that AREN'T already at a public URL
   * should use [[ImageBytes]] instead — spice's [[URL]] parser
   * mangles `data:` URIs (parses them as `https://` with `data:image`
   * as host), and `Image(URL.parse("data:..."))` round-trips through
   * `toString` as garbage. Sigil #296.
   */
  case Image(url: URL, altText: Option[String] = None)

  /**
   * Inline image bytes — for transient, in-memory images that aren't
   * (and shouldn't be) persisted as [[sigil.storage.StoredFile]] or
   * hosted at a fetchable URL. PDF page renders, screen captures,
   * tool-produced diagnostic images, anything generated mid-turn
   * that the agent's next call needs to see.
   *
   * Parallels the wire-layer [[sigil.provider.MessageContent.ImageBytes]] —
   * `Provider.toMessageContent` maps this case directly to the wire
   * type, which every multimodal provider already handles (OpenAI
   * Responses' `image_url: data:...;base64,...`, Anthropic's
   * `{type: "base64", media_type: ..., data: ...}`, Gemini's
   * `inlineData`). Sigil #296.
   *
   * @param mediaType the image MIME (`image/jpeg`, `image/png`, `image/webp`, …).
   * @param base64    the image bytes, already base64-encoded (no `data:` prefix).
   * @param altText   optional alt text the model may surface for accessibility.
   */
  case ImageBytes(mediaType: String, base64: String, altText: Option[String] = None)

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
  case TextInput(label: String,
                 id: String,
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

  /**
   * Reference to bytes living in [[sigil.storage.StoredFile]] —
   * what gets persisted in `SigilDB.events` instead of an oversized
   * inline `Code` / `Diff` / `Image` block.
   *
   * The framework's [[sigil.pipeline.ContentExternalizationTransform]]
   * rewrites blocks larger than [[sigil.Sigil.inlineContentThreshold]]
   * to this variant before persist. Renderers (UI, agent context,
   * search indexer) dispatch on it the same way they dispatch on
   * any other variant — UIs render a chip with `title` + `size`,
   * the agent's prompt-builder calls
   * [[ResponseContent.dereference]] to materialize the original
   * bytes, and the search indexer does the same before reading the
   * content for index entries.
   *
   *   - `fileId` — id of the persisted bytes in `SigilDB.storedFiles`.
   *   - `title` — short human-readable label the UI displays
   *     (e.g. "PaymentService.scala" or "tool-output.json").
   *   - `language` — optional language hint, copied from the original
   *     `Code.language` when externalizing a `Code` block.
   *   - `contentType` — MIME type of the bytes; renderers use it to
   *     choose icons / preview affordances.
   *   - `size` — bytes count; UIs render as "12.4 KB" without
   *     fetching the file.
   *
   * The reference is safe to include in agent context, conversation
   * snapshots, and replays; it never carries the bytes themselves.
   */
  case StoredFileReference(fileId: Id[StoredFile],
                           title: String,
                           language: Option[String] = None,
                           contentType: String = "application/octet-stream",
                           size: Long = 0L)

  /**
   * Container that groups a sequence of [[ResponseContent]] blocks into
   * a single composable unit. The agent emits one `Card` per `respond_card`
   * call; richer multi-card responses use `respond_cards`. Recursive — a
   * Card's sections may themselves contain Card blocks for nested groups.
   *
   *   - `sections` — the building blocks composed into this card, stored
   *     as raw JSON values to break what would otherwise be a self-
   *     referential cycle in fabric's auto-derived RW (`Card.sections:
   *     Vector[ResponseContent]` → enum RW → Card case → enum RW
   *     deadlocks during lazy-val initialization). Construct via
   *     [[Card.of]] when you have typed blocks, render via
   *     [[Card.typedSections]] to recover them. Wire-format-wise the
   *     JSON is exactly what a typed `Vector[ResponseContent]` would
   *     serialize to, so no observer downstream sees a difference.
   *   - `title` — optional card title rendered as a header before
   *     `sections`. Distinct from a `Heading` block inside `sections` —
   *     renderers may style the title differently (card chrome vs.
   *     section header).
   *   - `kind` — optional UI styling hint (`"alert"`, `"info"`,
   *     `"recipe"`, `"metric"`, …). The framework doesn't interpret
   *     it; apps map kinds to their UI components / style sheets.
   *     Renderers that have no equivalent (plain text, generic
   *     markdown) ignore it.
   */
  case Card(sections: List[Json],
            title: Option[String] = None,
            kind: Option[String] = None)
}

object ResponseContent {

  /**
   * Explicit RW for the [[ResponseContent.Card]] case so tools that
   * constrain their input to Cards specifically (e.g. `respond_card`)
   * can round-trip. Lives in the enum's companion so implicit search
   * for `RW[ResponseContent.Card]` finds it. The case is non-recursive
   * (`sections: List[Json]`), so this derivation completes without the
   * cycle that `Vector[ResponseContent]` would introduce.
   */
  given cardRW: RW[ResponseContent.Card] = RW.gen
}

/**
 * Construction + access helpers for [[ResponseContent.Card]] — the
 * enum case stores `sections` as `List[Json]` (raw JSON) to break
 * what would otherwise be a self-referential cycle in fabric's
 * auto-derived RW. Apps construct Cards from typed blocks via
 * `Card(blocks, title, kind)` and read them back via
 * `Card.typedSections(card)`.
 */
object Card {

  /**
   * Build a [[ResponseContent.Card]] from typed blocks. Each block
   * round-trips through the enum's RW into a Json value so the wire
   * format matches what a `Vector[ResponseContent]`-typed field would
   * have produced.
   */
  def apply(sections: Vector[ResponseContent],
            title: Option[String] = None,
            kind: Option[String] = None): ResponseContent.Card =
    ResponseContent.Card(sections.map(_.json).toList, title, kind)

  /**
   * Materialize the typed blocks back from a Card's raw section JSON.
   * Used by renderers (which need typed dispatch) and apps that want to
   * inspect a Card's contents structurally. Each Json value is read via
   * the parent `ResponseContent` RW.
   */
  def typedSections(card: ResponseContent.Card): Vector[ResponseContent] =
    card.sections.iterator.map(j => j.as[ResponseContent]).toVector
}
