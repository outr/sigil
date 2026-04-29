package sigil.debug

import fabric.Json
import fabric.rw.*
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}

import scala.jdk.CollectionConverters.*

case class DapLaunchInput(languageId: String,
                          sessionId: String,
                          launchArguments: Map[String, Json] = Map.empty,
                          breakpointsByFile: Map[String, List[Int]] = Map.empty,
                          exceptionFilters: List[String] = Nil) extends ToolInput derives RW

/**
 * Spawn a debug adapter for the given language and start a fresh
 * process for debugging. Composes the multi-step DAP startup
 * (initialize → launch → setBreakpoints → setExceptionBreakpoints →
 * configurationDone) into a single agent call.
 *
 * `launchArguments` is the language-adapter-specific payload (which
 * program to run, working directory, environment, etc.). Each
 * adapter documents its own keys; common shapes:
 *
 *   - **JVM** (sbt's debug bridge): `mainClass`, `arguments`, `jvmOptions`, `runtimeClasspath`
 *   - **Python** (debugpy): `program`, `args`, `cwd`, `env`
 *   - **Go** (delve): `program`, `args`, `mode = "debug"`
 *   - **Rust** (lldb-dap): `program`, `args`, `cwd`
 *
 * After this returns, the program is running until it hits a
 * breakpoint / exception. The agent polls `dap_session_status` for
 * the next stop event.
 */
final class DapLaunchTool(val manager: DapManager) extends TypedTool[DapLaunchInput](
  name = ToolName("dap_launch"),
  description =
    """Spawn a debug adapter and launch a fresh program for debugging.
      |
      |`languageId` selects the persisted DebugAdapterConfig.
      |`sessionId` is an opaque id the agent uses for subsequent dap_* calls
      |(typically the conversation id or a synthetic name).
      |`launchArguments` is the adapter-specific payload (program path, cwd, env, etc.).
      |`breakpointsByFile` (optional) is a map of source path → line numbers for initial breakpoints.
      |`exceptionFilters` (optional) is a list of adapter-defined filter ids (e.g. "uncaught", "all").
      |Returns the resulting session id and any breakpoints that failed to verify.""".stripMargin,
  examples = List(
    ToolExample(
      "launch a sbt main class with one breakpoint",
      DapLaunchInput(
        languageId = "scala",
        sessionId = "demo-session",
        launchArguments = Map("mainClass" -> fabric.str("com.example.Main")),
        breakpointsByFile = Map("/abs/path/Main.scala" -> List(15))
      )
    )
  )
) with DapToolSupport {
  override protected def executeTyped(input: DapLaunchInput, context: TurnContext): Stream[Event] = {
    val task = manager.spawn(input.languageId, input.sessionId).flatMap { session =>
      val args = input.launchArguments.map { case (k, v) =>
        k -> jsonToObject(v)
      }.asJava

      for {
        _    <- session.launch(args)
        bps  <- Task.sequence(
                  input.breakpointsByFile.toList.map { case (path, lines) =>
                    session.setBreakpoints(path, lines).map(path -> _)
                  }
                )
        _    <- if (input.exceptionFilters.nonEmpty) session.setExceptionBreakpoints(input.exceptionFilters)
                else Task.pure(Nil)
        _    <- session.configurationDone()
      } yield {
        val bpReport = bps.map { case (path, set) =>
          val verified = set.count(_.isVerified)
          s"  $path: ${verified}/${set.size} verified"
        }.mkString("\n")
        val header = s"Debug session '${input.sessionId}' launched (language=${input.languageId})."
        if (bpReport.isEmpty) header else s"$header\nBreakpoints:\n$bpReport"
      }
    }.map(text => reply(context, text, isError = false))
      .handleError(e => Task.pure(reply(context, s"DAP launch failed: ${e.getMessage}", isError = true)))
    Stream.force(task.map(Stream.emit))
  }

  /** Translate fabric `Json` into the boxed Java types lsp4j-debug
    * expects in the launch arguments map. Values that don't fit a
    * primitive role flatten to their JSON-string form. */
  private def jsonToObject(j: Json): Object = j match {
    case fabric.Str(s, _)   => s
    case fabric.NumInt(n, _)  => java.lang.Long.valueOf(n)
    case fabric.NumDec(d, _) => d.bigDecimal.doubleValue.asInstanceOf[Object]
    case fabric.Bool(b, _)  => java.lang.Boolean.valueOf(b)
    case fabric.Null        => null
    case fabric.Arr(values, _) =>
      values.toArray.map(jsonToObject)
    case obj: fabric.Obj =>
      obj.value.map { case (k, v) => k -> jsonToObject(v) }.asJava
  }
}
