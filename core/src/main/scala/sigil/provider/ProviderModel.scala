package sigil.provider

import sigil.db.Model

/**
 * A resolved (provider, model) pair: the live [[Provider]] that serves
 * `model`, alongside its registered [[Model]] record. The runtime result
 * of [[ModelResolver.resolve]] — a transient binding handed straight to
 * the turn pipeline (the provider's `apply`, the request's `model`),
 * never persisted. Pairing the two here means a single resolve answers
 * both "who serves this id" and "what's the registered record" without a
 * second registry lookup at the call site.
 */
final case class ProviderModel(provider: Provider, model: Model)
