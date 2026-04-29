package sigil.codegen

import fabric.define.Definition
import fabric.rw.*
import sigil.Sigil
import sigil.signal.Signal
import spice.openapi.generator.dart.{DurableSocketDartConfig, DurableSocketDartGenerator}

import java.nio.file.{Path, Paths}

/**
 * Build-time helper that wraps [[DurableSocketDartGenerator]] with the
 * setup every Sigil consumer needs:
 *
 *   - drives [[Sigil.polymorphicRegistrations]] (NOT `instance`) so the
 *     fabric `PolyType` discriminators are populated for the codegen
 *     read of `summon[RW[Signal]].definition`. `polymorphicRegistrations`
 *     does NOT open the LightDB / RocksDB store, so codegen can run while
 *     a backend server is live (no lock contention).
 *   - sets `wireType` to `Signal -> summon[RW[Signal]].definition` —
 *     Sigil's wire vocabulary is Signals; consumers shouldn't be picking
 *     a different wire type.
 *   - sets `durableSubtypes` to [[Sigil.eventSubtypeNames]] so the
 *     generator knows which Signal subtypes ride the durable channel
 *     (everything that's an `Event`) vs. the ephemeral one (Deltas,
 *     Notices).
 *
 * Apps invoke from a small `runMain` shim:
 *
 * {{{
 *   object GenerateDart {
 *     def main(args: Array[String]): Unit =
 *       sigil.codegen.DartGenerator(
 *         sigil       = Sage,
 *         serviceName = "Sage",
 *         infoFields  = List("conversationId" -> "String"),
 *         defTypes    = List("SageInfo" -> summon[RW[SageInfo]].definition)
 *       ).run(args)
 *   }
 * }}}
 *
 * @param sigil           the [[Sigil]] instance to run polymorphic
 *                        registration against and pull `eventSubtypeNames`
 *                        from
 * @param serviceName     the Dart service name used as a prefix for
 *                        generated client / handler / sender symbols
 *                        (typically the app name, e.g. `"Sage"`)
 * @param infoFields      `(name, dart-type)` pairs describing the
 *                        connect-time `Info` payload's shape — usually
 *                        `List("conversationId" -> "String")`
 * @param defTypes        additional named [[Definition]]s the generator
 *                        should emit Dart classes for — typically the
 *                        consumer's `Info` case-class so the wire
 *                        handshake type lands in the generated output
 * @param storedEventMode whether the durable channel uses
 *                        spice's stored-event mode (default `true`,
 *                        matching `SigilDbEventLog`)
 */
final case class DartGenerator(sigil: Sigil,
                               serviceName: String,
                               infoFields: List[(String, String)],
                               defTypes: List[(String, Definition)] = Nil,
                               storedEventMode: Boolean = true) {

  /** Run the generator end-to-end: phase-1 registrations, generate
    * source files, write to `outputPath`, log every emitted file plus
    * a count summary. Defaults `outputPath` to `../app` if no CLI arg
    * is supplied — matches the convention every Sigil consumer's
    * `GenerateDart` shim uses today. */
  def run(args: Array[String]): Unit = {
    val outputPath: Path =
      if (args.nonEmpty) Paths.get(args.head)
      else Paths.get("../app")

    sigil.polymorphicRegistrations.sync()

    val config = DurableSocketDartConfig(
      serviceName = serviceName,
      storedEventMode = storedEventMode,
      infoFields = infoFields,
      wireType = "Signal" -> summon[RW[Signal]].definition,
      clientEventDefs = Nil,
      defTypes = defTypes,
      durableSubtypes = sigil.eventSubtypeNames
    )

    val generator = DurableSocketDartGenerator(config)
    val files = generator.generate()
    generator.write(files, outputPath)

    val tag = serviceName.toLowerCase
    files.foreach(sf => println(s"[$tag:codegen] ${sf.path}/${sf.fileName}"))
    println(s"[$tag:codegen] wrote ${files.size} file(s) under $outputPath")
  }
}
