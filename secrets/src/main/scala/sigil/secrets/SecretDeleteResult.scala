package sigil.secrets

import fabric.rw.*
import lightdb.id.Id
import sigil.signal.Notice

/** Server→client [[Notice]] reply to a [[RequestSecretDelete]]. */
case class SecretDeleteResult(secretId: Id[SecretRecord], success: Boolean) extends Notice derives RW
