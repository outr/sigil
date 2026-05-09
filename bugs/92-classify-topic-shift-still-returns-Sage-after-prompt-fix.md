# ❌ #92 — `classify_topic_shift` still returns the agent's name even after the #89 prompt fix

**Where:** The classifier prompt (`TopicClassifier.scala` or
wherever `classify_topic_shift`'s system prompt + enum options
are templated) and the agent's `respond` `topicLabel` validation.

**What's wrong:** Bug #89 was supposed to prevent two failure
modes:

  1. The first respond using the agent's name as `topicLabel`.
  2. The classifier returning a generic prior label too eagerly.

After the #89 fix landed (snapshot 15:35 today), live trace
from Sage shows:

  - Turn 1 → `respond` with `topicLabel = "Initial setup"` ✓
    (1 fixed — agent no longer seeds with "Sage")
  - Several turns later, after `start_metals` succeeded:
    `classify_topic_shift` called with **`kind = "Sage"`** ❌
    (2 not fixed — classifier still returns the agent's name
    even though the seed topic isn't "Sage" anymore)

So even when the prior topics are "Initial setup", "Model
switching", "Workspace setup" — none of which are the agent's
name — the classifier still emits `"Sage"` as the kind. That's
not even a *prior label*, it's the agent's name showing up in
the enum from somewhere else in the prompt.

Where is `"Sage"` coming from in the classifier's option list?
The wire log doesn't include the classifier's prompt verbatim,
but the only places "Sage" appears in this conversation's state
are:
  - `currentMode = ConversationMode` — not "Sage"
  - Agent's display name — "Sage"
  - System prompt header text

The classifier seems to be picking up the agent's display name
as a candidate prior topic *regardless* of what's actually in
the conversation's topic history. The #89 prompt fix targeted
the agent's `respond.topicLabel` selection, but the classifier
itself has a separate path that sources candidate labels from
agent-display-name (likely via the system prompt header or a
default-topic seed), bypassing the agent's persisted topic
history.

**Suggested fix:** Two angles:

### 1. Source classifier's prior-label list directly from
persisted topic history

The classifier's prompt template should include the
*conversation's actual topic stack* as the list of valid
`<prior label>` enum cases. Not the agent's display name, not
"Sage" or any framework default — only the labels the
conversation has actually seen. If the topic history is
`["Initial setup", "Model switching", "Workspace setup"]`,
those are the only `<prior label>` strings the classifier may
return. Anything else (including the agent's display name) is
not a valid response.

```
Pick exactly one value:
  - "NoChange"
  - "Refine"
  - "Initial setup"      ← from conversation.topicHistory
  - "Model switching"    ← from conversation.topicHistory
  - "Workspace setup"    ← from conversation.topicHistory
  - "New"
```

### 2. Defensive validator on classifier output

If the classifier returns a string that isn't in
`{NoChange, Refine, New} ∪ topicHistory.map(_.label)`, the
framework should reject it and treat the result as `New`
rather than persisting it. Today an out-of-band string like
"Sage" gets accepted and propagates as the new label.

(1) prevents the wrong values from ever being valid options;
(2) catches any future template drift. Together they ensure
the classifier's verdict is always grounded in the
conversation's actual history.
