package sigil.storage

/**
 * Outcome of a [[StorageProvider.writeIfMatch]] call.
 *
 *   - [[Written]] — the conditional write succeeded; `version` is the
 *     fresh verification token the caller can pass on its next CAS.
 *   - [[Stale]] — the storage's current `hash` did not match the
 *     caller's `expected`. `current` carries the freshest snapshot
 *     so the caller can re-evaluate its edit and retry without a
 *     separate read round-trip.
 *   - [[NotFound]] — there is no object at `path` to compare against.
 *     Distinct from [[Stale]] so callers can distinguish "I need to
 *     re-edit on top of newer content" from "the file was deleted
 *     out from under me."
 *
 * Not derived RW — flows through tool code as a typed result, not on
 * the wire. Tools that surface a [[Stale]] outcome render the embedded
 * `current` snapshot to a JSON payload explicitly so the agent sees a
 * structured "stale read" message.
 */
enum WriteResult {
  case Written(version: FileVersion)
  case Stale(current: StorageContents)
  case NotFound
}
