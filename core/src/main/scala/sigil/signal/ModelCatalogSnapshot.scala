package sigil.signal

import fabric.rw.*
import sigil.db.Model

/**
 * Sigil #293 — server→client [[Notice]] carrying the catalog of
 * [[Model]] rows matching the recipient viewer's
 * [[RequestModelCatalog]] (or an unsolicited push when the registry
 * refreshes).
 *
 * Carries the full `Model` records so UIs can render any field they
 * need (pricing, context length, modality, knowledge cutoff, …)
 * without a follow-up fetch.
 */
case class ModelCatalogSnapshot(models: List[Model]) extends Notice derives RW
