package spec

import fabric.rw.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.secrets.SecretRecord

import scala.concurrent.duration.*

/**
 * Round-trip coverage of the default [[sigil.secrets.DatabaseSecretStore]]:
 * encrypted set/get (string and structured), hashed set/verify,
 * kind-mismatch returns, TTL expiry, delete, overwrite-across-kinds.
 *
 * Uses [[TestSecretsSigil]] — a Sigil that mixes `SecretsCollections`
 * into its DB so `db.secrets` is reachable. Same shape any
 * `sigil-secrets` consumer would assemble for production use.
 */
class SecretStoreSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  TestSecretsSigil.initFor(getClass.getSimpleName)
  private val store = TestSecretsSigil.secretStore

  case class OAuthCreds(accessToken: String, refreshToken: String, expiresAt: Long) derives RW

  "DatabaseSecretStore" should {
    "round-trip a String secret via setEncrypted + get" in {
      val id: Id[SecretRecord] = Id("encrypted.api-key")
      for {
        _ <- store.setEncrypted[String](id, "sk-test-12345")
        out <- store.get[String](id)
        _ <- store.delete(id)
      } yield out shouldBe Some("sk-test-12345")
    }

    "round-trip a structured typed secret via setEncrypted[T] + get[T]" in {
      val id: Id[SecretRecord] = Id("encrypted.oauth")
      val creds = OAuthCreds("at-abc", "rt-xyz", 1781234567000L)
      for {
        _ <- store.setEncrypted[OAuthCreds](id, creds)
        out <- store.get[OAuthCreds](id)
        _ <- store.delete(id)
      } yield out shouldBe Some(creds)
    }

    "produce different ciphertext on repeated set of the same plaintext (per-record salt)" in {
      val a: Id[SecretRecord] = Id("salt.a")
      val b: Id[SecretRecord] = Id("salt.b")
      for {
        _ <- store.setEncrypted[String](a, "same-value")
        _ <- store.setEncrypted[String](b, "same-value")
        recA <- TestSecretsSigil.withDB(_.secrets.transaction(_.get(a)))
        recB <- TestSecretsSigil.withDB(_.secrets.transaction(_.get(b)))
        outA <- store.get[String](a)
        outB <- store.get[String](b)
        _ <- store.delete(a); _ <- store.delete(b)
      } yield {
        outA shouldBe Some("same-value")
        outB shouldBe Some("same-value")
        recA.flatMap(_.encryptedSalt) should not equal recB.flatMap(_.encryptedSalt)
      }
    }

    "round-trip a hashed secret via setHashed + verify (Argon2)" in {
      val id: Id[SecretRecord] = Id("hashed.password")
      for {
        _ <- store.setHashed(id, "hunter2")
        good <- store.verify(id, "hunter2")
        bad <- store.verify(id, "wrong")
        _ <- store.delete(id)
      } yield {
        good shouldBe true
        bad shouldBe false
      }
    }

    "hash a structured typed value as a unit (multi-field credential)" in {
      val id: Id[SecretRecord] = Id("hashed.recovery")
      val good = OAuthCreds("answer-1", "answer-2", 42L)
      val bad = OAuthCreds("answer-1", "answer-3", 42L) // any field difference flips the hash
      for {
        _ <- store.setHashed[OAuthCreds](id, good)
        ok <- store.verify[OAuthCreds](id, good)
        notOk <- store.verify[OAuthCreds](id, bad)
        _ <- store.delete(id)
      } yield {
        ok shouldBe true
        notOk shouldBe false
      }
    }

    "return None on get() for a hashed entry, false on verify() for an encrypted entry" in {
      val enc: Id[SecretRecord] = Id("kind.encrypted")
      val hsh: Id[SecretRecord] = Id("kind.hashed")
      for {
        _ <- store.setEncrypted[String](enc, "value-a")
        _ <- store.setHashed(hsh, "value-b")
        getOnHashed <- store.get[String](hsh)
        verifyOnEncrypted <- store.verify(enc, "value-a")
        _ <- store.delete(enc); _ <- store.delete(hsh)
      } yield {
        getOnHashed shouldBe None
        verifyOnEncrypted shouldBe false
      }
    }

    "respect TTL — entries past expiry return None / false and don't dereference" in {
      val id: Id[SecretRecord] = Id("ttl.short")
      for {
        _ <- store.setEncrypted[String](id, "ephemeral", ttl = Some(50.millis))
        _ <- rapid.Task.sleep(150.millis)
        out <- store.get[String](id)
        _ <- store.delete(id)
      } yield out shouldBe None
    }

    "delete is idempotent — no-op on missing id" in {
      val id: Id[SecretRecord] = Id("delete.missing")
      store.delete(id).map(_ => succeed)
    }

    "set overwrites across kinds — hashed → encrypted overwrite is observable" in {
      val id: Id[SecretRecord] = Id("overwrite.kind")
      for {
        _ <- store.setHashed(id, "first")
        firstVerify <- store.verify(id, "first")
        _ <- store.setEncrypted[String](id, "second")
        secondGet <- store.get[String](id)
        verifyAfterOverwrite <- store.verify(id, "first")
        _ <- store.delete(id)
      } yield {
        firstVerify shouldBe true
        secondGet shouldBe Some("second")
        verifyAfterOverwrite shouldBe false   // hashed entry is gone
      }
    }

    "missing id returns None / false" in {
      val id: Id[SecretRecord] = Id("absent")
      for {
        g <- store.get[String](id)
        v <- store.verify(id, "anything")
      } yield {
        g shouldBe None
        v shouldBe false
      }
    }

    "type mismatch on get[T] returns None rather than throwing" in {
      val id: Id[SecretRecord] = Id("typemismatch")
      for {
        _ <- store.setEncrypted[OAuthCreds](id, OAuthCreds("a", "b", 1L))
        // Reading as a different type — fabric's RW.write fails to lift
        // the OAuthCreds JSON object into a String. The store catches
        // the failure and returns None rather than throwing. Apps that
        // round-trip secrets across versions own their own JSON
        // versioning convention.
        outAsString <- store.get[String](id)
        _ <- store.delete(id)
      } yield outAsString shouldBe None
    }
  }
}
