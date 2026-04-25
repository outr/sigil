package sigil.provider.debug

import java.nio.file.Path

/**
 * CLI entrypoint for [[WireLogAnalyzer]]. Pass a directory; prints
 * every finding, exits 0 if none, 1 if any.
 *
 * {{{
 *   sbt 'runMain sigil.provider.debug.WireLogAnalyzerMain target/wire-logs'
 * }}}
 */
object WireLogAnalyzerMain {
  def main(args: Array[String]): Unit = {
    val dir = args.headOption.getOrElse("target/wire-logs")
    val findings = WireLogAnalyzer.analyze(Path.of(dir))
    if (findings.isEmpty) {
      println(s"No wire-log issues found in $dir.")
    } else {
      println(s"Found ${findings.size} wire-log issue(s) in $dir:")
      findings.foreach(f => println(s"  - $f"))
      sys.exit(1)
    }
  }
}
