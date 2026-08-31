package spec

import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.signal.{CoreSignals, Signal}

/**
 * Regression for sigil #294 — the Dart codegen was silently skipping
 * Signal subtypes whose dependent types live in non-`signal/`
 * packages (e.g. `RequestConversationSearch` / `ConversationSearchSnapshot`
 * pull in `sigil.tool.model.SearchConversationHit`). Consumer Tome
 * code referenced the generated Dart classes and the build broke with
 * "type isn't defined" errors.
 *
 * This spec generates the Signal poly types directly and asserts that
 * EVERY entry registered in `CoreSignals` produces a corresponding
 * Dart file in the output. Any future addition that the generator's
 * discovery walk misses will fail this test before it hits a
 * consumer.
 */
class DartGeneratorSignalCoverageSpec extends AnyWordSpec with Matchers {

  // Run the same boot sequence as the real `DartGenerator.run` — phase-1
  // registrations, then summon the wire-type RW so we capture the
  // populated Signal poly.
  private def generate(): List[spice.openapi.generator.SourceFile] = {
    TestSigil.polymorphicRegistrations.sync()
    spice.openapi.generator.dart.DurableSocketDartGenerator(
      spice.openapi.generator.dart.DurableSocketDartConfig(
        serviceName = "Test",
        wireType = "Signal" -> summon[RW[Signal]].definition,
        infoFields = Nil
      )
    ).generate()
  }

  /**
   * Snake-case helper matching the generator's own `snakeCaseFile`.
   */
  private def snake(camel: String): String =
    if (camel.isEmpty) camel
    else {
      val pre = camel.charAt(0).toLower
      val suffix = "\\p{Lu}".r.replaceAllIn(camel.substring(1), m => s"_${m.group(0).toLowerCase}")
      s"$pre$suffix"
    }

  "Dart codegen for the Signal poly (sigil #294)" should {

    "emit a Dart file for every CoreSignals subtype" in {
      val files = generate()
      val emittedDartFiles: Set[String] = files.map(_.fileName).toSet

      val expected: Map[String, String] = CoreSignals.all.flatMap { rw =>
        val defn = rw.asInstanceOf[RW[Signal]].definition
        defn.className.map { cn =>
          val dart = spice.openapi.generator.dart.DartNames.dartClassName(cn)
          dart -> s"${snake(dart)}.dart"
        }
      }.toMap

      val missing = expected.collect {
        case (dartName, fileName) if !emittedDartFiles.contains(fileName) => dartName -> fileName
      }

      withClue(s"Missing Dart files (${missing.size}):\n" +
        missing.toList.sortBy(_._1).map { case (d, f) => s"  - $d → $f" }.mkString("\n") +
        s"\n\nEmitted files (${emittedDartFiles.size}): ${emittedDartFiles.toList.sorted.mkString(", ")}\n") {
        missing shouldBe empty
      }
    }
  }
}
