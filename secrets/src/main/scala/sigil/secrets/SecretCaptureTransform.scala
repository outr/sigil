package sigil.secrets

import fabric.rw.*
import lightdb.id.Id
import rapid.Task
import sigil.Sigil
import sigil.event.{Event, Message}
import sigil.pipeline.InboundTransform
import sigil.security.SecretKind
import sigil.signal.{EventState, Signal}
import sigil.tool.model.ResponseContent

/**
 * Default `inboundTransform` for [[SecretsSigil]] — intercepts
 * [[SecretSubmission]] signals on `Sigil.publish`'s hot path, writes
 * the plaintext value to [[SecretStore]] (encrypted or hashed
 * depending on `SecretKind`), and replaces the in-flight signal with
 * a `Message`-from-the-submitter whose `content` is a
 * [[sigil.tool.model.ResponseContent.SecretRef]] referencing the
 * stored id.
 *
 * The replacement Message:
 *   - Triggers the agent's self-loop (Message-from-non-self rule in
 *     `TriggerFilter`) so the agent can read the SecretRef and react.
 *   - Persists like any normal Message — its content carries no
 *     plaintext, only the id.
 *   - Renders to the wire as a normal user-role message containing
 *     the SecretRef block, which UIs render as `••••••••` + Copy
 *     button (the Copy button calls back into the app's
 *     `secretStore.get(...)` REST endpoint over TLS).
 *
 * Other signals pass through unchanged. The transform is fast — one
 * pattern match plus a SecretStore write — so it's safe to leave at
 * the head of `inboundTransforms`.
 */
final class SecretCaptureTransform(secretStore: SecretStore) extends InboundTransform {

  override def apply(signal: Signal, self: Sigil): Task[Signal] = signal match {
    case sub: SecretSubmission =>
      val id = Id[SecretRecord](sub.secretId)
      val storeWrite: Task[Unit] = sub.kind match {
        case SecretKind.Encrypted => secretStore.setEncrypted[String](id, sub.value)
        case SecretKind.Hashed    => secretStore.setHashed[String](id, sub.value)
      }
      storeWrite.map { _ =>
        Message(
          participantId = sub.participantId,
          conversationId = sub.conversationId,
          topicId = sub.topicId,
          content = Vector(ResponseContent.SecretRef(secretId = sub.secretId, label = sub.label)),
          state = EventState.Complete,
          timestamp = sub.timestamp
        )
      }
    case other => Task.pure(other)
  }
}
