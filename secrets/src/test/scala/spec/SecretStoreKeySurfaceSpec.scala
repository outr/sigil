package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.secrets.DatabaseSecretStore

import java.nio.file.{Files, NoSuchFileException, Path}

/**
 * Regression for bug #41 — pins the new key-source contract:
 *
 *   1. [[DatabaseSecretStore.fromKeyFile]] requires the file to
 *      exist; it does NOT silently auto-generate. Apps that want a
 *      generated key persisted to disk make the decision explicitly
 *      via [[DatabaseSecretStore.generateKeyFile]].
 *   2. [[DatabaseSecretStore.generateKeyFile]] refuses to overwrite
 *      an existing file — overwriting would invalidate every secret
 *      already encrypted under the old material.
 *   3. [[sigil.secrets.SecretsSigil.secretStoreKey]] is abstract —
 *      a fixture that doesn't override it must not compile. We can
 *      assert this via a `compileErrors` check in a typeclass-style
 *      compile-time test, but a simpler runtime proxy is "any
 *      concrete instance MUST have provided one" — covered by the
 *      [[TestSecretsSigil]] running its full test suite without
 *      crashing during init. That's already exercised by
 *      [[SecretStoreSpec]]; this spec adds the file-side guarantees.
 */
class SecretStoreKeySurfaceSpec extends AnyWordSpec with Matchers {

  "DatabaseSecretStore.fromKeyFile" should {
    "throw NoSuchFileException when the key file does not exist" in {
      val nonexistent = Path.of("/tmp", s"sigil-no-such-key-${rapid.Unique()}.key")
      Files.exists(nonexistent) shouldBe false
      a [NoSuchFileException] should be thrownBy DatabaseSecretStore.fromKeyFile(null, nonexistent)
    }
  }

  "DatabaseSecretStore.generateKeyFile" should {
    "create the file with a fresh key on first call" in {
      val path = Files.createTempFile("sigil-key-gen-", ".key")
      Files.deleteIfExists(path)  // tempFile creates it; we want it absent for the test
      try {
        val key = DatabaseSecretStore.generateKeyFile(path)
        key should not be empty
        Files.exists(path) shouldBe true
        Files.readString(path).trim shouldBe key
      } finally Files.deleteIfExists(path)
    }

    "refuse to overwrite an existing file (rotation must be deliberate)" in {
      val path = Files.createTempFile("sigil-key-gen-", ".key")
      try {
        Files.writeString(path, "existing-key-do-not-clobber")
        a [java.nio.file.FileAlreadyExistsException] should be thrownBy
          DatabaseSecretStore.generateKeyFile(path)
        // Existing content untouched
        Files.readString(path).trim shouldBe "existing-key-do-not-clobber"
      } finally Files.deleteIfExists(path)
    }
  }
}
