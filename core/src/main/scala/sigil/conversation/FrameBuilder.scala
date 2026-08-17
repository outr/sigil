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
      case ToolOutcome.Success if ti.overflow.isDefined && ti.summary.nonEmpty =>
        // Bounded result: the typed `output` is preserved on the invoke
        // (so typed consumers — and the visual channel below — keep the
        // real value), but the model-facing text is the bounded head the
        // executor wrote to `summary`. An ImageToolOutput with an
        // oversized caption keeps its image.
        val images = ti.output match {
          case img: sigil.tool.ImageToolOutput => List(sigil.tool.ImageQuality.stamp(img.url, img.quality))
          case _                               => Nil
        }
        (ti.summary, images)
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
   * [[Event.withContextFrame]].
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
          completionId = ti.completionId,
          internal = ti.internal,
          state = callState,
          // The Complete content above was rendered from a Pending
          // outcome (the race placeholder / stale summary) — flag it so
          // readers recompute once the row settles.
          resultPending = ti.state == EventState.Complete && ti.outcome == ToolOutcome.Pending
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
      case _: sigil.event.Reaction                       => None
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
   * The one tool-result pairing rule: whether a paired Tool-role
   * result still has work to do on its parent call's frame.
   *
   * A call whose frame is still `Active`, or whose settled frame
   * carries the "result hasn't landed" placeholder, adopts the
   * result's payload. A call already carrying its real result is left
   * alone — the framework's settled synthetic diagnostics stamp their
   * reason onto the invoke's own `outcome` + `summary`, so their frame
   * is complete before the paired Message arrives.
   *
   * `invokeOutcome` is the persisted invoke's outcome where the caller
   * has it (the live settle path reads the row); `frame.resultPending`
   * is the projection of that same fact for callers that only hold
   * frames.
   */
  private[sigil] def settlesPairedCall(frame: ContextFrame.ToolCall,
                                       invokeOutcome: Option[ToolOutcome] = None): Boolean =
    frame.state == ToolCallState.Active || frame.resultPending ||
      invokeOutcome.contains(ToolOutcome.Pending)

  /** Apply a settled Tool-role event's payload to `frame` — the shared
    * transition every pairing path performs once it decides to fold. */
  private[sigil] def settledPairedFrame(frame: ContextFrame.ToolCall, event: Event): ContextFrame.ToolCall = {
    val (content, images) = toolResultPayload(event)
    frame.copy(state = ToolCallState.Complete(content, images), resultPending = false)
  }

  /**
   * Fold a newly-Complete event into an existing frame vector — the
   * single event→frame rule set.
   *
   * Per-event frame shape comes from [[computeFrame]], so the fold path
   * and the live settle path cannot disagree about what an event
   * projects to. This function adds only what needs surrounding
   * context: folding a Tool-role result into its parent
   * `ContextFrame.ToolCall`, which the live path does separately in
   * `Sigil.attachContextFrameOnSettle` against the persisted invoke.
   * Both consult [[settlesPairedCall]], and both treat a parent that
   * already carries its result as a no-op — only a result with NO
   * parent frame at all is an orphan.
   */
  def appendFor(existing: Vector[ContextFrame], event: Event): Vector[ContextFrame] = {
    if (event.state != EventState.Complete) return existing

    if (event.role == MessageRole.Tool) {
      event.origin match {
        case Some(callId) =>
          // Tool-result rendering: for typed Events strip framework
          // boilerplate and render the JSON of the remaining typed
          // fields. For `Message` the typed fields are the wrapper
          // itself, so the text is extracted directly and the model
          // sees a clean result rather than doubly-wrapped JSON.
          val parentIdx = existing.indexWhere {
            case tc: ContextFrame.ToolCall => tc.callId == callId
            case _                         => false
          }
          if (parentIdx >= 0) {
            val tc = existing(parentIdx).asInstanceOf[ContextFrame.ToolCall]
            return if (settlesPairedCall(tc)) existing.updated(parentIdx, settledPairedFrame(tc, event))
                   else existing
          }
          // No parent ToolCall frame at all — genuine orphan result.
          // Surface as a synthetic agents-only Text frame so the data
          // doesn't vanish silently; the live publish path's invariant
          // gate prevents this for fresh events, so it covers replay
          // scenarios.
          val (content, _) = toolResultPayload(event)
          return existing :+ ContextFrame.Text(
            content       = s"[framework: orphan tool result for callId=${callId.value} — content: $content]",
            participantId = event.participantId,
            sourceEventId = event._id,
            visibility    = sigil.event.MessageVisibility.Agents
          )
        case None =>
          // Malformed Tool-role event. `computeFrame` degrades rather
          // than throwing so a single bad event can't wedge the
          // conversation; the fold path matches it.
          return computeFrame(event).fold(existing)(existing :+ _)
      }
    }

    computeFrame(event).fold(existing)(existing :+ _)
  }

  /**
   * Fold a chronologically-sorted sequence of events into a fresh frame
   * vector — recovery, migration, and bulk import.
   */
  def build(events: Iterable[Event]): Vector[ContextFrame] =
    events.foldLeft(Vector.empty[ContextFrame])((acc, e) => appendFor(acc, e))

  private def renderMessageText(m: Message): String = renderContentText(m.content)

  /** Render content blocks to clean prompt text. Delegates to the exhaustive
    * [[sigil.render.MarkdownRenderer]] so EVERY [[ResponseContent]] variant has
    * a real textual form — a partial match's `case other => other.toString` was
    * leaking raw case-class renderings (`ItemList(List(...),false)`,
    * `Options(...,List(SelectOption(...)))`) into the assistant history, teaching
    * the model to emit them. Images are reduced to a short alt-text placeholder
    * so a `data:` URL or base64 bytes don't bloat the prompt (image bytes ride
    * the provider's own multimodal channel, not the frame text). */
  private[sigil] def renderContentText(content: Vector[ResponseContent]): String =
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

}
