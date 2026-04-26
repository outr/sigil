package sigil.security

import fabric.rw.*

/**
 * Storage mode for a secret. Lives in core so
 * [[sigil.tool.model.ResponseContent.SecretInput]] can reference it
 * without core depending on the `sigil-secrets` module.
 *
 *   - [[Encrypted]] — symmetric-encrypted at rest, retrievable as
 *     plaintext. For API tokens, OAuth credentials.
 *   - [[Hashed]] — one-way hashed at rest, verify-only. For user
 *     passwords.
 */
enum SecretKind derives RW {
  case Encrypted
  case Hashed
}
