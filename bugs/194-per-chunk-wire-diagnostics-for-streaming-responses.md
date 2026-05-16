# ❌ #194 — Wire interceptor needs per-chunk timing + shape logging for streaming responses; today's request/aggregated-response shape can't diagnose mid-stream stalls

**Where:**
- `core/src/main/scala/sigil/provider/debug/JsonLinesInterceptor.scala`
  — current shape: one line per outbound request, one line per
  fully-aggregated response. For streaming SSE responses, the
  response line lands only after the stream completes (or errors
  out); per-chunk arrival timing is lost.
- `core/src/main/scala/sigil/provider/wire/OpenAIChatCompletions.scala`
  — the parser sees each chunk in real time but doesn't currently
  emit a diagnostic event per chunk.

**What's wrong:**

When a streaming provider response hangs mid-stream (idle timeout
upstream, connection stall, slow inference hardware, etc.), the
wire log gives no visibility into what was happening BEFORE the
hang. The interceptor's contract is "log the request, log the
response when it completes" — for SSE responses, "when it completes"
can be 10+ minutes after the request fired, and we get one
monolithic blob with no inter-chunk timing.

Concrete recent example (Sage session, bug #193 wire forensic):

- Request fired to OpenRouter for kimi-k2.6 at 07:30:09.
- 77 SSE chunks arrived over the next 10 minutes.
- Chunks 0-73 carried reasoning fragments.
- Chunk 74 carried an upstream-error chunk (Io Net hit an idle
  timeout per #193).
- Wire log shows: one `request` line at 07:30:09, one `response`
  line at 07:40:14, with the aggregated SSE body as a giant string.

What we COULDN'T determine from the wire log:
- Did the 73 reasoning chunks arrive in a fast burst followed by
  a long stall, or steadily-but-slow across the full 10 minutes?
- When did Io Net stop emitting tokens?
- Was there an inter-chunk gap that crossed Io Net's idle-timeout
  threshold before the error fired?
- What was the longest gap between any two chunks?

Each shape has a different mitigation:
- **Steady-but-slow** → route around the slow upstream
  (OpenRouter provider preferences).
- **Burst-then-stall** → Kimi got stuck reasoning; consider tighter
  prompt-engineering, or detect the stall faster (lower
  `tokenIdleTimeout`).
- **Connection issue** → network-layer retry policy.

Without per-chunk timing, we can't tell which mitigation applies.

### Suggested fix

Add an opt-in per-chunk diagnostic log on the wire interceptor. Shape
matters for the diagnostic to be useful without bloating the wire log
for routine traffic:

**1. Separate log file by default.** Mainline `sage-wire.jsonl` keeps
its current shape (one request line, one response line) so existing
forensic flows aren't disrupted. Per-chunk diagnostics land in
`sage-wire-chunks.jsonl` (or whatever the host configures) — opt-in
because it can be 50-100x more lines than the request-aggregated
mode for long streams.

**2. One line per SSE chunk** with:

```json
{
  "kind": "chunk",
  "ts": "2026-05-16 07:30:14.122",
  "requestId": "<correlation id matching the request line>",
  "url": "https://openrouter.ai/api/v1/chat/completions",
  "chunkIndex": 7,
  "elapsedSinceRequestMs": 4882,
  "elapsedSincePrevChunkMs": 312,
  "byteSize": 178,
  "shape": {
    "hasContent": false,
    "hasReasoning": true,
    "hasReasoningContent": false,
    "hasToolCalls": false,
    "hasError": false,
    "hasUsage": false,
    "finishReason": null
  },
  "preview": "{\"choices\":[{\"delta\":{\"reasoning\":\" log\"}}]}",
  "previewTruncatedAt": 200
}
```

Critical fields:
- `chunkIndex` — sequential per response
- `elapsedSinceRequestMs` + `elapsedSincePrevChunkMs` — the
  load-bearing fields for stall diagnosis
- `shape` — fast classification without parsing the full delta
  (so post-hoc filters like "show me chunks where hasError = true"
  or "show me the longest inter-chunk gaps" run cheap)
- `preview` — first ~200 chars of the raw chunk for spot-checks;
  truncated to keep lines bounded

**3. Configuration knob on the interceptor.** Hosts opt in by
setting `JsonLinesInterceptor.perChunkLogPath = Some(path)`. Default
`None` (no per-chunk file written, no overhead). Sage's wire config
could expose it through `WireConfig.perChunkLogDir` so apps can
toggle without code changes.

**4. Optional structured terminal event.** When the stream ends
(success or error), emit a final summary line:

```json
{
  "kind": "stream-end",
  "ts": "...",
  "requestId": "...",
  "totalChunks": 77,
  "totalDurationMs": 605432,
  "longestInterChunkGapMs": 547123,
  "longestGapAtChunkIndex": 73,
  "finishReason": null,
  "terminatedBy": "upstream-error"
}
```

The `longestInterChunkGapMs` field alone would make today's "did
Io Net stall mid-reasoning?" question answerable in one grep.

### Forensic workflows the new log enables

- **"Where did the stream stall?"** — `jq 'select(.kind == "chunk")
  | select(.elapsedSincePrevChunkMs > 60000)' sage-wire-chunks.jsonl`
  → lists every inter-chunk gap > 1 minute.
- **"How fast does each provider stream?"** — group chunks by
  `provider` (if available in the parsed shape), compute
  median/p99 inter-chunk gap per provider. Surfaces slow upstreams
  empirically rather than anecdotally.
- **"Did chunk N contain an error?"** — `jq 'select(.shape.hasError)'`
  — composes with #193's parser fix to find every wire-level
  upstream error.
- **"Total stream cost breakdown."** — combine the per-chunk
  `byteSize` with the request line's `model` to compute per-second
  bandwidth, surface buffering issues.

### Privacy / size considerations

- The `preview` field carries raw chunk content (first ~200 chars).
  For prompts with sensitive payloads, hosts can disable the
  preview entirely (`previewBytes = 0`) and keep only timing +
  shape.
- Per-chunk lines for a 77-chunk reasoning stream would be ~77 lines
  of ~300 bytes each = ~23 KB. For a typical session with 50-100
  streaming responses, ~1-2 MB per day. Manageable; the existing
  `sage-wire.jsonl` is already 46 MB in the current session.
- Hosts that don't enable per-chunk logging pay zero cost.

### Test sketch

Under `core/src/test/scala/sigil/provider/debug/`:

1. Build a `JsonLinesInterceptor` with `perChunkLogPath = Some(tmp)`.
2. Wire it into a fake provider that emits N SSE chunks at known
   intervals.
3. Assert:
   - One `chunk` line per SSE chunk in the per-chunk file.
   - `elapsedSincePrevChunkMs` values match the fixture's delays
     (within tolerance).
   - Final `stream-end` line carries `totalChunks = N`,
     `totalDurationMs` matches, `longestInterChunkGapMs` matches
     the largest fixture delay.
4. Negative: with `perChunkLogPath = None`, no per-chunk file is
   created; the main `wire.jsonl` is unchanged.

### Related

- Bug #192 — wire parser drops OpenRouter's `reasoning` field.
  Per-chunk logging would have caught this earlier — a glance at
  the per-chunk shape would have shown `"reasoning": " The"`
  fragments arriving and we'd have noticed the parser wasn't
  picking them up.
- Bug #193 — wire parser drops mid-stream `error` chunks. Same
  story: per-chunk shape with `hasError = true` would have made
  the upstream error obvious immediately instead of requiring
  manual SSE-chunk decoding for diagnosis.
- Bug #190 — corruption-resistance architecture. Per-chunk
  diagnostics make the "is the framework keeping up with the
  stream?" question observable, which composes with #190's
  invariant-check / retry decisions (orchestrator can see "stream
  is stalled" via this signal and act before the upstream's
  idle-timeout fires).
