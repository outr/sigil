# ❌ #151 — Chat-completion request takes 307 s wall-clock against local llama, but llama-cpp reports 4 s of internal work — HTTP response held somewhere on the path back

**Where:**
- `core/src/main/scala/sigil/provider/llamacpp/LlamaCppProvider.scala` — the provider's HTTP call
- Likely `spice.http.client.HttpClient` streaming consumer, or Sigil's `wireInterceptor` post-processing path

**What's wrong:**

In a Sage wire log captured while running an imported 51 K-frame Claude Code conversation, one chat-completion request shows a wall-clock duration ~75× larger than the model's actual work:

```
L375 request,  ts: 05/11/2026 21:07:51,  POST /v1/chat/completions
L376 response, ts: 05/11/2026 21:12:58
wall-clock:    307 seconds

llama-cpp's own timings (from the response body):
  prompt_n           = 18170
  prompt_ms          = 2945.716    (prefill: ~3 s)
  predicted_n        = 158
  predicted_ms       = 969.013     (generation: ~1 s)
  total internal work: ~4 seconds
finish_reason: tool_calls
```

So llama-cpp executed the request in ~4 s, but the wire-log interceptor recorded the response timestamp 5 minutes after the request. The other ~303 s is unaccounted for — held somewhere between llama-cpp's last token emission and Sigil's interceptor `after()` call.

The wire log file is at:

```
/home/mhicks/projects/clients/outr/sage/backend/target/sage-wire.jsonl
```

Lines 371-376 are the relevant request/response pair (request preflight + main response). The response body at line 376 has the full SSE stream including the final `data: [DONE]` and the `timings` block showing the internal numbers above. Reading those lines should let you reproduce the analysis directly.

**Hypotheses worth checking:**

1. **Spice `HttpClient.streamLines`** — the consumer reads the response body line-by-line via `rapid.Stream`. If the consumer fiber is blocked behind some other work, the stream's terminal frame doesn't get pulled out of the OS buffer until the fiber resumes. The interceptor's `after()` hook fires when the stream completes from its perspective — which means after the consumer drains it, not when llama-cpp finished writing.

2. **`wireInterceptor`'s `StreamWireInterceptor.attach`** — Sigil wraps the stream to capture SSE lines. If the interceptor's buffering or fiber-park behavior holds the stream open after data stops arriving (e.g. waiting on a timeout to confirm the stream really ended), the response-close event lags by that timeout. Worth checking what makes the interceptor decide a stream is "done."

3. **Concurrent request queuing on a single-slot llama-cpp** — if llama-cpp's `total_slots = 1` (Sage default), and another chat-completion request was queued behind this one, llama-cpp would serialize them. But that would show up as a delay in the *next* request's start, not in this one's response. Unless the stream is held open by HTTP keep-alive while llama-cpp prepares for the next request.

4. **Rapid fiber scheduling** — `rapid.fiber.SynchronousFiber` parks the consumer fiber waiting on `LinkedBlockingQueue.poll`. Under contention (many fibers competing for the FJP-1 carrier threads), the consumer might not get scheduled to drain the response promptly. Less likely on 0% CPU idle, but worth verifying with a stack dump while it's happening.

**Why this matters:**

Today the user isn't blocked by this — llama-cpp's work is done, the agent loop's downstream consumer eventually reads the response and proceeds. So no user-visible hang. But:

- Any code measuring "how long did the LLM take" off the HTTP timing gets numbers ~75× too high
- If the buffering blocks other concurrent HTTP requests on the same client, multi-slot or parallel-call setups would silently serialize
- The wire log itself becomes misleading for diagnosis — future bugs filed against "this request was slow" can't trust the timestamps

**Suggested fix:**

Investigate before fixing. Concrete starting points:

1. Reproduce locally: replay the request from the wire log against a local llama-cpp, watch netstat / `lsof` on the response socket, time-stamp each SSE chunk's arrival vs the framework's interceptor `after()` invocation
2. Compare the response-close timestamp recorded by the wire interceptor against the actual `[DONE]` frame's arrival time (parse it out of the SSE buffer)
3. If the gap is in the interceptor's stream-completion detection, fix the detection to close on `[DONE]` rather than EOF / idle timeout
4. If the gap is in `HttpClient.streamLines`, look at how the fiber drains the final frames — there may be a `rapid.Pull` that parks on a queue with a long default poll timeout

This is a prevention fix at the source — once the wire log timestamps are correct, downstream measurement is correct for free.

**Severity:** Investigation. Not user-blocking, but actively misleading any future diagnosis that consults the wire log.
