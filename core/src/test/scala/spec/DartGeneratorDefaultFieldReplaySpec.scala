package spec

import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.event.ToolInvoke
import sigil.signal.EventState

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
        source should include("'EventState.Active'")
        source should include("'EventState.Complete'")
        source should include("value.lastIndexOf('.')")
        source should include("bare[0].toLowerCase()")
        source should not include "value.toLowerCase()"
      }
    }
  }
}
