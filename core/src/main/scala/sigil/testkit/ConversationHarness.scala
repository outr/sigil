package sigil.testkit

import fabric.rw.*
import lightdb.id.Id
import rapid.Task
import sigil.Sigil
import sigil.conversation.Conversation
import sigil.event.Event
import sigil.participant.{AgentParticipant, ParticipantId}
import sigil.transport.{DurableSocketSink, SigilDbEventLog}
import spice.http.client.HttpClient
import spice.http.durable.{DurableSocketClient, DurableSocketConfig, DurableSocketServer, ReconnectStrategy}
import spice.http.server.MutableHttpServer
import spice.http.server.config.HttpServerListener
import spice.http.server.dsl.*
import spice.http.server.dsl.given
import spice.net.*

import scala.concurrent.duration.*
import scala.language.implicitConversions

/**
 * Reusable end-to-end conversation test harness.
 *
 * Stands up a real HTTP server with a `DurableSocketServer` mounted at
 * `/ws`, bridges every accepted session into the supplied [[Sigil]]'s
 * per-viewer signal stream via [[DurableSocketSink]], and exposes a
 * [[withClient]] resource that opens a fresh [[Conversation]],
 * connects a wire client, and provides a [[ConversationSession]] with
 * a `send` helper that drives a full agent turn end-to-end.
 *
 * Designed for downstream apps building on Sigil. Construct one
 * harness per spec (typically in `beforeAll`); call [[start]] /
 * [[stop]] for lifecycle and [[withClient]] per test.
 *
 * The harness is deliberately decoupled from the concrete `Sigil` —
 * apps pass their own initialized instance, their own viewer
 * `ParticipantId`, and a factory that produces a fresh `Conversation`
 * (with the right participants, topics, and any app-specific config)
 * per `withClient` invocation.
 *
 * Apps using this harness must supply a spice runtime impl on the
 * test classpath — typically `"com.outr" %% "spice-server-undertow" %
 * spiceVersion % Test`. The runtime registers via classpath
 * service-loader; no explicit wiring is required.
 *
 * Example:
 * {{{
 * val harness = ConversationHarness(MySigil, MyUser, convId =>
 *   Conversation(_id = convId,
 *                topics = List(MyTopic),
 *                participants = List(MyAgent)))
 *
 * harness.withClient("greeting") { s =>
 *   for {
 *     reply <- s.send("Hello.")
 *   } yield ConversationSession.textOf(reply) should not be empty
 * }
 * }}}
 */
final class ConversationHarness(sigil: Sigil,
                                viewer: ParticipantId,
                                conversationFactory: Id[Conversation] => Conversation) {

  /** Adapter exposing `SigilDB.events` as a spice `EventLog`. The
    * server uses this for resume reads, so missed events stream from
    * the same store `Sigil.publish` writes to — no separate buffer. */
  lazy val eventLog: SigilDbEventLog = new SigilDbEventLog(sigil)

  /** The DurableSocket server. Public so apps can poke at sessions /
    * channels for advanced scenarios (custom resume flows, etc.). */
  lazy val durableServer: DurableSocketServer[Id[Conversation], Event, String] =
    new DurableSocketServer[Id[Conversation], Event, String](
      config = DurableSocketConfig(ackBatchDelay = 50.millis, reconnectStrategy = ReconnectStrategy.none),
      eventLog = eventLog,
      // `info` carries the conversationId string supplied at connect time.
      resolveChannel = (_, info) => Task.pure(Conversation.id(info))
    )

  /** The HTTP server. Bound to an ephemeral port; read [[serverPort]]
    * after `start()` to learn which one. */
  lazy val httpServer: MutableHttpServer = {
    val s = new MutableHttpServer
    s.config.clearListeners().addListeners(HttpServerListener(port = None))
    s.handler(List(path"/ws" / durableServer))
    s.start().sync()
    // Bridge each new session into the viewer-scoped signal stream.
    // `sync()` materializes the SignalHub subscription before this
    // callback returns so the live stream is hot before any publish.
    durableServer.onSession.attach { session =>
      sigil.signalTransport.attach(
        viewer = viewer,
        sink = new DurableSocketSink[Id[Conversation], String](session),
        conversations = Some(Set(session.channelId))
      ).sync()
      ()
    }
    s
  }

  def serverPort: Int = httpServer.config.listeners().head.port.getOrElse(0)

  /** Boot the HTTP server (idempotent — repeat calls are no-ops). Call
    * once per spec, e.g. in `beforeAll`. */
  def start(): Task[Unit] = Task { httpServer; () }

  /** Stop the HTTP server. Failures are swallowed (best-effort
    * cleanup). Call once per spec, e.g. in `afterAll`. */
  def stop(): Task[Unit] = Task {
    try httpServer.stop().sync() catch { case _: Throwable => () }
  }

  /** Open a [[ConversationSession]], run `f`, and guarantee the wire
    * client closes whether `f` succeeds or fails. Use in place of
    * paired open/close calls. */
  def withClient[A](suffix: String)(f: ConversationSession => Task[A]): Task[A] =
    newSession(suffix).flatMap { s =>
      f(s).attempt.flatMap { result =>
        Task { s.client.close() }.attempt.flatMap(_ => result match {
          case scala.util.Success(a) => Task.pure(a)
          case scala.util.Failure(t) => Task.error(t)
        })
      }
    }

  /** Open a fresh session without resource management. Prefer
    * [[withClient]] unless you specifically want to drive the client
    * lifecycle yourself (e.g. testing reconnect / replay flows that
    * need to close-then-reopen the wire). */
  def newSession(suffix: String): Task[ConversationSession] = {
    val convId = Conversation.id(s"conv-$suffix-${rapid.Unique()}")
    val convo = conversationFactory(convId)
    val agentIds: Set[ParticipantId] =
      convo.participants.collect { case a: AgentParticipant => a.id }.toSet
    val topicId = convo.topics.headOption.map(_.id).getOrElse(
      throw new IllegalStateException(
        s"ConversationHarness: factory returned a Conversation with no topics; need at least one for `send` to publish into"
      )
    )
    val clientId = s"client-${rapid.Unique()}"
    for {
      _ <- sigil.withDB(_.conversations.transaction(_.upsert(convo)))
      received = new ConversationSession.Received
      client = new DurableSocketClient[Id[Conversation], Event, String](
        createWebSocket = () => HttpClient.url(url"ws://localhost".withPort(serverPort).withPath(path"/ws")).webSocket(),
        config = DurableSocketConfig(ackBatchDelay = 50.millis, reconnectStrategy = ReconnectStrategy.none),
        outboundLog = eventLog,
        initialChannelId = convId,
        info = convId.value,
        clientId = clientId
      )
      _ = client.onEvent.attach { case (_, e) => received.add(e) }
      _ <- client.connect()
      _ <- awaitSessionReady(clientId)
      // Settle a moment for the server's SignalTransport subscription
      // to materialize (the stream is lazy on first pull).
      _ <- Task.sleep(200.millis)
    } yield new ConversationSession(
      sigil = sigil,
      viewer = viewer,
      agentIds = agentIds,
      topicId = topicId,
      client = client,
      convId = convId,
      received = received
    )
  }

  private def awaitSessionReady(clientId: String, timeout: FiniteDuration = 5.seconds): Task[Unit] = {
    val deadline = System.currentTimeMillis() + timeout.toMillis
    def loop: Task[Unit] =
      if (durableServer.session(clientId).isDefined) Task.unit
      else if (System.currentTimeMillis() < deadline) Task.sleep(50.millis).flatMap(_ => loop)
      else Task.error(new RuntimeException(
        s"DurableSocket session for clientId=$clientId not registered within ${timeout.toMillis}ms"
      ))
    loop
  }
}

object ConversationHarness {
  def apply(sigil: Sigil,
            viewer: ParticipantId,
            conversationFactory: Id[Conversation] => Conversation): ConversationHarness =
    new ConversationHarness(sigil, viewer, conversationFactory)
}
