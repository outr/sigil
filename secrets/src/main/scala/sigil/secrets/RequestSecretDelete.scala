package sigil.secrets

import fabric.rw.*
import lightdb.id.Id
import sigil.signal.Notice

/** Client→server [[Notice]]: remove the stored secret at `secretId`.
  * Idempotent — a missing entry still replies with `success = true`.
  * Server replies with [[SecretDeleteResult]]. */
case class RequestSecretDelete(secretId: Id[SecretRecord]) extends Notice derives RW
