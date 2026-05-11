# ❌ #137 — Classifier routing decisions (`workType`, `complexity`, chosen candidate) aren't published as Signals; invisible to wire-log forensics

**Where:**
- `core/src/main/scala/sigil/Sigil.scala:1022-1065` — `classifyForRoute` runs the classifier, persists the result to `routingCache`, returns `(WorkType, Complexity)`. The decision never reaches the signal hub.
- `core/src/main/scala/sigil/provider/ProviderStrategy.scala` — `routed` walks the chain and picks a candidate. The candidate selection never reaches the hub either.

**What's wrong:**

Per-turn routing has three observable inputs (the user's message, the strategy's chains, the classifier's `(workType, complexity)` output) and one observable output (the chosen `ModelCandidate`). None of them surface as Signals. The hub-subscribed event log captures everything else — tool invocations, progress checkpoints, message deltas — but the routing decision is opaque.

This makes wire-log forensics work blind on questions like:
- "Why did this turn go to gpt-5.5 instead of llama?" (Was the classifier wrong? Was the chain misconfigured? Was llama in cooldown?)
- "How often is the classifier returning High vs Medium?" (Distribution insight — useful for tuning the system prompt.)
- "Did `request_escalation` actually bump the tier on the next turn?" (No way to verify without intrusive logging.)

**Repro from a real Sage session** (2026-05-11, sage-events.jsonl, 2482 records):

Grepping the event log for any signal type related to routing yields zero results. The 21 signal types observed:

```
MessageDelta, FrameworkWorkflowNotice, ToolProgress, AgentStateDelta,
ToolInvoke, ToolDelta, StateDelta, ToolLog, WireRequestProfile,
ToolResults, CapabilityResults, Message, AgentState,
ConversationCostUpdated, TopicChange, ToolListSnapshot, ToolApproval,
ProgressCheckpoint, ModeChange, ConversationListSnapshot,
ConversationCreated
```

No `RouteResolved`, `ClassifierResult`, `ModelChosen`, or similar. The only nearby signal is `WireRequestProfile` — which records which model handled a turn after the fact, but doesn't expose the classifier's reasoning or which candidates were skipped and why.

For the Sage session above: 41/42 main turns went to gpt-5.5. Forensics had to infer that the classifier was misclassifying (or the chain was misordered) without being able to see the actual `(workType, complexity)` per turn. The diagnosis required reading the codebase and triangulating from token usage — far less efficient than reading a structured signal.

**Test first:**

```scala
class RouteResolvedSignalSpec extends AsyncWordSpec with Matchers {
  "classifyForRoute" should {
    "publish a RouteResolved signal with the classifier output and the chosen candidate" in {
      // Drive a turn that triggers classification + candidate selection.
      // Subscribe to the signal hub. Assert exactly one RouteResolved
      // event lands, carrying:
      //   - conversationId, agentId, userMessageId
      //   - inferredWorkType (or `null` when default-used)
      //   - inferredComplexity (or `null` when default-used)
      //   - candidateChain (the ordered list of ModelIds considered)
      //   - chosenModelId (the candidate that won)
      //   - skipReasons (per-candidate reason for skip, e.g. "in
      //     cooldown until ...", "supportedComplexity doesn't include
      //     High", or null when the candidate was selected)
      //   - classifierLatencyMs (how long the consult took, or null
      //     when skipped)
      //   - escalationCount (#128's voluntary-escalation counter on
      //     the cached routing state)
    }

    "publish RouteResolved with null classifier fields when shouldClassify* gates skip" in {
      // Strategy whose chain has no tier variation (workTypeMatters /
      // complexityMatters = false). Assert the signal carries
      // null for inferredWorkType/inferredComplexity rather than
      // omitting the event.
    }

    "publish RouteResolved on cap-hit forced-synthesis escalation (#125 composition)" in {
      // Cap-hit triggers escalateForCapHit + forced-synthesis turn.
      // Assert the forced-synthesis turn's RouteResolved shows the
      // bumped complexity tier and the elevated chosen candidate.
    }
  }
}
```

All three must fail on current `main` (no such signal exists).

**Suggested fix:**

### 1. New `RouteResolved` event in the core signal vocabulary

`core/src/main/scala/sigil/event/RouteResolved.scala`:

```scala
package sigil.event

import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.participant.ParticipantId
import sigil.provider.{Complexity, WorkType}
import sigil.signal.{ControlPlaneEvent, EventState}

/** Per-turn routing-decision event. Published by [[Sigil.classifyForRoute]]
  * once per provider call so wire-log forensics can see exactly which
  * (workType, complexity, candidate) shaped the turn — and which
  * candidates were skipped and why. */
case class RouteResolved(
  conversationId:        Id[Conversation],
  topicId:               Id[Topic],
  agentId:               ParticipantId,
  userMessageId:         Option[Id[Event]],            // None when no user message frames this turn (greeting)
  inferredWorkType:      Option[WorkType],             // None when classifier skipped or no callback wired
  inferredComplexity:    Option[Complexity],           // None when classifier skipped or no callback wired
  candidateChain:        List[Id[Model]],              // ordered chain considered for this turn
  chosenModelId:         Id[Model],                    // the winner
  skipReasons:           Map[Id[Model], String],       // candidate -> skip reason ("cooldown", "complexity Low not supported", etc.)
  classifierLatencyMs:   Option[Long],                 // None when classifier skipped
  escalationCount:       Int,                          // 0 for default classification; N after N voluntary escalations
  topicIndex:            Int = 0,
  state:                 EventState = EventState.Complete,
  timestamp:             Timestamp = Timestamp(),
  override val source:   Option[String] = None,
  _id:                   Id[Event] = Event.id()
) extends ControlPlaneEvent derives RW {
  override def withState(state: EventState): Event = copy(state = state)
  override def withConversationId(c: Id[Conversation]): Event = copy(conversationId = c)
  // ... withOrigin, withContextFrame stubs
}
```

Register in `CoreSignals`:

```scala
summon[RW[RouteResolved]]
```

### 2. Emit from `classifyForRoute` + candidate-selection sites

In `Sigil.scala:1022-1065`, after the classifier returns:

```scala
cxTask.flatMap { cx =>
  routingCache.put(conversation._id, RoutingState(msgId, wt, cx, escalations = 0))
  // Emit the RouteResolved event AFTER candidate selection.
  selectCandidate(strategy, wt, cx).flatMap { case (chosen, chain, skips) =>
    publish(RouteResolved(
      conversationId      = conversation._id,
      topicId             = conversation.currentTopicId,
      agentId             = ???,                 // plumb through from caller
      userMessageId       = userMsg.map(_._id),
      inferredWorkType    = if (strategy.shouldClassifyWorkType) Some(wt) else None,
      inferredComplexity  = if (strategy.shouldClassifyComplexity(wt)) Some(cx) else None,
      candidateChain      = chain.map(_.modelId),
      chosenModelId       = chosen.modelId,
      skipReasons         = skips,
      classifierLatencyMs = ???,
      escalationCount     = routingCache.get(conversation._id).map(_.escalations).getOrElse(0)
    )).map(_ => (wt, cx))
  }
}
```

`selectCandidate` is the small refactor that exposes both the chosen candidate AND the skip reasons so they can be published. Today the chain-walking happens inline; pulling it out lets the diagnostic surface emerge without duplicating the routing logic.

### 3. Sage-side benefit (no Sage change needed)

Sage's `SageEventLogger` (the wire-log monitor that subscribes to `signalsFor(viewer)`) automatically picks up the new signal type. Wire-log forensics on the next session shows routing decisions inline with everything else.

**Why this is lower priority than #136 but worth filing:**

- **#136** saves ~30% cost on every session immediately. Concrete, large, structural.
- **#137** doesn't save anything directly — it makes diagnosis-and-tuning loops faster. The first time someone wants to ask "did the classifier work correctly here?" they spend 20 minutes diagnosing without #137; with #137 they spend 1 minute reading the event log.

Worth landing during a routing-related refactor (e.g. when #128's per-message routing gets tuned for the gpt-4o-mini medium tier) so the visibility is in place when you need it.

**Companion notes:**

- **`WireRequestProfile`** (already published) carries the post-hoc model id but not the classifier inputs. Could be extended instead of adding `RouteResolved`, but `WireRequestProfile`'s semantics are about HTTP-level wire stats; mixing routing decisions into it would muddy the type. Separate signal is cleaner.
- **`escalationCount`** (from #128) is one of the most diagnostically valuable fields — it tells you whether the agent voluntarily escalated mid-turn AND whether the framework auto-escalated on cap-hit. Hard to get this signal any other way today.
