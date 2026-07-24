package sigil.tool.client

import lightdb.id.Id
import rapid.Task
import sigil.conversation.Conversation
import sigil.event.Event
import sigil.tool.{Tool, ToolName}

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.DurationLong
import scala.jdk.CollectionConverters.*

/**
 * In-memory registry of UI-registered interaction tools, keyed by
 * conversation. Deliberately NOT persisted: a client tool is
 * executable only while the registering client is attached, so its
 * lifetime is the registration's — registered on conversation load,
 * replaced wholesale on re-register (reconnect), dropped on
 * deregister / session detach. A durable record would leave dead
 * entries in the roster after the tab closes.
 *
 * Registrations are session-scoped (`sessionId` is client-generated):
 * one conversation may hold tools from several sessions (two tabs);
 * per tool NAME the latest registration wins. Every attached client
 * sees the [[sigil.event.ToolInvoke]] broadcast — clients act only on
 * names they registered; for round-trip tools the first
 * [[sigil.signal.ClientToolResult]] settles the call and later
 * duplicates are ignored.
 */
final class ClientToolRegistry(sigil: _root_.sigil.Sigil) {

  final private case class Registered(spec: ClientToolSpec, sessionId: String, tool: ClientTool)

  /**
   * conversationId → toolName → registration.
   */
  private val entries: ConcurrentHashMap[Id[Conversation], ConcurrentHashMap[String, Registered]] =
    new ConcurrentHashMap()

  /**
   * Parked round-trip calls awaiting a [[sigil.signal.ClientToolResult]].
   */
  private val pending: ConcurrentHashMap[Id[Event], rapid.task.Completable[ClientToolRegistry.Outcome]] =
    new ConcurrentHashMap()

  /**
   * Register (or replace) `specs` for `sessionId` on the conversation.
   * Returns `(accepted, rejected)` — rejected carries a per-name
   * reason. `replace = true` (the default wire semantics) first drops
   * the session's previous registrations for the conversation, so a
   * reconnecting client's fresh set IS the set.
   */
  def register(conversationId: Id[Conversation],
               sessionId: String,
               specs: List[ClientToolSpec],
               replace: Boolean = true): Task[(List[String], Map[String, String])] = Task.defer {
    val conv = entries.computeIfAbsent(conversationId, _ => new ConcurrentHashMap())
    if (replace) {
      conv.entrySet().removeIf(e => e.getValue.sessionId == sessionId)
    }
    val results = specs.map { raw =>
      validate(raw) match {
        case Some(reason) => Left(raw.name -> reason)
        case None =>
          val spec = sanitize(raw)
          sigil.clientToolFilter(spec) match {
            case None => Left(spec.name -> "rejected by the application's client-tool filter")
            case Some(vetted) => Right(vetted)
          }
      }
    }
    // Server-tool collision check is effectful (finder lookup); run it
    // only for the survivors.
    Task.sequence(results.collect { case Right(spec) => spec }.map { spec =>
      sigil.findTools.byName(ToolName(spec.name)).map {
        case Some(_) => Left(spec.name -> "collides with a server-registered tool of the same name")
        case None => Right(spec)
      }
    }).map { collisionChecked =>
      val rejected = results.collect { case Left(pair) => pair } ++ collisionChecked.collect { case Left(pair) => pair }
      val accepted = collisionChecked.collect { case Right(spec) => spec }
      val roomFor = math.max(0, sigil.clientToolLimit - conv.size())
      val (admitted, overflow) = accepted.splitAt(roomFor)
      admitted.foreach { spec =>
        conv.put(spec.name, Registered(spec, sessionId, new ClientTool(spec, conversationId, this)))
      }
      val allRejected =
        (rejected ++ overflow.map(s =>
          s.name -> s"registration limit reached (${sigil.clientToolLimit} client tools per conversation)")).toMap
      (admitted.map(_.name), allRejected)
    }
  }

  /**
   * Drop `sessionId`'s registrations on the conversation — `names`
   * narrows to a subset; `None` drops the session's whole set.
   */
  def deregister(conversationId: Id[Conversation], sessionId: String, names: Option[Set[String]] = None): Task[Unit] = Task {
    Option(entries.get(conversationId)).foreach { conv =>
      conv.entrySet().removeIf { e =>
        e.getValue.sessionId == sessionId && names.forall(_.contains(e.getKey))
      }
      if (conv.isEmpty) entries.remove(conversationId, conv)
    }
    ()
  }

  /**
   * Drop every registration `sessionId` made, across all
   * conversations. Transports call this on client detach.
   */
  def deregisterSession(sessionId: String): Task[Unit] = Task {
    entries.forEach { (convId, conv) =>
      conv.entrySet().removeIf(_.getValue.sessionId == sessionId)
      if (conv.isEmpty) entries.remove(convId, conv)
    }
    ()
  }

  def isLive(conversationId: Id[Conversation], name: String): Boolean =
    Option(entries.get(conversationId)).exists(_.containsKey(name))

  /**
   * Conversation-scoped exact-name lookup — the roster-resolution
   * seam. Client tools shadow nothing: registration rejects names
   * that collide with server tools.
   */
  def byName(conversationId: Id[Conversation], name: ToolName): Option[Tool] =
    Option(entries.get(conversationId)).flatMap(c => Option(c.get(name.value))).map(_.tool)

  /**
   * All live client tools for a conversation, for discovery.
   */
  def toolsFor(conversationId: Id[Conversation]): List[Tool] =
    Option(entries.get(conversationId)).map(_.values().asScala.map(_.tool).toList).getOrElse(Nil)

  /**
   * Park a round-trip call until [[completeResult]] answers or
   * `timeoutMs` elapses. The pending slot is cleared on every exit
   * path (answer, timeout, cancellation).
   */
  def awaitResult(invokeId: Id[Event], timeoutMs: Long): Task[ClientToolRegistry.Outcome] = Task.defer {
    val completable = Task.completable[ClientToolRegistry.Outcome]
    pending.put(invokeId, completable)
    Task.race(
      completable: Task[ClientToolRegistry.Outcome],
      Task.sleep(timeoutMs.millis).map(_ => ClientToolRegistry.TimedOut: ClientToolRegistry.Outcome)
    ).map(_.merge).guarantee(Task {
      pending.remove(invokeId)
      ()
    })
  }

  private def validate(spec: ClientToolSpec): Option[String] =
    if (!ClientToolRegistry.NamePattern.matches(spec.name))
      Some("invalid name — use snake_case, starting with a letter, max 64 chars")
    else if (spec.description.trim.isEmpty) Some("description is required")
    else None

  private def sanitize(spec: ClientToolSpec): ClientToolSpec =
    spec.copy(
      description = spec.description.take(sigil.clientToolDescriptionMaxChars),
      keywords = spec.keywords.take(16).map(_.take(32))
    )

  /**
   * Complete a parked call from a [[sigil.signal.ClientToolResult]].
   * Returns `false` when no call is parked under `invokeId` (already
   * answered, timed out, or fire-and-forget) — duplicates are
   * ignored, first answer wins.
   */
  def completeResult(invokeId: Id[Event], content: String, isError: Boolean): Boolean =
    Option(pending.remove(invokeId)) match {
      case Some(completable) =>
        completable.success(ClientToolRegistry.Answer(content, isError))
        true
      case None => false
    }
}

object ClientToolRegistry {
  sealed trait Outcome
  final case class Answer(content: String, isError: Boolean) extends Outcome
  case object TimedOut extends Outcome

  /**
   * Valid client-tool name: snake_case, bounded — the same shape
   * every server tool uses, so prompts and dispatch treat them
   * uniformly.
   */
  private[client] val NamePattern = "^[a-z][a-z0-9_]{0,63}$".r
}
