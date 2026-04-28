package sigil.secrets

import fabric.rw.*
import lightdb.id.Id
import sigil.signal.Notice

/** Server→client [[Notice]] reply to a [[RequestSecretSet]]. `success`
  * is true when the underlying [[SecretStore]] write completed. */
case class SecretSetResult(secretId: Id[SecretRecord], success: Boolean) extends Notice derives RW
