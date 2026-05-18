package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.{Files, Paths}
import scala.io.Source
import scala.jdk.CollectionConverters.*

/**
 * Tool descriptions must describe what THIS tool does on its own
 * terms. Cross-references to other tool names create broken links
 * when consumers expose only a subset of the catalog, and they
 * imply coupling that isn't real (every tool in core / sigil-tooling
 * is independently registered; composition is a workflow concern
 * the agent figures out from its roster, not a baked-in dependency).
 *
 * Allowed cross-references are limited to tightly-coupled family
 * pairs that ship as one unit — the respond family, pin/unpin
 * pairs, and the pagination pair. Anything else fails the audit.
 */
class ToolDescriptionAuditSpec extends AnyWordSpec with Matchers {

  private val roots = List(
    "core/src/main/scala",
    "tooling/src/main/scala"
  )
  private val NameRe = """ToolName\("([^"]+)"\)""".r
  private val Triple = """description\s*=\s*"""""".r // we'll parse manually
  private val Simple = """description\s*=\s*"([^"]+)"""".r
  private val BacktickRe = """`([a-z_][a-z0-9_]*)`""".r

  private def readAllScala: List[(String, String)] =
    roots.flatMap { root =>
      val p = Paths.get(root)
      if (!Files.exists(p)) Nil
      else Files.walk(p).iterator.asScala
        .filter(_.toString.endsWith(".scala"))
        .map(p => p.toString -> Source.fromFile(p.toFile).getLines().mkString("\n"))
        .toList
    }

  /**
   * Extract `description = """..."""` (triple-quoted) or
   * `description = "..."` (single-line) from a tool source.
   */
  private def descriptionOf(src: String): Option[String] = {
    val idx = src.indexOf("description")
    if (idx < 0) return None
    val sub = src.substring(idx)
    // Match """...""" first
    val tripleStart = sub.indexOf("\"\"\"")
    if (tripleStart >= 0 && tripleStart < 50) {
      val after = sub.substring(tripleStart + 3)
      val tripleEnd = after.indexOf("\"\"\"")
      if (tripleEnd >= 0) return Some(after.substring(0, tripleEnd))
    }
    Simple.findFirstMatchIn(sub).map(_.group(1))
  }

  private val allowed: Set[(String, String)] = {
    val respondFam = Set(
      "respond",
      "respond_options",
      "respond_field",
      "respond_failure",
      "respond_card",
      "respond_cards",
      "no_response")
    val cross = for (a <- respondFam; b <- respondFam if a != b) yield (a, b)
    cross ++ Set(
      "pin_complexity" -> "unpin_complexity",
      "unpin_complexity" -> "pin_complexity",
      "pin_memory" -> "unpin_memory",
      "unpin_memory" -> "pin_memory",
      "pin_model" -> "unpin_model",
      "unpin_model" -> "pin_model",
      "next_page" -> "query_tool_output",
      "query_tool_output" -> "next_page"
    )
  }

  "Tool descriptions" should {

    "not reference other tools' names except in allowed tightly-coupled family pairs" in {
      val files = readAllScala
      val knownNames: Set[String] = files.flatMap { case (_, src) =>
        NameRe.findAllMatchIn(src).map(_.group(1)).toList
      }.toSet

      val violations = scala.collection.mutable.ListBuffer.empty[(String, String, String)]
      for ((path, src) <- files) {
        val toolName = NameRe.findFirstMatchIn(src).map(_.group(1))
        for {
          tn <- toolName
          desc <- descriptionOf(src)
          m <- BacktickRe.findAllMatchIn(desc)
        } {
          val ref = m.group(1)
          if (knownNames.contains(ref) && ref != tn && !allowed.contains(tn -> ref)) {
            violations += ((path.split("/").last, tn, ref))
          }
        }
      }
      val grouped = violations.toList.distinct.groupBy(_._2).toList.sortBy(_._1)
      if (grouped.nonEmpty) {
        val report = grouped.map { case (tool, vs) =>
          s"  $tool: " + vs.map(_._3).distinct.sorted.mkString(", ")
        }.mkString("\n")
        fail(s"Tool descriptions reference other tools' names:\n$report")
      }
    }
  }
}
