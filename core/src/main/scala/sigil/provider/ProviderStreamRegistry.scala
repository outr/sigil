package sigil.provider

import lightdb.id.Id
import rapid.{Stream, Task}
import sigil.conversation.Conversation
import sigil.participant.ParticipantId
import spice.http.client.StreamHandle

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import scala.jdk.CollectionConverters.*

/**
 * Tracks in-flight provider HTTP streams so a `Stop` can abort the
 * underlying call mid-stream instead of letting it drain to natural
 * completion (which still bills the generated-then-discarded output
 * tokens).
 *
 * Each registered entry pairs a `(conversationId, agentId)` key with the
 * spice `StreamHandle.cancel` task for the call currently streaming for
 * that agent's turn. The agent loop runs at most one provider call per
 * iteration, so a single in-flight handle per key is the live one;
 * within a key the most recent registration wins (an `AtomicLong`
 * registration token guards against a stale `unregister` from a
 * just-completed call clobbering a freshly-started one).
 *
 * Mirrors the shape of `McpManager.cancelInFlight` — `Stop` dispatch
 * looks up the matching key(s) and runs `cancel`.
 */
final class ProviderStreamRegistry {
  private final case class Entry(token: Long, cancel: Task[Unit])

  private val inFlight: ConcurrentHashMap[ProviderStreamRegistry.Key, Entry] =
    new ConcurrentHashMap()
  private val tokens: AtomicLong = new AtomicLong(0L)

  /**
   * Register the `cancel` handle for a provider call that is about to
   * start streaming for `(conversationId, agentId)`. Returns a token to
   * pass back to [[unregister]] when the stream terminates — the token
   * ensures a stale deregistration from an already-finished call cannot
   * remove a newer in-flight handle that reused the same key.
   */
  def register(conversationId: Id[Conversation],
               agentId: ParticipantId,
               cancel: Task[Unit]): Long = {
    val token = tokens.incrementAndGet()
    inFlight.put(ProviderStreamRegistry.Key(conversationId, agentId), Entry(token, cancel))
    token
  }

  /**
   * Deregister the handle registered under `token`. A no-op when the key
   * has already been replaced by a newer registration (token mismatch)
   * or removed — the token check stops a stale `unregister` from a
   * just-completed call clobbering a freshly-started one on the same
   * key.
   */
  def unregister(conversationId: Id[Conversation], agentId: ParticipantId, token: Long): Unit = {
    val key = ProviderStreamRegistry.Key(conversationId, agentId)
    inFlight.computeIfPresent(key, (_, entry) => if (entry.token == token) null else entry)
    ()
  }

  /**
   * Cancel the in-flight provider stream(s) for `conversationId`. When
   * `agentId` is `Some`, only that agent's stream is aborted; when
   * `None`, every agent streaming in the conversation is aborted (a
   * conversation-wide `Stop`). Returns a task that runs every matching
   * `cancel` handle. Each `cancel` is idempotent and best-effort.
   */
  def cancelFor(conversationId: Id[Conversation],
                agentId: Option[ParticipantId]): Task[Unit] = Task.defer {
    val matches = inFlight.entrySet().iterator().asScala.toList.filter { e =>
      e.getKey.conversationId == conversationId &&
        agentId.forall(_ == e.getKey.agentId)
    }
    matches.foreach(e => inFlight.remove(e.getKey, e.getValue))
    Task.sequence(matches.map(_.getValue.cancel.handleError(_ => Task.unit))).unit
  }

  /** Number of in-flight handles currently registered. Test/diagnostic. */
  def size: Int = inFlight.size()

  /**
   * Wire a provider's [[StreamHandle]] into the registry. When the call
   * carries both a `conversationId` and an `agentId` (a real agent
   * turn), the handle's `cancel` is registered under that key so a
   * `Stop` can abort the in-flight HTTP call; the returned stream
   * deregisters via [[rapid.Stream.guarantee]] on every termination
   * path (clean completion, error, or cancellation).
   *
   * A call without both ids (one-shot consult, embedding, etc.) is not
   * tracked — there is no agent turn for a `Stop` to target — and the
   * handle's `stream` is returned unchanged.
   */
  def track(input: ProviderCall, handle: StreamHandle[String]): Stream[String] =
    (input.conversationId, input.agentId) match {
      case (Some(convId), Some(agentId)) =>
        val token = register(convId, agentId, handle.cancel)
        handle.stream.guarantee(Task(unregister(convId, agentId, token)))
      case _ => handle.stream
    }
}

object ProviderStreamRegistry {
  /** Per-(agent, conversation) key — the granularity Stop dispatch uses. */
  final case class Key(conversationId: Id[Conversation], agentId: ParticipantId)
}
