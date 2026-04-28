package sigil.secrets

import fabric.rw.*
import lightdb.id.Id
import sigil.signal.Notice

/**
 * Server→client [[Notice]] reply to a [[RequestSecret]]. `value` is
 * `None` when the secret is missing, expired (TTL), hashed (not
 * retrievable), or fails to decrypt as a string.
 */
case class SecretSnapshot(secretId: Id[SecretRecord], value: Option[String]) extends Notice derives RW
