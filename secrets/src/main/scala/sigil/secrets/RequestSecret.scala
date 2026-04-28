package sigil.secrets

import fabric.rw.*
import lightdb.id.Id
import sigil.signal.Notice

/**
 * Client→server [[Notice]]: ask the server for the value of a stored
 * secret. The server's [[SecretsSigil.handleNotice]] arm replies with
 * a [[SecretSnapshot]] targeted at the requesting viewer.
 *
 * Only encrypted (retrievable) secrets surface a value through this
 * Notice — hashed secrets reply with `value = None`. Apps that need
 * to verify a candidate against a hashed secret use
 * [[RequestSecretVerify]] instead.
 */
case class RequestSecret(secretId: Id[SecretRecord]) extends Notice derives RW
