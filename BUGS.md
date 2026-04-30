# Bugs

Tracking bugs found in Sigil, Spice, Fabric, and related libraries while building a downstream consumer on top of Sigil. Each entry: where it lives, what's wrong, what we observed, suggested fix.

**Status legend:** ⚠️ workaround in place · ❌ open · ✅ fixed.

Numbering preserves history — gaps reflect entries that were filed, fixed, and pruned.

---

## ❌ #41 — `SecretsSigil`'s default `<dbPath>/crypto.key` auto-generation is a wrong default for security material; consumers should be required to supply the key explicitly

**Where:**
- `secrets/src/main/scala/sigil/secrets/SecretsSigil.scala` lines 49–52:
  ```scala
  def secretStore: SecretStore = defaultSecretStore

  private final lazy val defaultSecretStore: SecretStore =
    DatabaseSecretStore.default(this)
  ```
- `secrets/src/main/scala/sigil/secrets/DatabaseSecretStore.scala` lines 169–199 — auto-generates a 65-char `rapid.Unique`-based key at `<dbPath>/crypto.key`, mode `0600`, on first call.

**What's wrong:** the default behaviour is "if no key file exists, generate one and write it to `<dbPath>/crypto.key`." That's a wrong-by-default posture for a security primitive:

- **Silent first-run generation** ties the key to one install location with no explicit consumer decision. The deployment didn't say "store the key here, like this" — the framework just picked a path.
- **Lose the file → lose every encrypted secret**, with no recovery story. The deployment doesn't necessarily realise this file is the single point of failure for everything they encrypted.
- **Doesn't fit container / KMS / config-injection patterns.** Containers prefer config-via-env or mounted-secrets; the file-based path forces a sidecar volume just to mount the right key, or a wrapper that shells out the value into a file at boot.
- **Inconsistent with how the framework treats other security material.** `SlackSigil.slackClientCredentials` is *required*, no default — apps must supply OAuth credentials explicitly. The crypto key is more sensitive than Slack creds (losing it loses every encrypted secret in the database, not just one workspace's auth) and should follow the same pattern.

Voidcraft already moved off this default — the active `backend/.../server/ServerCrypto.scala` reads from `App.config.crypto.key` (typed Profig config field, `case class CryptoConfig(key: String) derives RW`), with the older file-based `server/.../ServerCrypto.scala` left in the tree as a vestige. Sage wants the same shape. Both consumers having to override `secretStore` wholesale to do this — when the only thing they're changing is *the source of the key string* — is the symptom.

**Suggested fix:** require consumers to supply the key. Drop the file-based auto-generation default entirely. Replace `secretStore: SecretStore = defaultSecretStore` with an abstract `secretStoreKey: String` hook the consumer must implement; the framework wires it into the standard `DatabaseSecretStore` automatically.

```scala
// in SecretsSigil:

/** AES symmetric key string the framework's standard
  * [[DatabaseSecretStore]] uses to encrypt + decrypt secret
  * records. Supplied by the consumer from typed Profig config,
  * env var, KMS / Vault, mounted file, or wherever the
  * deployment's key-management story lives.
  *
  * No default — losing this key invalidates every encrypted
  * record in the database, so the framework refuses to silently
  * generate one. Consumers make a deliberate decision about
  * key source.
  *
  * Voidcraft pattern (config-driven):
  *
  * {{{
  *   override def secretStoreKey: String = App.config.crypto.key
  * }}}
  *
  * Sage pattern (Profig directly):
  *
  * {{{
  *   override def secretStoreKey: String =
  *     Profig("sage.crypto.key").as[String]
  * }}}
  *
  * Apps with rotation / per-tenant keys / fancier policies
  * still override `secretStore` directly with their own
  * [[SecretStore]] implementation. */
def secretStoreKey: String

def secretStore: SecretStore = defaultSecretStore

private final lazy val defaultSecretStore: SecretStore =
  new DatabaseSecretStore(this, secretStoreKey)
```

Drop `DatabaseSecretStore.default` and `defaultKeyPath` from the API entirely — they were only useful when the framework was making the wrong default choice. `DatabaseSecretStore.fromKeyFile` can stay (apps that genuinely want file-on-disk material — rare but legitimate — opt in explicitly via that constructor), but it's no longer the default path.

**Migration:**
- Existing consumers using the file-based default and a key file at `<dbPath>/crypto.key` add a one-line `override def secretStoreKey: String = java.nio.file.Files.readString(java.nio.file.Path.of("<dbPath>/crypto.key")).trim`. Or move the value into config and override accordingly.
- New consumers can't ship without making the decision — which is the whole point.

A regression test: instantiate a `SecretsSigil` test fixture without overriding `secretStoreKey`; assert it fails to compile (or fails at framework init with a clear error if it ends up resolved at runtime). Existing tests presumably pass an explicit key already; pin that contract.

**Naming nit:** `secretStoreKey` is the literal name of the AES material, which feels like it's leaking implementation. If you'd prefer abstraction (`secretEncryptionKey`, `cryptoKey`, `secretsMasterKey`, etc.), pick whichever — the shape is what matters.
