# ❌ #201 — Tool-result frames must round-trip verbatim; `StandardBlockExtractor.extractToolResult` violates the tool contract

**Where:**
- `core/src/main/scala/sigil/conversation/compression/StandardBlockExtractor.scala:32-43`
  — `extractToolResult: Boolean = true` parameter.
- `core/src/main/scala/sigil/conversation/compression/StandardBlockExtractor.scala:66-67`
  — the walk's `ContextFrame.ToolResult` extraction branch.
- `core/src/main/scala/sigil/conversation/compression/StandardBlockExtractor.scala:117`
  — `DefaultPlaceholder` (hardcodes "Use `lookup(...)`" advice).
- `core/src/main/scala/sigil/conversation/compression/StandardBlockExtractor.scala:119-122`
  — `DefaultSummary` (line-oriented; broken on single-line JSON).
- `core/src/main/scala/sigil/tool/core/FindCapabilityTool.scala` — concrete
  field repro: emits a multi-match `CapabilityResults` event whose
  serialized JSON exceeds `minChars = 2000` and gets compressed away.
- `core/src/main/scala/sigil/tool/util/LookupTool.scala` — the tool the
  placeholder advertises; not in `CoreTools`, so apps that don't
  explicitly wire it leave the placeholder pointing at nothing.

**What's wrong:**

When a tool's `executeTyped` emits a result, the contract — implicit
throughout the framework, the agent's training, and the agent's view
of "what tools do" — is that the tool's output is what the agent sees
on the next iteration. The tool is a function; its return value is
authoritative; the framework's job is to deliver it.

`StandardBlockExtractor` violates that contract for any tool result
exceeding `minChars` (default 2000). The full tool output is replaced
with a placeholder stub whose `Summary:` field is the first 140 chars
of the content. For structured tool outputs (JSON / typed records /
multi-result lists) this is catastrophic:

- The summary captures *one* entry's *partial* description; the rest
  is invisible.
- The placeholder advises "Use `lookup(...)` to retrieve full content,"
  but `LookupTool` is in `sigil.tool.util`, not `CoreTools`. Apps that
  don't explicitly add it — Sage being a concrete current example —
  leave the placeholder pointing at a tool the model can't call.
- The agent has no narrative cue that the suggested tools added to its
  next-iteration toolset (the `suggestedTools` overlay in
  `Sigil.scala:2995`) are the find_capability matches it's missing.
  From the agent's perspective the toolset just grew, with no explanation.

**Field evidence** — a Sage session, user message "I'd like to connect
my project: /home/mhicks/projects/open/sigil":

1. Agent calls `find_capability(keywords="connect link project sigil")`.
2. find_capability emits `CapabilityResults` with 12 matches (Lucene
   ranking — `link_slack_channel` topped the list at score 18, but
   `set_workspace` was present at score 10).
3. `StandardBlockExtractor` runs over the result, replaces it with a
   333-char stub:
   ```
   (large content stored as Information[u3zvKMMHpl...]. Summary:
    {"matches":[{"name":"link_slack_channel","description":"Link a
    Slack channel to the current conversation so messages flow between
    them. P....
    Use `lookup(capabilityType="Information", name="u3zvKMMHpl...")`
    to retrieve full content.)
   ```
4. Agent receives this on iter 2 with `tool_choice: required` and 16
   tools offered (the 6 always-on plus the 10 suggested). The summary
   names one wrong match, truncates mid-word, and points at a tool
   that doesn't exist in its toolset.
5. Agent returns `finish=stop` with no tool call. From its perspective
   the tool result was unactionable.
6. Sigil's no-triggers recovery (`Sigil.scala:5412`) fires
   forced-synthesis with `tool_choice` narrowed to the respond family
   (3 tools). With the same broken Information overlay still in context
   and `set_workspace` no longer available, the agent has nothing to
   call. It enters reasoning mode, generates 28,756 reasoning tokens
   for 243 s straight, hits the model's 32 K context wall, returns
   `finish=length` with no tool call. `AgentRunawayException`.

Root cause of the entire cascade: **the agent never saw what
find_capability actually returned**. The other bugs (#198 misleading
error, #199 reasoning-runaway on forced-synthesis, #200 triplicated
failure messages) are all downstream amplifiers of this one defect.

**Why "make the summary smarter" isn't the fix:**

Tools have two valid output shapes:

1. **Complete** — the tool self-limits its output to a manageable
   size. `find_capability`'s ranker already caps results to a
   reasonable shortlist (12 in the field repro). `lsp_definition`
   returns one location. `record_consent` returns a small ack. Most
   tools fall here naturally.

2. **Paginated** — the tool exposes pagination inputs (offset / limit
   / cursor / pageSize / pageToken — whatever the framework standardises)
   so the agent decides how much to pull per call. `grep` over a 50K-line
   codebase, `read_file` on a 5 MB file, `list_objects` on a big bucket
   — all should accept `maxResults` / `byteRange` / `pageToken` and
   return one page per call.

There's no valid third option called "complete-but-framework-summarised."
A tool's behavior must be observable from inside the agent loop —
that's load-bearing for the agent's ability to choose its next action,
for prompt engineering, for debugging, for trust. If the framework
silently substitutes a stub for the tool's output, the tool's behavior
becomes invisible to the only consumer that matters.

A better summary algorithm would still be wrong. It would just hide
the contract violation slightly better.

**Suggested fix:**

Three changes, ordered from minimum-to-complete:

**1. Stop extracting tool results.**

```scala
// core/src/main/scala/sigil/conversation/compression/StandardBlockExtractor.scala

// REMOVE this parameter entirely:
//   extractToolResult: Boolean = true

// REMOVE this branch from the walk:
//   case tr: ContextFrame.ToolResult if extractToolResult && tr.content.length >= minChars => ...

// KEEP the Text branch — narrative-history summarization is fine:
//   case t: ContextFrame.Text if t.content.length >= minChars => ...
```

Update the `BlockExtractor` trait scaladoc to spell out the contract
explicitly: **tool results are out of scope for block extraction.
Only narrative text (Message-role content) is eligible.** Apps that
implement custom `BlockExtractor`s inherit the same constraint.

**2. Add `Tool.paginate: Boolean` — required, no default.**

```scala
// core/src/main/scala/sigil/tool/Tool.scala
trait Tool:
  ...
  /** Whether this tool returns paginated output. Required — no
    * default. Every Tool author must explicitly declare which
    * output shape their tool implements.
    *
    *   - `false` — the tool guarantees its result is self-limited
    *     to a size the agent can consume in one shot
    *     (`find_capability`'s ranked shortlist, `lsp_definition`'s
    *     single location, `record_consent`'s ack).
    *   - `true` — the tool's output is unbounded; the input schema
    *     MUST expose pagination fields (offset / limit / cursor /
    *     pageSize / pageToken) and the agent is responsible for
    *     re-calling with subsequent pages (`grep`, `read_file`
    *     over a large file, `list_objects` on a bucket).
    *
    * There is no third option. Tools whose output might exceed the
    * agent's context window without pagination must add pagination;
    * the framework will not silently compress tool output (bug #201).
    *
    * Surfaced in `find_capability` match records so the agent
    * learns from discovery whether a tool is one-shot or iterative.
    * Validated at registration (see `Sigil.validatePaginationSchema`):
    * `paginate = true` tools must have a pagination input field,
    * `paginate = false` tools cannot. */
  def paginate: Boolean
```

The absence of a default is deliberate. A default in either direction
silently classifies tools the author never thought about — exactly
the bug class this whole investigation traced. Making the field
required forces every Tool definition site (in-tree and downstream)
to make the call explicitly, with the trait scaladoc spelling out
which answer fits which output shape.

**3. Surface and validate.**

- Extend `CapabilityMatch` (in `core/src/main/scala/sigil/event/CapabilityResults.scala`
  or wherever the match record lives) with a `paginate: Boolean`
  field, populated from the matched tool's flag. `find_capability`'s
  inline match list (visible in `events.jsonl` and rendered in client
  UIs) carries it; agents that read the match list see "paginated:
  true" alongside the name/description and know to expect pagination
  inputs.
- At `Tool` registration time (`registerTool` / wherever tools are
  added to the framework's registry), validate: if `paginate = true`,
  the tool's input schema MUST contain at least one of the recognized
  pagination field names. Throw at registration, not in production.
  Pick a canonical set (`offset`+`limit`, OR `cursor`, OR
  `pageToken`+`pageSize` — Sigil picks; the validator accepts any
  one of the patterns).
- Audit in-tree tools (`sigil.tool.core.*`, `sigil.tool.util.*`) and
  set `paginate = true` for any whose output is potentially unbounded
  AND whose input schema already exposes pagination. For unbounded
  tools that DON'T paginate today, file separate per-tool follow-ups
  to add pagination — they were relying on the extractor to mask their
  unboundedness, which has been the bug.

**Migration / back-compat:**

- Apps that set `extractToolResult = true` explicitly on their
  `StandardBlockExtractor` will get a deprecation warning (or compile
  error if the param is removed outright). Migration is to delete the
  line — the new default does the right thing.
- Apps that defined custom `Tool`s now get a compile error until
  they declare `paginate` for each one. This is the point: every
  tool author has to look at their tool's output shape and pick
  Complete or Paginated. Tools that were quietly relying on the
  extractor surface in this audit, and the fix (add pagination, or
  prove self-limited) is forced rather than optional.
- The `LookupTool` references in `DefaultPlaceholder` are no longer
  hit for tool results (only for narrative-text extraction, which
  remains compatible). Apps that wire `LookupTool` keep narrative
  extraction working as before; apps that don't are no worse off than
  today.

**Related:**
- **#198** (misleading runaway error) — fires *after* this defect
  triggers a recovery cascade. Fix this and the recovery doesn't
  fire for find_capability calls.
- **#199** (forced-synthesis reasoning runaway on local llama) —
  the 4-minute hang only happens because forced-synthesis is being
  invoked unnecessarily. Fix this and the runaway never gets a
  chance to start in the find_capability happy path.
- **#200** (3× failure message republish) — the multiplied failure
  bubbles are downstream of the same cascade.

Of the four bugs (#198–#201), **this one is the root cause of the
field repro**. The other three are different facets of "what the
framework does when the agent can't act" — but the only reason the
agent couldn't act was that this defect hid the tool result from it.
