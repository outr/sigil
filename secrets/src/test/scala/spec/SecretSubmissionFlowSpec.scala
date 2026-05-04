package spec

import fabric.rw.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.conversation.{Conversation, Topic}
import sigil.event.Message
import sigil.secrets.{SecretRecord, SecretSubmission}
import sigil.security.SecretKind
import sigil.tool.model.ResponseContent

import java.util.UUID

/**
 * End-to-end coverage of the SecretInput → SecretSubmission →
 * SecretCaptureTransform → Message-with-SecretRef flow.
 *
 * What's verified:
 *   - Publishing a `SecretSubmission` (encrypted) writes the value to
 *     `SecretStore` and the in-flight signal is replaced by a
 *     `Message` from the submitter whose `content` is a
 *     `ResponseContent.SecretRef` with the original `secretId` and
 *     `label`. The plaintext is reachable via `secretStore.get`.
 *   - Same for hashed: value is verifiable via `secretStore.verify`,
 *     the rendered Message carries only the SecretRef.
 *   - The `SecretSubmission` itself never persists to
 *     `SigilDB.events` — only the post-transform Message is stored.
 *   - `SigilDB.events` doesn't carry the plaintext value anywhere.
 */
class SecretSubmissionFlowSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  TestSecretsSigil.initFor(getClass.getSimpleName)

  private val store = TestSecretsSigil.secretStore

  "SecretCaptureTransform" should {

    "consume a SecretSubmission(Encrypted), persist via SecretStore.setEncrypted, and replace with a Message bearing SecretRef" in {
      val secretId = s"test.encrypted.${UUID.randomUUID().toString.take(8)}"
      val convId = Conversation.id(s"conv-${UUID.randomUUID().toString.take(8)}")
      val topicId: Id[Topic] = Id("topic-flow")
      val submission = SecretSubmission(
        secretId = secretId,
        value = "sk-test-12345",
        kind = SecretKind.Encrypted,
        label = "OpenAI key",
        participantId = SubmittingUser,
        conversationId = convId,
        topicId = topicId
      )
      for {
        _ <- TestSecretsSigil.publish(submission)
        retrieved <- store.get[String](Id(secretId))
        events <- TestSecretsSigil.withDB(_.events.transaction(_.list))
        relevant = events.filter(_.conversationId == convId).sortBy(_.timestamp.value)
      } yield {
        retrieved shouldBe Some("sk-test-12345")
        relevant should have size 1
        val msg = relevant.head.asInstanceOf[Message]
        msg.participantId shouldBe SubmittingUser
        msg.content should have size 1
        val ref = msg.content.head.asInstanceOf[ResponseContent.SecretRef]
        ref.secretId shouldBe secretId
        ref.label shouldBe "OpenAI key"
        relevant.exists(_.isInstanceOf[SecretSubmission]) shouldBe false
        val serialized = events.map(_.toString).mkString("\n")
        serialized should not include "sk-test-12345"
        succeed
      }
    }

    "consume a SecretSubmission(Hashed), persist via SecretStore.setHashed, and verify against a candidate" in {
      val secretId = s"test.hashed.${UUID.randomUUID().toString.take(8)}"
      val convId = Conversation.id(s"conv-${UUID.randomUUID().toString.take(8)}")
      val topicId: Id[Topic] = Id("topic-flow-2")
      val submission = SecretSubmission(
        secretId = secretId,
        value = "hunter2",
        kind = SecretKind.Hashed,
        label = "Account password",
        participantId = SubmittingUser,
        conversationId = convId,
        topicId = topicId
      )
      for {
        _ <- TestSecretsSigil.publish(submission)
        good <- store.verify[String](Id(secretId), "hunter2")
        bad <- store.verify[String](Id(secretId), "wrong")
        events <- TestSecretsSigil.withDB(_.events.transaction(_.list))
        relevant = events.filter(_.conversationId == convId)
      } yield {
        good shouldBe true
        bad shouldBe false
        relevant should have size 1
        val msg = relevant.head.asInstanceOf[Message]
        msg.content.head shouldBe ResponseContent.SecretRef(secretId = secretId, label = "Account password")
        succeed
      }
    }

    "the in-flight SecretSubmission must never leave plaintext in the events store" in {
      val secretId = s"test.no-leak.${UUID.randomUUID().toString.take(8)}"
      val convId = Conversation.id(s"conv-${UUID.randomUUID().toString.take(8)}")
      val topicId: Id[Topic] = Id("topic-no-leak")
      val submission = SecretSubmission(
        secretId = secretId,
        value = "super-secret-value-987",
        kind = SecretKind.Encrypted,
        label = "vault",
        participantId = SubmittingUser,
        conversationId = convId,
        topicId = topicId
      )
      for {
        _ <- TestSecretsSigil.publish(submission)
        events <- TestSecretsSigil.withDB(_.events.transaction(_.list))
      } yield {
        val serialized = events.map(_.toString).mkString("\n")
        serialized should not include "super-secret-value-987"
        events.exists(_.isInstanceOf[SecretSubmission]) shouldBe false
        succeed
      }
    }
  }

  "tear down" should {
    "dispose TestSecretsSigil" in TestSecretsSigil.shutdown.map(_ => succeed)
  }
}
