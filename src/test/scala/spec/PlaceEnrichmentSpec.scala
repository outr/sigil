package spec

import fabric.rw.*
import lightdb.id.Id
import lightdb.spatial.{Geo, Point, Polygon}
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.Conversation
import sigil.event.{Event, Message}
import sigil.signal.{EventState, LocationDelta, Signal}
import sigil.spatial.{CachingGeocoder, GeocodingCache, GeocodingResult, InMemoryGeocoder, NoOpGeocoder, Place}

import scala.concurrent.duration.*

/**
 * Covers the [[sigil.Sigil]] geospatial pipeline end-to-end:
 *   - `locationFor` capture runs in `publish` for non-agent Messages
 *   - Agent-authored Messages never trigger capture
 *   - An explicit client-attached `Place` is never overwritten
 *   - Raw-GPS-only mode (default `NoOpGeocoder`) does not publish any
 *     [[LocationDelta]]
 *   - Non-NoOp [[sigil.spatial.Geocoder]] resolves to a `LocationDelta`
 *     that updates the persisted Message
 *   - [[CachingGeocoder]] spatial-containment cache hits skip the
 *     delegate
 *   - TTL expiry on read invalidates stale entries
 *   - `redactLocation` hides the location from non-sender viewers
 *   - `LocationDelta` round-trips through the polymorphic `Signal` RW
 */
class PlaceEnrichmentSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  TestSigil.initFor(getClass.getSimpleName)

  private val mint = Point(latitude = 37.7858, longitude = -122.4064)
  private val enrichedPlace = Place(point = mint, address = Some("66 Mint St, SF"), name = Some("Blue Bottle"))
  // Small polygon around the Mint St point (few blocks). Any GPS inside hits
  // the cached boundary.
  private val mintBoundary: Geo = Polygon.lonLat(
    -122.4070, 37.7850,
    -122.4050, 37.7850,
    -122.4050, 37.7870,
    -122.4070, 37.7870,
    -122.4070, 37.7850
  )
  private val nearbyInside = Point(latitude = 37.7860, longitude = -122.4055)

  private def bareMessage(participantId: sigil.participant.ParticipantId = TestUser,
                          convId: Id[Conversation] = Conversation.id(),
                          location: Option[Place] = None): Message =
    Message(
      participantId = participantId,
      conversationId = convId,
      topicId = TestTopicId,
      state = EventState.Complete,
      location = location
    )

  /** Poll the events store until the Message at `msgId` has a Place
    * satisfying `pred`, or the timeout elapses. */
  private def awaitMessageLocation(msgId: Id[Event],
                                   pred: Place => Boolean,
                                   timeoutMs: Long = 5000): Task[Message] = {
    val deadline = System.currentTimeMillis() + timeoutMs
    def loop: Task[Message] = TestSigil.withDB(_.events.transaction(_.get(msgId))).flatMap {
      case Some(m: Message) if m.location.exists(pred) => Task.pure(m)
      case _ if System.currentTimeMillis() > deadline =>
        Task.error(new RuntimeException(s"awaitMessageLocation timed out for $msgId after ${timeoutMs}ms"))
      case _ => Task.sleep(25.millis).flatMap(_ => loop)
    }
    loop
  }

  "Sigil publish with geospatial hooks" should {
    "capture location for a non-agent Message when locationFor returns Some" in {
      TestSigil.reset()
      TestSigil.setLocationFor((_, _) => Task.pure(Some(Place(point = mint))))
      val msg = bareMessage()
      for {
        _ <- TestSigil.publish(msg)
        loaded <- TestSigil.withDB(_.events.transaction(_.get(msg._id)))
      } yield {
        loaded.collect { case m: Message => m.location } shouldBe Some(Some(Place(point = mint)))
      }
    }

    "never capture location for an agent-authored Message" in {
      TestSigil.reset()
      TestSigil.setLocationFor((_, _) => Task.pure(Some(Place(point = mint))))
      val msg = bareMessage(participantId = TestAgent)
      for {
        _ <- TestSigil.publish(msg)
        loaded <- TestSigil.withDB(_.events.transaction(_.get(msg._id)))
      } yield {
        loaded.collect { case m: Message => m.location } shouldBe Some(None)
      }
    }

    "leave an explicit client-provided Place untouched" in {
      TestSigil.reset()
      TestSigil.setLocationFor((_, _) => Task.pure(Some(Place(point = mint))))
      val explicit = Place(point = mint, address = Some("client-side"), name = Some("client"))
      val msg = bareMessage(location = Some(explicit))
      for {
        _ <- TestSigil.publish(msg)
        loaded <- TestSigil.withDB(_.events.transaction(_.get(msg._id)))
      } yield {
        loaded.collect { case m: Message => m.location } shouldBe Some(Some(explicit))
      }
    }

    "not emit a LocationDelta when geocoder is NoOpGeocoder (raw-GPS-only mode)" in {
      TestSigil.reset()
      val recorder = new RecordingBroadcaster
      TestSigil.setBroadcaster(recorder)
      TestSigil.setLocationFor((_, _) => Task.pure(Some(Place(point = mint))))
      // geocoder stays at default NoOpGeocoder
      val msg = bareMessage()
      for {
        _ <- TestSigil.publish(msg)
        _ <- Task.sleep(100.millis)
        loaded <- TestSigil.withDB(_.events.transaction(_.get(msg._id)))
      } yield {
        loaded.collect { case m: Message => m.location } shouldBe Some(Some(Place(point = mint)))
        recorder.recorded.collect { case d: LocationDelta => d } shouldBe empty
      }
    }

    "enrich a bare Place asynchronously when a non-NoOp geocoder is wired" in {
      TestSigil.reset()
      val geocoder = InMemoryGeocoder.single(mint, GeocodingResult(enrichedPlace, mintBoundary))
      TestSigil.setGeocoder(geocoder)
      val msg = bareMessage(location = Some(Place(point = mint)))
      for {
        _ <- TestSigil.publish(msg)
        enriched <- awaitMessageLocation(msg._id, _.name.contains("Blue Bottle"))
      } yield {
        enriched.location.map(_.address) shouldBe Some(Some("66 Mint St, SF"))
        enriched.location.map(_.name) shouldBe Some(Some("Blue Bottle"))
        enriched.location.map(_.point) shouldBe Some(mint)
        geocoder.invocationCount should be >= 1
      }
    }
  }

  "CachingGeocoder" should {
    "hit the delegate on first call and miss on a second call inside the same boundary" in {
      TestSigil.reset()
      // Clear any leftover cache rows from prior tests.
      val clearCache = TestSigil.withDB(_.geocodingCache.transaction { tx =>
        tx.list.flatMap(rows => Task.sequence(rows.map(r => tx.delete(r._id))).unit)
      })
      val delegate = InMemoryGeocoder.single(mint, GeocodingResult(enrichedPlace, mintBoundary))
      val caching = CachingGeocoder(delegate, TestSigil)
      for {
        _ <- clearCache
        first <- caching.geocode(mint)
        firstCount = delegate.invocationCount
        second <- caching.geocode(nearbyInside)
        secondCount = delegate.invocationCount
      } yield {
        first.map(_.place.name) shouldBe Some(Some("Blue Bottle"))
        second.map(_.place.name) shouldBe Some(Some("Blue Bottle"))
        firstCount shouldBe 1
        secondCount shouldBe 1  // boundary-contained — delegate untouched
      }
    }

    "bypass a stale cache entry and re-call the delegate" in {
      TestSigil.reset()
      // Seed an entry with a resolvedAt from 40 days ago.
      val longAgo = Timestamp(System.currentTimeMillis() - 40L * 24 * 60 * 60 * 1000)
      val staleRow = GeocodingCache(boundary = mintBoundary, place = enrichedPlace, resolvedAt = longAgo)

      val delegate = InMemoryGeocoder.single(mint, GeocodingResult(enrichedPlace, mintBoundary))
      val caching = CachingGeocoder(delegate, TestSigil, ttl = Some(30.days))
      for {
        _ <- TestSigil.withDB(_.geocodingCache.transaction { tx =>
          tx.list.flatMap(rows => Task.sequence(rows.map(r => tx.delete(r._id))).unit)
        })
        _ <- TestSigil.withDB(_.geocodingCache.transaction(_.insert(staleRow))).unit
        before = delegate.invocationCount
        result <- caching.geocode(mint)
        after = delegate.invocationCount
      } yield {
        result.map(_.place.name) shouldBe Some(Some("Blue Bottle"))
        (after - before) shouldBe 1  // stale row ignored → delegate called
      }
    }
  }

  "redactLocation" should {
    "leave the Message untouched when the viewer is the sender" in Task {
      val msg = bareMessage(location = Some(enrichedPlace))
      TestSigil.redactLocation(msg, TestUser) shouldBe msg
    }

    "strip location for any other viewer" in Task {
      val msg = bareMessage(location = Some(enrichedPlace))
      val redacted = TestSigil.redactLocation(msg, TestAgent)
      redacted.location shouldBe None
      redacted.copy(location = Some(enrichedPlace)) shouldBe msg
    }
  }

  "LocationDelta" should {
    "round-trip through the polymorphic Signal RW" in Task {
      val delta: Signal = LocationDelta(
        target = Id[Event]("some-message"),
        conversationId = Conversation.id("some-conversation"),
        location = enrichedPlace
      )
      val json = summon[RW[Signal]].read(delta)
      val back = summon[RW[Signal]].write(json)
      back shouldBe delta
    }
  }
}
