package sigil.tooling

import fabric.io.JsonParser

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/**
 * Auto-discover a BSP server connection from a project root by reading
 * its `.bsp/<server>.json` files. The BSP spec mandates that every
 * project shipping a build server drops one of these files under
 * `<root>/.bsp/`; the JSON's `argv` field is the launch command.
 */
object BspDiscovery {

  def scan(projectRoot: String): Option[BspBuildConfig] = {
    val bspDir = Paths.get(projectRoot, ".bsp")
    if (!Files.isDirectory(bspDir)) None
    else {
      val candidates = Files.list(bspDir).iterator().asScala.toList
        .filter(p => p.getFileName.toString.endsWith(".json"))
        .sortBy(_.getFileName.toString)
      candidates.iterator.flatMap(parse(projectRoot, _)).nextOption()
    }
  }

  private def parse(projectRoot: String, file: Path): Option[BspBuildConfig] =
    scala.util.Try {
      val text = Files.readString(file)
      val json = JsonParser(text)
      val argv = json.get("argv").map(_.asVector).getOrElse(Vector.empty).map(_.asString).toList
      argv match {
        case Nil => None
        case head :: tail => Some(BspBuildConfig(
            projectRoot = projectRoot,
            command = head,
            args = tail,
            _id = BspBuildConfig.idFor(projectRoot)
          ))
      }
    }.toOption.flatten
}
