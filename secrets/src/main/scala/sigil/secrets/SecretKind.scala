package sigil.secret

import fabric.rw.*

/**
 * Storage mode for a [[SecretStore]] entry.
 *
 *   - [[Encrypted]] — symmetric-encrypted at rest, retrievable via
 *     [[SecretStore.get]]. Used for API tokens, OAuth refresh
 *     tokens, and any credential the framework / a tool needs to
 *     read back as plaintext to call an external service.
 *   - [[Hashed]] — one-way hashed at rest, verify-only via
 *     [[SecretStore.verify]]. Used for user passwords and any other
 *     credential where the framework only ever needs to compare a
 *     candidate against the stored value.
 */
enum SecretKind derives RW {
  case Encrypted
  case Hashed
}
