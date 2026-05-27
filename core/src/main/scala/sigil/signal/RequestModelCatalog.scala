package sigil.signal

import fabric.rw.*

/**
 * Sigil #293 — client→server [[Notice]]: "send me the global model
 * catalog matching these filters." The default
 * [[sigil.Sigil.handleNotice]] arm responds with a
 * [[ModelCatalogSnapshot]] targeted at the requesting viewer.
 *
 * Backed by [[sigil.Sigil.cache]] (the in-memory [[sigil.cache.ModelRegistry]])
 * so the answer is global — no per-viewer scoping. Apps that want
 * per-tenant restrictions override `handleNotice`.
 *
 *   - `provider` — narrow to one provider namespace
 *                  (`"openai"`, `"anthropic"`, …); case-insensitive
 *                  match against `Model.provider`.
 *   - `modality` — narrow to a modality token
 *                  (`"text"`, `"image"`, `"audio"`, …);
 *                  case-insensitive match against
 *                  `Model.architecture.modality` OR any entry of
 *                  `Model.architecture.inputModalities`.
 *   - `query`    — substring match against `Model.name` or
 *                  `Model._id.value` (case-insensitive).
 */
case class RequestModelCatalog(provider: Option[String] = None,
                                modality: Option[String] = None,
                                query: Option[String] = None) extends Notice derives RW
