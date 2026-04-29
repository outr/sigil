package sigil.tooling;

import ch.epfl.scala.bsp4j.BuildServer;
import ch.epfl.scala.bsp4j.JvmBuildServer;
import ch.epfl.scala.bsp4j.ScalaBuildServer;

/**
 * Type witness combining the BSP base contract with the Scala /
 * JVM extensions. lsp4j's `Launcher.Builder<T>` requires a single
 * remote interface type; this empty Java interface lets the proxy
 * expose `BuildServer`, `ScalaBuildServer`, and `JvmBuildServer`
 * methods through one typed handle without losing source-level
 * type checking.
 *
 * Lives in Java because Scala 3 intersection types
 * (`BuildServer & ScalaBuildServer`) aren't reified as `Class`
 * instances, which is what `Launcher.setRemoteInterface` needs.
 */
public interface CombinedBuildServer extends BuildServer, ScalaBuildServer, JvmBuildServer {
}
