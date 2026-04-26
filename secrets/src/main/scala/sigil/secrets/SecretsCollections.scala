package sigil.secrets

import sigil.db.SigilDB

/**
 * lightdb collection mix-in that adds the `secrets` store to a
 * [[SigilDB]] subclass. Apps that pull in `sigil-secrets` declare
 * their concrete DB as
 * `class MyAppDB(...) extends SigilDB(...) with SecretsCollections`,
 * then refine `type DB = MyAppDB` on their Sigil instance via
 * [[SecretsSigil]].
 */
trait SecretsCollections { self: SigilDB =>
  val secrets: S[SecretRecord, SecretRecord.type] = store(SecretRecord)()
}
