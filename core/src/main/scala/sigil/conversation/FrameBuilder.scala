package sigil.conversation

import fabric.io.JsonFormatter
import fabric.rw.*
import fabric.{Json, Obj}
import sigil.event.{AgentState, CapabilityResults, Event, Message, ModeChange, MessageRole, Reasoning, Stop, TopicChange, TopicChangeKind, ToolInvoke, ToolOutcome}
import sigil.signal.EventState
import sigil.tool.{ToolInput, ToolOutput}
import sigil.tool.ToolInput.given
import sigil.tool.ToolName
import sigil.tool.discovery.CapabilityType
import sigil.tool.model.ResponseContent

/**
 * Pure, provider-agnostic translation from a single [[Event]] (that has
 * reached `EventState.Complete`) into zero or more [[ContextFrame]]s for
 * a [[ConversationView]].
 *
 * Event → frame mapping rules (per-type, no runtime visibility flag):
 *   - `Message` → one `ContextFrame.Text`. Tool-role Messages
 *     (synthesised diagnostics from the orchestrator) fold their
 *     content into the originating `ToolInvoke`'s inlined
 *     `ContextFrame.ToolCall` instead of emitting their own frame.
 *   - `ToolInvoke` → one `ContextFrame.ToolCall` whose `state` reflects
 *     the invoke's lifecycle: `Active` while in flight, `Complete`
 *     once a `ToolDelta` has folded `output` / `outcome` / `state`
 *     onto it. The `respond` tool is filtered at render time by the
 *     provider (the following `Message` is the response); the frame
 *     is still emitted here so the view carries the full history.
 *   - `ModeChange` → one `ContextFrame.System`.
 *   - `TopicChange` → one `ContextFrame.System` (Switch vs. Rename
 *     reflected in the rendered text).
 *   - `AgentState`, `Stop`, and any other control-plane event →
 *     no frame. These are lifecycle / control signals the LLM shouldn't
 *     be re-reading as content.
 *
 * Active events (in-flight) are skipped — only Complete events become
 * frames.
 */
object FrameBuilder {

  /**
   * Extract a Tool-role Message's render payload: the flattened text the
   * model reads as the `function_call_output`, and any image URLs the
   * Message carried. Images are kept out of the text — the renderer
   * delivers them as real image input via the frame's image list
   * rather than a stringified `toString` blob.
   *
   * Tool-role Messages are the orchestrator's synthetic-diagnostic
   * surface ([[sigil.orchestrator.SyntheticDiagnostic]] — refusal
   * challenges, repeated-query intercepts, plain-text reply
   * recoveries). Visibility relaxed to `private[sigil]` so the
   * framework's settle path (`Sigil.attachContextFrameOnSettle`) can
   * use it to compute the `ToolCallState.Complete(content, images)`
   * payload when it folds the diagnostic into the prior
   * [[ToolInvoke]]'s inlined frame.
   */
  private[sigil] def toolResultPayload(event: Event): (String, List[spice.net.URL]) =
    event match {
      case m: Message =>
        val images = m.content.collect { case ResponseContent.Image(url, _) => url }.toList
        (renderContentText(m.content), images)
      case other =>
        (JsonFormatter.Compact(stripEventBoilerplate(Event.rw.read(other))), Nil)
    }

  /** Sigil #265 — render the settled tool transaction's content + image
    * URLs from a `state = Complete` [[ToolInvoke]]. Success outcomes
    * render the typed `output` via the polymorphic `RW[ToolOutput]` and
    * use the result as the function_call_output text; failure outcomes
    * surface the reason; pending outputs (Failure with no output) fall
    * back to `ti.summary`. Sigil #280: framework-shipped
    * [[sigil.tool.ImageToolOutput]] surfaces its URL into the
    * `images` list so providers can render the image in the agent's
    * next-turn visual context. Apps with custom multi-image output
    * types override this hook to do the same for their own shapes. */
  private[sigil] def toolInvokePayload(ti: ToolInvoke): (String, List[spice.net.URL]) =
    ti.outcome match {
      case ToolOutcome.Failure(reason, _) =>
        val body = if (ti.summary.nonEmpty) ti.summary else reason
        (body, Nil)
      case ToolOutcome.Success =>
        ti.output match {
          case ToolOutput.Pending =>
            (if (ti.summary.nonEmpty) ti.summary else "(no result)", Nil)
          case img: sigil.tool.ImageToolOutput =>
            // Sigil #280 — lift the image URL into the frame's images
            // list so the agent's next-turn provider request actually
            // carries the pixels (via the existing image-rendering
            // path in each provider). Text falls back to alt → summary
            // → a generic "(image)" marker so the prose channel always
            // has SOMETHING for prompt-budget accounting.
            val text = img.text
              .orElse(Option(img.alt).filter(_.nonEmpty))
              .orElse(Option(ti.summary).filter(_.nonEmpty))
              .getOrElse("(image)")
            // Sigil #382 — stamp the tool's chosen quality onto the URL
            // so it survives the persisted `frame.images: List[URL]`
            // boundary; the provider parses it back into typed wire
            // content and downscales accordingly.
            (text, List(sigil.tool.ImageQuality.stamp(img.url, img.quality)))
          case txt: sigil.tool.TextToolOutput =>
            // Sigil #305 — the inner `text` IS the result the model
            // reads; the JSON-envelope rendering through `RW[ToolOutput]`
            // (`{"text":"…"}`) is a fabric-derived artifact that wastes
            // tokens on the wire and adds a small structural-reasoning
            // load per result. Unwrap to the inner string directly so
            // text-only tools ship as plain content across every
            // provider.
            (txt.text, Nil)
          case other =>
            // Sigil #404 — a structured output can opt into a clean-text
            // render via `modelText` (verbatim primary field + plain metadata
            // trailer) so a line the model copies out (e.g. read_file → an
            // edit_file anchor) equals the file's bytes. Others keep the
            // compact-JSON envelope.
            val rendered = other.modelText.getOrElse(
              JsonFormatter.Compact(stripPolyDiscriminator(summon[RW[ToolOutput]].read(other)))
            )
            (rendered, Nil)
        }
      case ToolOutcome.Pending =>
        // Defensive (#341): a Complete-state invoke shouldn't still be
        // Pending on outcome — its paired result either never settled it or
        // raced past the frame (a slow tool with a large result, #354). A
        // bare content-free `(pending)` strands the agent. Surface a marker
        // that names the call AND frames it as a missing-result race, NOT a
        // failure — so the agent reads it as "the result didn't arrive,
        // re-issuing is reasonable" rather than "the tool failed." The
        // duplicate-call cap excludes these retries (#354), so re-issuing to
        // obtain the missing result won't trip the escalation/restriction
        // spiral.
        val tn = ti.toolName.value
        val raceMarker =
          s"[$tn: result did not reach this turn (it completed but raced past the prompt, or is still " +
            s"finishing) — this is NOT a failure; if you still need it, calling $tn again is reasonable.]"
        (if (ti.summary.nonEmpty) ti.summary else raceMarker, Nil)
    }

  /**
   * Compute the render-ready [[ContextFrame]] for a single Event.
   * `None` for in-flight events and event types that don't produce
   * a frame (`AgentState`, `Stop`, `ControlPlaneEvent`s).
   *
   * Pure per-event projection — no dependency on any ordering or
   * surrounding events. The framework calls this at settle time to
   * inline the frame onto the durable event row via
   * [[Event.withContextFrame]]. Bug #26 — the curator queries
   * `db.events` for `contextFrame.isDefined` to materialize a
   * conversation's prompt history rather than walking a separate
   * frames Vector projection.
   */
  def computeFrame(event: Event): Option[ContextFrame] = {
    if (event.state != EventState.Complete) return None

    if (event.role == MessageRole.Tool) {
      // Well-formed Tool-role events (origin pointing back at the
      // matching ToolInvoke) produce NO frame of their own — the
      // framework's settle path (`Sigil.attachContextFrameOnSettle`)
      // updates the prior ToolInvoke's inlined `ContextFrame.ToolCall`
      // frame to `ToolCallState.Complete(content, images)` instead, so
      // the projection carries the full tool transaction as one frame.
      //
      // Bug #64 holdover: malformed historical events without `origin`
      // get a synthetic Agents-only Text frame so a broken historical
      // row doesn't brick the conversation. The write-side validator
      // rejects fresh emissions without `origin`, so this fallback
      // exists purely for legacy data.
      event.origin match {
        case Some(_) =>
          return None
        case None =>
          scribe.warn(
            s"FrameBuilder skipping malformed Tool-role ${event.getClass.getSimpleName} " +
              s"id=${event._id.value} participantId=${event.participantId.value} — no `origin`. " +
              s"Surfacing as a synthetic agents-only frame so the conversation continues."
          )
          return Some(ContextFrame.Text(
            content       = s"[framework: skipped malformed Tool-role event ${event._id.value} (no origin)]",
            participantId = event.participantId,
            sourceEventId = event._id,
            visibility    = sigil.event.MessageVisibility.Agents
          ))
      }
    }

    event match {
      case m: Message =>
        Some(ContextFrame.Text(
          content = renderMessageText(m),
          participantId = m.participantId,
          sourceEventId = m._id,
          visibility = m.visibility,
          // Sigil #405 — lift the message's attached image URLs onto the frame
          // so a user upload reaches the vision channel first-class (the
          // renderer emits MessageContent.Image blocks for them). The text
          // still reduces each image to a `[image: …]` placeholder — persisted
          // frames carry URLs, never base64. `ImageBytes` has no URL to persist,
          // so it stays a placeholder; apps store the bytes and reference them
          // as `ResponseContent.Image(storageUrl)`.
          images = m.content.collect { case img: ResponseContent.Image => img.url }.toList
        ))

      case ti: ToolInvoke =>
        val argsJson = ti.input
          .map(i => JsonFormatter.Compact(stripPolyDiscriminator(summon[RW[ToolInput]].read(i))))
          .getOrElse("{}")
        // Sigil #265 — `state == Complete` invokes carry their settled
        // payload inline (`output`, `outcome`, `summary`) so the frame
        // renders the full transaction. `Active` invokes shouldn't reach
        // this branch (the `state != Complete` gate at the top of the
        // method short-circuits them) — defensive fallback to Active.
        val callState =
          if (ti.state == EventState.Complete) {
            val (content, images) = toolInvokePayload(ti)
            ToolCallState.Complete(content, images)
          } else ToolCallState.Active
        Some(ContextFrame.ToolCall(
          toolName = ti.toolName,
          argsJson = argsJson,
          callId = ti._id,
          participantId = ti.participantId,
          sourceEventId = ti._id,
          visibility = ti.visibility,
          wireCallId = ti.callId,
          internal = ti.internal,
          state = callState
        ))

      case mc: ModeChange =>
        Some(ContextFrame.System(
          content = s"Mode changed to ${mc.mode}${mc.reason.map(r => s" ($r)").getOrElse("")}.",
          sourceEventId = mc._id,
          visibility = mc.visibility
        ))

      // Bug #74 — TopicChange is metadata, not conversation history.
      // The system prompt's "Current topic:" / "Previous topics:"
      // section already conveys current and prior labels; injecting a
      // mid-conversation `[system: Topic switched to: …]` frame is
      // pure noise AND becomes load-bearing when consecutive agent
      // Messages from a multi-respond turn (endsTurn = false → loop
      // iterates → another respond) cause the LlamaCpp provider's
      // mid-array system folding to prepend the topic-shift text into
      // the second assistant message, producing two consecutive
      // role=assistant entries that OpenAI-compatible providers
      // reject with HTTP 400. UI consumers still see TopicChange via
      // `signals` for breadcrumb / sidebar display.
      case _: TopicChange => None

      case r: Reasoning =>
        Some(ContextFrame.Reasoning(
          providerItemId = r.providerItemId,
          summary = r.summary,
          encryptedContent = r.encryptedContent,
          participantId = r.participantId,
          sourceEventId = r._id,
          visibility = r.visibility
        ))

      case _: AgentState | _: Stop                       => None

      // Sigil #313 — heal-pipeline audit events render as system
      // frames so the agent's next iteration reads them in context.
      // The trail is what makes the heal observable to the model:
      // "your prior tool call was orphaned; the framework synthesised
      // a result; pick up from here." Without the frame the model
      // would replay the same broken sequence after the synthetic
      // settle lands.
      case d: sigil.event.ConversationCorruptionDetected =>
        Some(ContextFrame.System(
          content = s"[framework-heal] Detected corruption (${d.detectorSource}): " +
            s"${d.detectedCorruption.size} evidence row(s); " +
            s"original error: ${d.originalError.errorClass}: ${d.originalError.message}.",
          sourceEventId = d._id,
          visibility    = d.visibility
        ))
      case h: sigil.event.ConversationHealed =>
        Some(ContextFrame.System(
          content = s"[framework-heal] ${h.strategyName} applied ${h.corrections.size} correction(s)" +
            (if (h.remainingIssues.isEmpty) "." else s"; remaining: ${h.remainingIssues.mkString("; ")}"),
          sourceEventId = h._id,
          visibility    = h.visibility
        ))
      case ex: sigil.event.HealingExhausted =>
        Some(ContextFrame.System(
          content = s"[framework-heal] ${ex.strategyName} exhausted: retry failed with " +
            s"${ex.retryError.errorClass}: ${ex.retryError.message}",
          sourceEventId = ex._id,
          visibility    = ex.visibility
        ))
      // Bug #61 — Reaction is UI signal, not curator-visible
      // context. Reactions persist as durable events but never
      // render into the prompt; agents that care query the event
      // log explicitly.
      case _: sigil.event.Reaction                       => None
      // Bug #62 — same rationale: read receipts are UI signal,
      // not prompt context. The ReadState row is per-(conv,
      // participant) state for delivery indicators, never seen
      // by the curator.
      case _: sigil.event.ReadState                      => None
      case _: sigil.event.ControlPlaneEvent              => None

      case other =>
        throw new RuntimeException(
          s"FrameBuilder: Event ${other.getClass.getSimpleName} has no frame rule. " +
            s"Add a case here to either emit a frame or extend ControlPlaneEvent."
        )
    }
  }

  /**
   * Append frames for a newly-Complete event to an existing frame vector.
   * The return value is the updated vector.
   *
   * Legacy convenience wrapper around [[computeFrame]] — kept while the
   * old `ConversationView.frames` projection is being phased out (bug
   * #26). New code should query `Event.contextFrame` directly.
   */
  def appendFor(existing: Vector[ContextFrame], event: Event): Vector[ContextFrame] = {
    if (event.state != EventState.Complete) return existing

    // Tool-result rendering takes precedence over the per-subclass match
    // — any event whose role is MessageRole.Tool is paired against the
    // most-recent unresolved ToolInvoke and rendered to JSON via
    // [[stripEventBoilerplate]] (framework metadata fields removed so
    // the model sees only the typed payload).
    if (event.role == MessageRole.Tool) {
      // Tool-result rendering: for typed Events (ChangeMode, ToolResults,
      // etc.) strip framework boilerplate and render the JSON of the
      // remaining typed fields. For `Message`, the typed fields are the
      // wrapper itself (`content: Vector[ResponseContent]` etc.) — the
      // agent wants just the text the tool wrote, not the Message
      // record. Extract the text directly so models see a clean tool
      // result instead of doubly-wrapped JSON.
      val (content, images) = toolResultPayload(event)
      // Bug #69 — pair via the explicit `origin` parent pointer the
      // orchestrator stamps onto every tool-emitted event. Multiple
      // Tool events from one `executeResult` therefore all carry the
      // same origin and all pair to the same call_id; no orphan-
      // frame fall-through, no ambiguity from "most-recent
      // unresolved" scanning, no temporal heuristics that break under
      // out-of-order delivery or replay.
      //
      // A `MessageRole.Tool` event with `origin = None` is a
      // programmer error — the framework throws rather than rendering
      // a degraded "additional tool output" placeholder. Tool authors
      // emit Tool-role events from `executeResult` (where the
      // orchestrator stamps origin automatically); slash-command /
      // workflow / programmatic dispatch sites set `origin` at the
      // emission point. Anything else is a category error caught at
      // publish time.
      val callId = event.origin.getOrElse {
        throw new IllegalStateException(
          s"FrameBuilder: ${event.getClass.getSimpleName} has role=MessageRole.Tool but no `origin` " +
            s"set. Every Tool-role event MUST carry `origin` pointing to its parent ToolInvoke. " +
            s"Event id=${event._id.value}; participantId=${event.participantId.value}."
        )
      }
      // Sigil #261 — unified ToolCall(state) frame model: fold this
      // Tool-role result into the matching ToolCall frame in
      // `existing` rather than appending a separate ToolResult frame.
      val matchingIdx = existing.indexWhere {
        case tc: ContextFrame.ToolCall if tc.callId == callId && tc.state == ToolCallState.Active => true
        case _ => false
      }
      if (matchingIdx >= 0) {
        val tc = existing(matchingIdx).asInstanceOf[ContextFrame.ToolCall]
        return existing.updated(
          matchingIdx,
          tc.copy(state = ToolCallState.Complete(content, images))
        )
      }
      // No matching Active ToolCall in `existing` — orphan result.
      // Surface as a synthetic agents-only Text frame so the data
      // doesn't vanish silently; live publish path's invariant gate
      // already prevents this in fresh events, so this fallback only
      // covers legacy / replay scenarios.
      return existing :+ ContextFrame.Text(
        content       = s"[framework: orphan tool result for callId=${callId.value} — content: $content]",
        participantId = event.participantId,
        sourceEventId = event._id,
        visibility    = sigil.event.MessageVisibility.Agents
      )
    }

    event match {
      case m: Message =>
        existing :+ ContextFrame.Text(
          content = renderMessageText(m),
          participantId = m.participantId,
          sourceEventId = m._id,
          visibility = m.visibility
        )

      case ti: ToolInvoke =>
        val argsJson = ti.input
          .map(i => JsonFormatter.Compact(stripPolyDiscriminator(summon[RW[ToolInput]].read(i))))
          .getOrElse("{}")
        val callState =
          if (ti.state == EventState.Complete) {
            val (content, images) = toolInvokePayload(ti)
            ToolCallState.Complete(content, images)
          } else ToolCallState.Active
        existing :+ ContextFrame.ToolCall(
          toolName = ti.toolName,
          argsJson = argsJson,
          callId = ti._id,
          participantId = ti.participantId,
          sourceEventId = ti._id,
          visibility = ti.visibility,
          wireCallId = ti.callId,
          internal = ti.internal,
          state = callState
        )

      case mc: ModeChange =>
        existing :+ ContextFrame.System(
          content = s"Mode changed to ${mc.mode}${mc.reason.map(r => s" ($r)").getOrElse("")}.",
          sourceEventId = mc._id,
          visibility = mc.visibility
        )

      // Bug #74 — TopicChange skips frame-emission; see `computeFrame`.
      case _: TopicChange => existing

      case r: Reasoning =>
        // Provider-internal reasoning items — surfaced as a frame so
        // the originating agent's prompt-build picks them up and the
        // OpenAIProvider can replay them in the next request's input
        // array. Visibility carries through from the event; other
        // viewers (other agents, human user UIs) filter the frame
        // out via `Sigil.canSee` / `visibilityAllows`. Bug #61.
        existing :+ ContextFrame.Reasoning(
          providerItemId = r.providerItemId,
          summary = r.summary,
          encryptedContent = r.encryptedContent,
          participantId = r.participantId,
          sourceEventId = r._id,
          visibility = r.visibility
        )

      case _: AgentState | _: Stop =>
        existing

      case _: sigil.event.ControlPlaneEvent =>
        // Status / lifecycle / wire-only signals (workflow run events,
        // etc.) — flow through `publish` for broadcast + persistence
        // but stay out of the conversation projection.
        existing

      case other =>
        // Unknown Event type — fail loud so gaps are caught. Adding a new
        // Event requires making a deliberate decision here: emit a frame,
        // or extend `ControlPlaneEvent` to opt out of the view.
        throw new RuntimeException(
          s"FrameBuilder: Event ${other.getClass.getSimpleName} has no frame rule. " +
            s"Add a case here to either emit a frame or extend ControlPlaneEvent."
        )
    }
  }

  /**
   * Fold a chronologically-sorted sequence of events into a fresh frame
   * vector. Used by `Sigil.rebuildView` for recovery / migration.
   */
  def build(events: Iterable[Event]): Vector[ContextFrame] =
    events.foldLeft(Vector.empty[ContextFrame])((acc, e) => appendFor(acc, e))

  // Bug #26 — the per-event projection updates (recentTools,
  // suggestedTools) moved to `Sigil.applyParticipantProjectionFor`,
  // which writes directly to the `db.participantProjections`
  // collection. Frame-derivation itself is per-event via
  // [[computeFrame]].

  private def renderMessageText(m: Message): String = renderContentText(m.content)

  /** Render content blocks to clean prompt text. Delegates to the exhaustive
    * [[sigil.render.MarkdownRenderer]] so EVERY [[ResponseContent]] variant has
    * a real textual form — a partial match's `case other => other.toString` was
    * leaking raw case-class renderings (`ItemList(List(...),false)`,
    * `Options(...,List(SelectOption(...)))`) into the assistant history, teaching
    * the model to emit them. Images are reduced to a short alt-text placeholder
    * so a `data:` URL or base64 bytes don't bloat the prompt (image bytes ride
    * the provider's own multimodal channel, not the frame text). */
  private def renderContentText(content: Vector[ResponseContent]): String =
    sigil.render.MarkdownRenderer.render(content.map {
      case ResponseContent.Image(_, alt)         => ResponseContent.Text(imagePlaceholder(alt))
      case ResponseContent.ImageBytes(_, _, alt) => ResponseContent.Text(imagePlaceholder(alt))
      case other                                 => other
    })

  private def imagePlaceholder(alt: Option[String]): String =
    alt.map(a => s"[image: $a]").getOrElse("[image]")

  /**
   * Strip the `ToolInput` poly discriminator so the `arguments` JSON
   * is pure parameter-schema on the wire. Same logic the llama.cpp
   * provider used to do at render time; moved here so frames already
   * carry the clean form.
   */
  private def stripPolyDiscriminator(json: Json): Json = json match {
    case o: Obj => Obj(o.value - "type")
    case other => other
  }

  /**
   * Strip the framework's standard Event fields (`_id`,
   * `participantId`, `conversationId`, `topicId`, `state`,
   * `timestamp`, `role`) plus the polymorphic Signal discriminator
   * (`type`) from a JSON-rendered Event. What remains is the
   * event-specific typed payload — what the model should see when
   * the event is rendered as a tool result.
   *
   * Example: `BalanceRead(balance = 1810.0, ...)` rendered via
   * `Event.rw.read` gives `{"type": "BalanceRead", "balance":
   * 1810.0, "participantId": "...", ...}`. After this strip:
   * `{"balance": 1810.0}` — clean payload only.
   */
  private val EventBoilerplateFields: Set[String] =
    Set("type", "_id", "participantId", "conversationId", "topicId", "state", "timestamp", "role")

  private def stripEventBoilerplate(json: Json): Json = json match {
    case o: Obj => Obj(o.value -- EventBoilerplateFields)
    case other => other
  }

  // `pairedCallId` was retired in bug #69 — Tool-role pairing now
  // uses the explicit `Event.origin` parent pointer the orchestrator
  // stamps at publish time. The scan-based heuristic is no longer
  // load-bearing.
}
