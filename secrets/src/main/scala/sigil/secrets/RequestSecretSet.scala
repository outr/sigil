package sigil.secrets

import fabric.rw.*
import lightdb.id.Id
import sigil.security.SecretKind
import sigil.signal.Notice

/**
 * Client→server [[Notice]]: store a string-valued secret. The server
 * routes via [[SecretStore.setEncrypted]] when `kind = Encrypted` or
 * [[SecretStore.setHashed]] when `kind = Hashed`. Server replies with
 * [[SecretSetResult]].
 *
 * Notices carry only string values — apps that need typed multi-field
 * secrets call `Sigil.secretStore.setEncrypted[T]` server-side directly
 * within their own `handleNotice` override.
 */
case class RequestSecretSet(secretId: Id[SecretRecord],
                            value: String,
                            kind: SecretKind) extends Notice derives RW
