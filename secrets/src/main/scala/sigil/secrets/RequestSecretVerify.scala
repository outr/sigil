package sigil.secrets

import fabric.rw.*
import lightdb.id.Id
import sigil.signal.Notice

/**
 * Client→server [[Notice]]: verify a candidate value against a stored
 * hashed secret. Server replies with [[SecretVerifyResult]].
 *
 * Returns `match = false` for encrypted entries, missing entries, or
 * entries past their TTL.
 */
case class RequestSecretVerify(secretId: Id[SecretRecord], candidate: String) extends Notice derives RW
