package spec

import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.event.ToolInvoke
import sigil.signal.EventState

/**
 * Sigil bug #269 — Dart codegen's `fromJson` must tolerate missing keys for
 * fields whose Scala source declares a case-class default. Without this,
 * persisted events written before a field existed crash on replay with a
 * Dart TypeError ("Null is not a subtype of `Map<String, dynamic>`").
 *
 * The 1.1.0 typed-output consolidation grew `ToolInvoke` with two such
 * fields — `output: ToolOutput = ToolOutput.Pending` and `outcome:
 * ToolOutcome = ToolOutcome.Pending`. Historical events from pre-1.1.0 have
 * no entry under either key; the codegen's emitted `fromJson` needs the
 * same property the Scala-side default carries.
 *
 * The spice-side `DartRequiredPrimitiveSpec` tests the contract against
 * synthetic fixtures; this Sigil-side spec locks in the real-world case
 * the bug doc cites end-to-end.
 */
class DartGeneratorDefaultFieldReplaySpec extends AnyWordSpec with Matchers {

  private val ToolInvokeWire: (String, fabric.define.Definition) =
    "ToolInvoke" -> summon[RW[ToolInvoke]].definition

  private def generate(): List[spice.openapi.generator.SourceFile] =
    spice.openapi.generator.dart.DurableSocketDartGenerator(
      spice.openapi.generator.dart.DurableSocketDartConfig(
        serviceName = "Test",
        wireType = ToolInvokeWire
      )
    ).generate()

  "Dart codegen for ToolInvoke" should {

    "guard the fromJson read on `output` against missing keys" in {
      val files = generate()
      val source = files.find(_.fileName == "tool_invoke.dart").map(_.source).getOrElse("")
      withClue(s"tool_invoke.dart source:\n$source\n") {
        source should include regex
          """output\s*=\s*json\[['"]output['"]\]\s*!=\s*null\s*\?[^:]+:\s*ToolOutput\.pending"""
        // The pre-fix shape (unconditional cast) must be gone — that's the
        // exact line that crashes on replay of pre-1.1.0 events.
        source should not include
          "output = ToolOutput.fromJson(json['output'] as Map<String, dynamic>)"
      }
    }

    "guard the fromJson read on `outcome` against missing keys" in {
      val files = generate()
      val source = files.find(_.fileName == "tool_invoke.dart").map(_.source).getOrElse("")
      withClue(s"tool_invoke.dart source:\n$source\n") {
        source should include regex
          """outcome\s*=\s*json\[['"]outcome['"]\]\s*!=\s*null\s*\?[^:]+:\s*ToolOutcome\.pending"""
        source should not include
          "outcome = ToolOutcome.fromJson(json['outcome'] as Map<String, dynamic>)"
      }
    }
  }

  /**
   * Sigil bug #270 — `fromString` must accept the same parent-prefixed
   * form `wireName` writes. Pre-fix, every `EventState` field on the wire
   * (`"EventState.Complete"`) landed null on the Dart side, so settle
   * branches keyed on `EventState.complete` were skipped and messages
   * never appeared to finish.
   */
  "Dart codegen for EventState" should {

    "emit a fromString reader that parses the parent-prefixed wire form" in {
      val files = spice.openapi.generator.dart.DurableSocketDartGenerator(
        spice.openapi.generator.dart.DurableSocketDartConfig(
          serviceName = "Test",
          wireType = "EventState" -> summon[RW[EventState]].definition
        )
      ).generate()
      val source = files.find(_.fileName == "event_state.dart").map(_.source).getOrElse("")
      withClue(s"event_state.dart source:\n$source\n") {
        // Writer side carries the parent-prefixed form fabric 1.29 emits.
        source should include("'EventState.Active'")
        source should include("'EventState.Complete'")
        // Reader strips the parent prefix before matching.
        source should include("value.lastIndexOf('.')")
        // Reader transforms the leading char only (mirrors dartIdentifier).
        source should include("bare[0].toLowerCase()")
        // No broad lowercasing — was the pre-fix shape.
        source should not include "value.toLowerCase()"
      }
    }
  }
}
