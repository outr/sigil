package sigil.codegen

import fabric.define.{DefType, Definition}
import fabric.rw.*
import sigil.Sigil
import sigil.provider.Mode
import sigil.signal.{CoreSignals, Signal}
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
 *     generator knows which Signal subtypes are replayable on resume
 *     (everything that's an `Event` — those persist to
 *     `SigilDB.events`). Deltas and Notices ride the same durable
 *     wire channel as Events but aren't replayed across server
 *     restart; the codegen flags them so clients reconcile correctly.
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

  /**
   * Run the generator end-to-end: phase-1 registrations, generate
   * source files, write to `outputPath`, log every emitted file plus
   * a count summary. Defaults `outputPath` to `../app` if no CLI arg
   * is supplied — matches the convention every Sigil consumer's
   * `GenerateDart` shim uses today.
   */
  def run(args: Array[String]): Unit = {
    val outputPath: Path =
      if (args.nonEmpty) Paths.get(args.head)
      else Paths.get("../app")

    sigil.polymorphicRegistrations.sync()

    // Sanity-check that the Signal poly was populated as expected before
    // snapshotting its definition. Mismatches usually
    // mean (a) a stale jar in the consumer's ivy cache predates one
    // of the signals declared in CoreSignals, (b) the consumer's
    // Sigil subclass shadowed `signalRegistrations` without calling
    // `super.signalRegistrations`, or (c) a build classpath that
    // resolves a different `CoreSignals` than the one this generator
    // compiled against. Warn (not fail) — apps can legitimately add
    // / remove signals via overrides; the diagnostic just flags
    // unexpected shrinkage at the point it bites consumers.
    val signalDef = summon[RW[Signal]].definition
    val registeredKeys: Set[String] = signalDef.defType match {
      case p: DefType.Poly => p.values.keySet.toSet
      case _ => Set.empty
    }
    val coreKeys: Set[String] =
      CoreSignals.all.iterator.flatMap(_.definition.className).map(simpleNameOf).toSet
    val missingFromRegistered = coreKeys.diff(registeredKeys)
    if (missingFromRegistered.nonEmpty) {
      val tag = serviceName.toLowerCase
      System.err.println(
        s"[$tag:codegen] WARNING: CoreSignals declares ${coreKeys.size} subtypes but the Signal " +
          s"poly only has ${registeredKeys.size} registered. Missing: " +
          s"${missingFromRegistered.toList.sorted.mkString(", ")}. " +
          "Generated Dart will not include files for these. Most common cause: a stale Sigil jar " +
          "in the consumer's ivy cache predates these signals — run `sbt publishLocal` against the " +
          "Sigil source tree, or refresh the consumer's classpath."
      )
    }

    val config = DurableSocketDartConfig(
      serviceName = serviceName,
      storedEventMode = storedEventMode,
      infoFields = infoFields,
      wireType = "Signal" -> signalDef,
      clientEventDefs = Nil,
      defTypes = defTypes,
      durableSubtypes = sigil.eventSubtypeNames,
      // Mode's polymorphic RW uses `name` as the wire discriminator key (not the
      // fabric default `type`) so the JSON payload aligns with the value persisted
      // in `ModeChange` events and resolved by `Sigil.modeByName`. Tell the Dart
      // generator about it so emitted `toJson` writes / parent `fromJson` reads
      // the same key.
      polyDiscriminatorKeys = Map(classOf[Mode].getName -> "name")
    )

    val generator = DurableSocketDartGenerator(config)
    val files = generator.generate()
    generator.write(files, outputPath)

    val tag = serviceName.toLowerCase
    files.foreach(sf => println(s"[$tag:codegen] ${sf.path}/${sf.fileName}"))
    println(s"[$tag:codegen] wrote ${files.size} file(s) under $outputPath")
  }

  /**
   * Extract the simple class name from a fabric-shaped className. Matches
   * Fabric's `Definition.simpleClassName` for top-level classes (chain
   * empty → leaf segment); for inner classes (e.g. `Outer.Inner`) returns
   * the leaf. Used by the Signal poly registration cross-check above.
   */
  private def simpleNameOf(cn: String): String = {
    val cleaned = cn.replace("$", ".")
    val parts = cleaned.split('.').toList.filter(_.nonEmpty)
    parts.lastOption.getOrElse(cn)
  }
}
