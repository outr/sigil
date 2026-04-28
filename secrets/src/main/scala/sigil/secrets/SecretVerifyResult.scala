package sigil.secrets

import fabric.rw.*
import lightdb.id.Id
import sigil.signal.Notice

/** Server→client [[Notice]] reply to a [[RequestSecretVerify]]. */
case class SecretVerifyResult(secretId: Id[SecretRecord], matched: Boolean) extends Notice derives RW
