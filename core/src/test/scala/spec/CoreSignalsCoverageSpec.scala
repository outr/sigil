package spec

import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.signal.{CoreSignals, Notice, ParticipantProjectionUpdated, Signal}

import java.net.URI
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/**
 * Regression for sigil #297 — five Notice subtypes added across
 * #291 / #292 / #293 (`ParticipantProjectionUpdated`,
 * `RequestMemoryList`, `MemoryListSnapshot`, `RequestModelCatalog`,
 * `ModelCatalogSnapshot`) shipped without being registered in
 * [[CoreSignals.notices]]. Without the registration, the Signal poly
 * RW doesn't recognize them at write time — wire serialization fails,
 * and subsequent frames in the same broadcast batch never reach the
 * client.
 *
 * This spec walks the classpath under `sigil.signal.*` (the framework's
 * own Notice package), finds every concrete class that extends
 * [[Notice]] AND `derives RW`, and asserts each is registered in
 * [[CoreSignals.notices]]. Catches future drift before it bites
 * downstream consumers.
 */
class CoreSignalsCoverageSpec extends AnyWordSpec with Matchers {

  /** Discover Notice subtypes by walking the compiled .class files
    * for `sigil.signal.*`. Classpath scanning rather than reflection-
    * via-package because Java reflection has no built-in "list
    * classes in package" — `getResources("sigil/signal")` is the
    * only portable handle. */
  private def discoveredNoticeClassNames: Set[String] = {
    val loader = getClass.getClassLoader
    val resources = loader.getResources("sigil/signal").asScala.toList
    val files = resources.flatMap { url =>
      val uri: URI = url.toURI
      uri.getScheme match {
        case "file" =>
          val dir = Paths.get(uri)
          if (Files.isDirectory(dir)) {
            val it = Files.list(dir)
            try it.iterator().asScala.toList finally it.close()
          } else Nil
        case _ => Nil // jar entries — skip in dev test runs (.class files come from `file:` in sbt forks)
      }
    }
    val classNames = files.iterator
      .filter(p => p.toString.endsWith(".class"))
      .map(_.getFileName.toString)
      .filterNot(_.contains("$"))                    // skip inner / synthetic / companion
      .map(_.stripSuffix(".class"))
      .toList
    classNames.flatMap { simpleName =>
      val fqn = s"sigil.signal.$simpleName"
      scala.util.Try(loader.loadClass(fqn)).toOption.filter { cls =>
        classOf[Notice].isAssignableFrom(cls) &&
          cls != classOf[Notice] &&
          !cls.isInterface &&
          !java.lang.reflect.Modifier.isAbstract(cls.getModifiers)
      }.map(_ => simpleName)
    }.toSet
  }

  /** Registered class names in `CoreSignals.notices` — extracted via
    * each RW's `Definition.className`. Returns the simple-name (final
    * segment) for cross-reference against the classpath-derived set. */
  private def registeredNoticeClassNames: Set[String] = {
    CoreSignals.notices.iterator
      .flatMap(_.definition.className)
      .map(_.split('.').last.split('$').last)
      .toSet
  }

  "CoreSignals.notices (sigil #297)" should {

    "register every Notice subtype the framework ships" in {
      val discovered = discoveredNoticeClassNames
      val registered = registeredNoticeClassNames
      val missing = discovered.diff(registered)
      withClue(
        s"\nNotice subtypes found in sigil.signal.* via classpath scan that are NOT in\n" +
          s"CoreSignals.notices (${missing.size}):\n" +
          missing.toList.sorted.map(m => s"  - $m").mkString("\n") +
          s"\n\nRegistered (${registered.size}): ${registered.toList.sorted.mkString(", ")}\n" +
          s"\nDiscovered (${discovered.size}): ${discovered.toList.sorted.mkString(", ")}\n"
      ) {
        missing shouldBe empty
      }
    }

    "round-trip ParticipantProjectionUpdated through the Signal poly RW" in {
      // Trigger the framework's polymorphic registration before
      // summoning the Signal RW so all subtypes (including the
      // newly-registered ones from #297) are in the poly.
      TestSigil.polymorphicRegistrations.sync()

      val notice = ParticipantProjectionUpdated(
        conversationId = lightdb.id.Id("conv-297"),
        participantId  = TestUser,
        projection     = sigil.conversation.ParticipantProjection.empty(TestUser, lightdb.id.Id("conv-297"))
      )
      val signalRW = summon[RW[Signal]]
      val json = signalRW.read(notice)
      val decoded = json.as[Signal](using signalRW)
      decoded shouldBe notice
    }
  }
}
