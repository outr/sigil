package sigil.browser

import fabric.io.{JsonFormatter, JsonParser}
import fabric.rw.*
import fabric.{Json, arr, num, obj, str}
import lightdb.id.Id
import rapid.Task
import robobrowser.{BrowserConfig, RoboBrowser, RoboBrowserConfig}
import sigil.{Sigil, SpaceId}
import sigil.conversation.Conversation
import sigil.db.SigilDB
import sigil.event.Event
import sigil.participant.ParticipantId
import sigil.secrets.{SecretRecord, SecretsCollections, SecretsSigil}
import sigil.signal.{Delta, EventState, StateDelta}

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Sigil refinement that adds browser support. Apps mix this in
 * alongside any other module-Sigil refinements they need:
 *
 * {{{
 *   class MyAppSigil extends BrowserSigil with SecretsSigil { ... }
 * }}}
 *
 * Provides a per-conversation [[BrowserController]] that lazy-
 * allocates a real headless Chrome on first tool use and auto-
 * disposes after `browserIdleTimeoutMs` of inactivity (or on
 * conversation delete). Mixes the framework's
 * [[BrowserState]] / [[BrowserStateDelta]] subtypes into the
 * polymorphic Signal discriminator so they round-trip through
 * persistence + replay.
 *
 * Apps register [[WebBrowserMode]] via `Sigil.modes` to make it
 * switchable, then add the primitive browser tools to `staticTools`
 * (or expose them via a custom finder). See `sigil-browser`'s README
 * for the minimal app shape.
 */
trait BrowserSigil extends Sigil with SecretsSigil {
  type DB <: SigilDB & BrowserCollections & SecretsCollections

  /** Default browser config — apps override for non-headless runs,
    * different window sizes, custom proxies, etc. */
  def browserConfig: RoboBrowserConfig = RoboBrowserConfig(
    browserConfig = BrowserConfig(headless = true, disableGPU = true),
    enableNetworkEvents = false,
    enableDOMEvents = false
  )

  /** How long to keep an idle browser open before disposing. Default
    * 5 minutes — Chrome processes are heavy. The internal reaper
    * fiber checks every 30 seconds. */
  def browserIdleTimeoutMs: Long = 5.minutes.toMillis

  /** Resolve the per-conversation [[BrowserController]]. Lazy-
    * allocated on first call; the same instance is returned for
    * subsequent calls in the same conversation. Dispose happens
    * automatically on conversation delete or after
    * [[browserIdleTimeoutMs]] of inactivity.
    *
    * On first allocation an initial [[BrowserState]] event is
    * published in the `Active` state so subsequent
    * [[BrowserStateDelta]]s have a target to mutate. On dispose a
    * `StateDelta` settles the state to `Complete`.
    *
    * Concurrent callers see the same controller — the underlying
    * map is a `ConcurrentHashMap.compute`. Browser construction
    * itself is atomic per conversation.
    *
    * @param participantId who the initial `BrowserState` event is
    *                      attributed to — typically the agent
    *                      driving the browser. Read from the
    *                      `TurnContext.participantId` at the call
    *                      site of a browser tool. */
  final def browserController(convId: Id[Conversation],
                              participantId: ParticipantId,
                              chain: List[ParticipantId]): Task[BrowserController] = Task.defer {
    Option(BrowserSigil.controllers.get(convId.value)) match {
      case Some(existing) if !existing.isDisposed => Task.pure(existing)
      case _ =>
        for {
          browser   <- RoboBrowser(browserConfig)
          // Resolve the cookie jar BEFORE setting cookies — apps with
          // per-user persistent jars get logged-in state restored
          // before the first navigate.
          jarIdOpt  <- cookieJarFor(chain, convId)
          _         <- jarIdOpt match {
                         case None        => Task.unit
                         case Some(jarId) => loadCookies(jarId).flatMap { cookies =>
                           if (cookies.isEmpty) Task.unit
                           else BrowserSigil.injectCookies(browser, cookies)
                         }
                       }
          fresh      = new BrowserController(
                         conversationId = convId,
                         browser = browser,
                         stateId = BrowserState.id(),
                         cookieJarId = jarIdOpt
                       )
          winner     = BrowserSigil.controllers.compute(convId.value, (_, prior) =>
                         if (prior != null && !prior.isDisposed) prior else fresh
                       )
          result    <- if (winner eq fresh) {
                         // First-allocation: publish the initial BrowserState Event so
                         // subsequent deltas have a target to mutate. Topic is read
                         // from the live conversation.
                         withDB(_.conversations.transaction(_.get(convId))).flatMap {
                           case None => Task.error(new RuntimeException(
                             s"BrowserSigil.browserController: conversation $convId not found"))
                           case Some(conv) =>
                             val initial = BrowserState(
                               participantId = participantId,
                               conversationId = convId,
                               topicId = conv.currentTopicId,
                               _id = fresh.stateId,
                               state = EventState.Active
                             )
                             publish(initial).map(_ => fresh)
                         }
                       } else fresh.dispose.map(_ => winner)
        } yield result
    }
  }

  /** Eagerly dispose the browser controller for a conversation, if
    * any. Before disposing:
    *   - if the controller has a bound `cookieJarId`, the live
    *     browser's cookies are extracted and persisted back to that
    *     jar so the next session resumes the latest state;
    *   - the controller's [[BrowserState]] is settled to `Complete`
    *     via a [[StateDelta]] so subscribers see the session end.
    *
    * Idempotent. Per-step failures are logged and swallowed —
    * partial dispose is better than a stuck controller. */
  final def disposeBrowserController(convId: Id[Conversation]): Task[Unit] = {
    val removed = BrowserSigil.controllers.remove(convId.value)
    if (removed == null) Task.unit
    else for {
      _ <- removed.cookieJarId match {
             case None => Task.unit
             case Some(jarId) =>
               BrowserSigil.extractCookies(removed.browser).flatMap { cookies =>
                 if (cookies.isEmpty) Task.unit
                 else withDB(_.cookieJars.transaction(_.get(jarId))).flatMap {
                   case None => Task.unit
                   case Some(jar) => saveCookies(jarId, jar.space, cookies, jar.metadata).unit
                 }
               }.handleError(t => Task {
                 scribe.warn(s"BrowserSigil.dispose: cookie save failed for $jarId: ${t.getMessage}", t)
               })
           }
      _ <- publish(StateDelta(
             target = removed.stateId,
             conversationId = convId,
             state = EventState.Complete
           )).handleError(_ => Task.unit)
      _ <- removed.dispose
    } yield ()
  }

  // -- cookie persistence --

  /** Resolve which [[CookieJar]] this conversation should use.
    * Default: `None` — every conversation runs with no persisted
    * cookies. Apps override for per-user / per-project / per-tenant
    * jars:
    *
    * {{{
    *   override def cookieJarFor(chain: List[ParticipantId],
    *                             convId: Id[Conversation]): Task[Option[Id[CookieJar]]] =
    *     loadOrCreateUserJar(chain.head)
    * }}}
    *
    * Returning `Some(id)` makes the controller load the jar's
    * cookies before the first navigate and persist any new cookies
    * back when the controller disposes. */
  def cookieJarFor(chain: List[ParticipantId],
                   convId: Id[Conversation]): Task[Option[Id[CookieJar]]] =
    Task.pure(None)

  /** Read + decrypt a [[CookieJar]] into the typed cookie list.
    * Cookies are encrypted via `secretStore` keyed by the jar's id —
    * decryption + JSON parsing happens here so callers see typed
    * values. Returns `Nil` if the jar doesn't exist or the secret
    * record is missing. */
  final def loadCookies(jarId: Id[CookieJar]): Task[List[BrowserCookie]] =
    withDB(_.cookieJars.transaction(_.get(jarId))).flatMap {
      case None => Task.pure(Nil)
      case Some(_) =>
        val secretId: Id[SecretRecord] = Id(jarId.value)
        secretStore.get[String](secretId).map {
          case None       => Nil
          case Some(json) => JsonParser(json).as[List[BrowserCookie]]
        }
    }

  /** Encrypt + persist a list of cookies into a [[CookieJar]]. Creates
    * the record if it doesn't exist; updates `modified` and re-
    * encrypts otherwise. */
  final def saveCookies(jarId: Id[CookieJar],
                        space: SpaceId,
                        cookies: List[BrowserCookie],
                        metadata: Map[String, String] = Map.empty): Task[CookieJar] = {
    val json = JsonFormatter.Compact(cookies.json)
    val secretId: Id[SecretRecord] = Id(jarId.value)
    for {
      _      <- secretStore.setEncrypted(secretId, json)
      record  = CookieJar(space = space, _id = jarId, metadata = metadata,
                          modified = lightdb.time.Timestamp())
      stored <- withDB(_.cookieJars.transaction(_.upsert(record)))
    } yield stored
  }

  /** Resolve which [[SpaceId]] a newly-created [[BrowserScript]]
    * lands in. Default returns [[sigil.GlobalSpace]] — apps that
    * want per-user / per-tenant scripts override:
    *
    * {{{
    *   override def browserScriptSpace(chain: List[ParticipantId],
    *                                   requested: Option[String]): Task[SpaceId] =
    *     Task.pure(MyUserSpace(chain.head.value))
    * }}}
    *
    * The agent supplies an optional `requested` string at create
    * time; apps decide whether to honor, validate, or ignore. */
  def browserScriptSpace(chain: List[ParticipantId],
                         requested: Option[String]): Task[SpaceId] =
    Task.pure(sigil.GlobalSpace)

  /** Auto-register sigil-browser's Event / Delta subtypes so they
    * round-trip through fabric's polymorphic discriminator. Apps
    * mixing in `BrowserSigil` get this for free; subclasses adding
    * their own custom subtypes concatenate. */
  override protected def eventRegistrations: List[RW[? <: Event]] =
    super.eventRegistrations ++ List(summon[RW[BrowserState]])

  override protected def deltaRegistrations: List[RW[? <: Delta]] =
    super.deltaRegistrations ++ List(summon[RW[BrowserStateDelta]])

  /** Auto-register [[BrowserScript]]'s RW so persisted records
    * round-trip without apps having to remember it. */
  override def toolRegistrations: List[RW[? <: sigil.tool.Tool]] =
    summon[RW[BrowserScript]] :: super.toolRegistrations

  /** Tear down every live [[BrowserController]] on `Sigil.shutdown`.
    * Each controller's Chrome subprocess is closed and any bound
    * cookie jar is persisted before disposal. Chains through
    * `super.onShutdown` so apps that mix multiple modules tear each
    * down in declaration order. */
  override protected def onShutdown: rapid.Task[Unit] =
    BrowserSigil.disposeAll.flatMap(_ => super.onShutdown)
}

object BrowserSigil {
  /** Per-(JVM, conversation) registry of live controllers. Keyed by
    * conversation-id string so the map can outlive a `BrowserSigil`
    * instance restart inside the same JVM (codegen → live → codegen
    * cycles). */
  private[browser] val controllers: ConcurrentHashMap[String, BrowserController] =
    new ConcurrentHashMap[String, BrowserController]()

  /** Dispose every live controller. Called by the framework on
    * `Sigil.shutdown`. Idempotent — disposed controllers stay
    * disposed. */
  def disposeAll: Task[Unit] = Task.defer {
    val all = controllers.values().asScala.toList
    controllers.clear()
    Task.sequence(all.map(_.dispose.handleError(_ => Task.unit))).unit
  }

  /** Inject a list of cookies into a live browser via CDP's
    * `Network.setCookies`. */
  private[browser] def injectCookies(browser: RoboBrowser,
                                     cookies: List[BrowserCookie]): Task[Unit] = {
    val params = obj(
      "cookies" -> arr(cookies.map(cookieToJson)*)
    )
    browser.send("Network.setCookies", params).unit
  }

  /** Extract every cookie from the live browser via CDP's
    * `Network.getAllCookies`. */
  private[browser] def extractCookies(browser: RoboBrowser): Task[List[BrowserCookie]] =
    browser.send("Network.getAllCookies").map { response =>
      response.result.get("cookies") match {
        case Some(c) => c.asVector.toList.flatMap(cookieFromJson)
        case None    => Nil
      }
    }

  private def cookieToJson(c: BrowserCookie): Json = {
    val base = List(
      "name"     -> str(c.name),
      "value"    -> str(c.value),
      "domain"   -> str(c.domain),
      "path"     -> str(c.path),
      "secure"   -> fabric.bool(c.secure),
      "httpOnly" -> fabric.bool(c.httpOnly)
    )
    val withSameSite = c.sameSite.fold(base)(s => base :+ ("sameSite" -> str(s)))
    val withExpires  = c.expiresEpochMs.fold(withSameSite)(ms =>
      withSameSite :+ ("expires" -> num(BigDecimal(ms) / 1000)))
    obj(withExpires*)
  }

  private def cookieFromJson(json: Json): Option[BrowserCookie] =
    try {
      val o = json.asObj
      Some(BrowserCookie(
        domain         = o("domain").asString,
        name           = o("name").asString,
        value          = o("value").asString,
        path           = o.get("path").map(_.asString).getOrElse("/"),
        secure         = o.get("secure").exists(_.asBoolean),
        httpOnly       = o.get("httpOnly").exists(_.asBoolean),
        sameSite       = o.get("sameSite").map(_.asString),
        expiresEpochMs = o.get("expires").flatMap(j => try Some((j.asBigDecimal * 1000).toLong) catch { case _: Throwable => None })
      ))
    } catch { case _: Throwable => None }
}
