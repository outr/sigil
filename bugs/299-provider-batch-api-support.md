# ❌ #299 — `Provider` abstraction has no batch-API path (apps that fire thousands of OneShotRequests against batchable providers leave 50% on the table)

**Where:**

- `core/src/main/scala/sigil/provider/Provider.scala` — exposes only
  the streaming-sync surface (`apply(request: OneShotRequest):
  Stream[ProviderEvent]`). No bulk variant.
- Every concrete impl that has a native batch surface upstream:
  - `core/src/main/scala/sigil/provider/openai/OpenAIProvider.scala`
    (OpenAI Batch API — `/v1/batches`, 50% discount on `gpt-4o`,
    `gpt-4o-mini`, `gpt-5.x`, etc., 24-hour SLA, effectively
    unlimited per-batch rate).
  - `core/src/main/scala/sigil/provider/anthropic/AnthropicProvider.scala`
    (Anthropic Message Batches API — same 50% discount, same SLA
    shape).
  - `core/src/main/scala/sigil/provider/google/GoogleProvider.scala`
    (Gemini Batch API — also 50%, same window).
  - DeepInfra + OpenRouter routes either inherit or pass through to
    underlying provider; same surface.

**What's wanted:**

Real-world Sigil consumers running batch ingestion (RAG corpus
rebuilds, bulk classification, periodic re-summarization passes) are
firing thousands of `OneShotRequest`s sequentially against gpt-4o /
gpt-4o-mini / Claude. Discovered in widge-server's manufacturing
RAG pipeline — a single tj_clark rebuild (41 docs, ~800 pages)
makes ~2000 OpenAI calls between the per-page page-classifier,
per-page vision rewrite, per-chunk optimizer, and embedding pass.
Sequential = hours of wall-clock + full-price spend. Customers with
"giant repositories" (5000+ docs) are looking at days.

OpenAI Batch + Anthropic Message Batches + Gemini Batch all solve
exactly this case at the wire layer — but only if Sigil routes
through them. With Sigil's current `apply(OneShotRequest)` surface
the only option is N parallel sync calls (mitigated only by rapid
concurrency on the app side), still paying full price.

**Suggested fix:**

Add a `batch` method to the `Provider` trait. Default implementation
falls back to per-request `apply` calls (correctness for impls that
have no native batch); each native-batch provider overrides with
the wire-level batch API.

```scala
// In Provider.scala
/** Bulk-submit many OneShotRequests. Providers with a wire-level
  * batch surface (OpenAI Batch, Anthropic Message Batches, Gemini
  * Batch) override to get ~50% cost reduction + higher throughput
  * + async SLA. The default falls back to sequential `apply` calls
  * so any caller can use `batch` regardless of provider — the
  * concurrent-on-failure-fallback degrades to "the same wall-clock
  * as sequential apply" rather than failing. */
def batch(requests: List[OneShotRequest]): Task[Map[Id[ProviderRequest], OneShotResponse]] =
  // Default: sequential apply, collect, return. Correct everywhere;
  // optimal nowhere a native batch exists.
  ???
```

Companion type `OneShotResponse` carries:
- `requestId: Id[ProviderRequest]` (the matching request's `requestId`)
- `content: Vector[ResponseContent]` (accumulated text + image
  outputs, same shape `apply(...).drain` would have produced)
- `usage: Option[Usage]` (token counts when the provider returns
  them in the batch result — OpenAI does, Anthropic does)
- `error: Option[ProviderError]` (per-request error — batch APIs
  surface partial failures; one bad request shouldn't kill the
  batch)

OpenAI override flow (sketch):
1. Render each request into an OpenAI batch JSONL line:
   `{"custom_id":"<requestId>","method":"POST","url":"/v1/chat/completions","body":<translate(request)>}`
2. Upload JSONL to `/v1/files` with `purpose=batch`.
3. Create batch via `/v1/batches` with the file id +
   `completion_window=24h`.
4. Poll the batch (with reasonable backoff; OpenAI returns 30 min
   to several hours typically) until status is `completed` /
   `failed` / `cancelled` / `expired`.
5. Download output file, parse line-by-line into `OneShotResponse`
   keyed by `custom_id` (= `requestId`).
6. Best-effort delete the input + output files (cleanup).

Anthropic + Gemini follow analogous patterns — different upload
mechanism, same logical shape.

**Bonus considerations:**

- **Cost-aware routing**: if the framework had this surface,
  `Sigil.providerFor` could pick batch vs sync based on a caller-
  supplied policy (e.g. `BatchPolicy.PreferBatch` for offline
  pipelines, `BatchPolicy.RequireSync` for interactive UX).
- **Partial completion**: long-running batches need to be queryable
  by id and resumable across app restarts. A `Provider.attachBatch
  (batchId: String): Task[OneShotResponse]` companion call would
  let apps persist the batch id and reattach after a crash. Could
  ship as a follow-up once `batch` itself is in.
- **No retry semantics shift**: the default fallback uses the
  existing per-request retry path (`callWithTransientRetry`).
  Native-batch impls handle their own retries at the batch
  granularity (OpenAI surfaces failed entries per-line; we treat
  them as `error` in the per-request response, no batch-level
  retry needed beyond network blips around the batch endpoint
  itself).

**Test sketch:**

- Build a list of 10 `OneShotRequest`s with distinct `userPrompt`s,
  identifiable from the response.
- Call `openAIProvider.batch(requests)`.
- Assert the returned map has 10 entries keyed by the original
  `requestId`s and each `content` matches the prompt distinctively.
- Mock-server variant: stub `/v1/files`, `/v1/batches`, the polling
  endpoint, and the output file — assert the request line for each
  input renders into the right JSONL shape and that the response
  parse round-trips.
- Default-impl variant: against a provider with no batch override
  (e.g. an llama.cpp local backend), assert the same `batch` call
  works via fallback and produces identical results to N
  individual `apply` calls.

**Consumer wiring (widge-server example):**

In `DocumentPipeline`'s post-Doc-AI phase widge-server today
sequentially calls (per doc): `PageClassifier.classify(thumb)`
(gpt-4o-mini, one per page) → `HybridPdfExtractor.fullPageVisionRewrite`
(gpt-4o, per Visual/Mixed page) → `optimizeChunk` (gpt-4o-mini, one
per chunk). All three are pure batchable workloads — no inter-call
dependencies inside a phase. The right Sigil surface lets widge
rewrite this as three big `provider.batch(...)` calls across the
whole corpus, dropping a 1-hour run to maybe 30-45 min and saving
~$5-10 per rebuild on tj_clark-scale corpora. Scales linearly with
customer corpus size — savings are real for the multi-thousand-doc
case.
