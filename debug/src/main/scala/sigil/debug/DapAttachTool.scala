package sigil.debug

import fabric.Json
import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{
  DiscoverySpec,
  Effect,
  MutationTargeting,
  Resolution,
  TextToolOutput,
  Tool,
  ToolExample,
  ToolIO,
  ToolInput,
  ToolName,
  ToolProfile,
  ToolResult,
  ToolSpec
}

import scala.jdk.CollectionConverters.*

case class DapAttachInput(languageId: String, sessionId: String, attachArguments: Map[String, Json] = Map.empty) extends ToolInput
  derives RW

/**
 * Spawn a debug adapter and attach to a running process. The
 * `attachArguments` payload is adapter-specific — typically `pid`
 * for native debuggers, `host`/`port` for network adapters, etc.
 */
final class DapAttachTool(val manager: DapManager) extends Tool with DapToolSupport {
  type Input = DapAttachInput
  type Output = TextToolOutput
  val io: ToolIO[DapAttachInput, TextToolOutput] = ToolIO.derived[DapAttachInput, TextToolOutput].withExamples(
    ToolExample(
      "attach to a running JVM by port",
      DapAttachInput(
        languageId = "scala",
        sessionId = "attach-1",
        attachArguments = Map("hostName" -> fabric.str("localhost"), "port" -> fabric.num(5005))
      )
    )
  )
  override val name = ToolName("dap_attach")
  override val description =
    """Attach a debug adapter to a running process.
      |
      |`languageId` selects the persisted DebugAdapterConfig.
      |`sessionId` is the opaque id for subsequent dap_* calls.
      |`attachArguments` is the adapter-specific payload (pid, host/port, etc.).""".stripMargin
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.Mutating(MutationTargeting.none)),
    discovery = DiscoverySpec(keywords = Set("debug", "dap", "attach", "process", "pid", "debugger"))
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Explicit(executeResult)

  private def executeResult(input: DapAttachInput, context: ToolContext): Task[ToolResult[TextToolOutput]] =
    manager.spawn(input.languageId, input.sessionId).flatMap { session =>
      val args = input.attachArguments.map { case (k, v) => k -> jsonToObject(v) }.asJava
      session.attach(args).flatMap(_ => session.configurationDone()).map { _ =>
        ToolResult.success(TextToolOutput(
          s"Debug session '${input.sessionId}' attached (language=${input.languageId})."))
      }
    }.handleError(e => Task.pure(ToolResult.failure(s"DAP attach failed: ${e.getMessage}")))

  private def jsonToObject(j: Json): Object = j match {
    case fabric.Str(s, _) => s
    case fabric.NumInt(n, _) => java.lang.Long.valueOf(n)
    case fabric.NumDec(d, _) => d.bigDecimal.doubleValue.asInstanceOf[Object]
    case fabric.Bool(b, _) => java.lang.Boolean.valueOf(b)
    case fabric.Null => null
    case fabric.Arr(values, _) => values.toArray.map(jsonToObject)
    case obj: fabric.Obj => obj.value.map { case (k, v) => k -> jsonToObject(v) }.asJava
  }
}
