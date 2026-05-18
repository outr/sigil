package sigil.tooling.dispatch

import fabric.rw.*

/**
 * Per-item processing pipeline. Each worker item flows through (in
 * order): the optional [[LlmStep]] (worker LLM call with item
 * substitution), then the optional [[ScriptStep]] (deterministic
 * code execution with the LLM's output as input). Both are
 * `Option`s — a pipeline with `llm = Some(...)` and `script = None`
 * is the "classify each item" shape; a pipeline with both is the
 * "LLM proposes / script applies" refactor shape; a pipeline with
 * only `script = Some(...)` is the "deterministic per-item
 * computation" shape (no model cost).
 *
 * At least one of the two steps must be set; an empty pipeline is
 * rejected at the input level.
 */
case class WorkerPipeline(llm: Option[LlmStep] = None,
                          script: Option[ScriptStep] = None) derives RW
