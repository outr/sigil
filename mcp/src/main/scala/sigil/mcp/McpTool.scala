package sigil.mcp

import fabric.Json
import fabric.define.Definition
import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import rapid.Task
import sigil.{GlobalSpace, SpaceId}
import sigil.tool.ToolContext
import sigil.participant.{AgentParticipantId, ParticipantId}
import sigil.provider.Mode
import sigil.tool.{DiscoverySpec, Effect, ImageToolOutput, JsonInput, JsonSchemaToDefinition, MutationTargeting, TextToolOutput, Tool, ToolExample, ToolName, ToolOutput, ToolProfile, ToolResult, ToolSpec}

import java.util.Base64

/**
 * Sigil [[Tool]] backed by an MCP-server-side tool. The wire schema
 * advertised by the server is surfaced directly to the LLM via
 * `inputDefinition` — Sigil generates none of its own. The LLM's
 * arguments arrive as fabric `Json` (wrapped in [[JsonInput]]); the
 * call body fans them out via [[McpManager.callTool]].
 *
 * Text content blocks render into a [[TextToolOutput]]; an `image`
 * content block (or an embedded `resource` whose `mimeType` is
 * `image/…`) is decoded from its base64 payload, persisted via
 * [[sigil.Sigil.storeBytes]], and surfaced as an [[ImageToolOutput]]
 * so the framework lifts the picture into the agent's visual context.
 *
 * The display name in `name` includes the server's `prefix` (so two
 * servers can both expose `read_file` without collision).
 */
final class McpTool(manager: McpManager,
                    serverConfig: McpServerConfig,
                    definition: McpToolDefinition) extends Tool {
  type Input  = JsonInput
  type Output = ToolOutput
  val inputRW  = summon[RW[JsonInput]]
  val outputRW = summon[RW[ToolOutput]]

  val spec: ToolSpec = McpTool.specFor(serverConfig, definition)

  override def inputDefinition: Definition =
    JsonSchemaToDefinition(definition.inputSchema)

  override val examples: List[ToolExample] = Nil
  override val createdBy: Option[ParticipantId] = None
  override val _id: Id[Tool] = Id(name.value)
  override val created: Timestamp = Tool.Epoch
  override val modified: Timestamp = Tool.Epoch

  override def executeResult(input: JsonInput, context: ToolContext): Task[ToolResult[ToolOutput]] = {
    val agentId = context.chain.collectFirst { case a: AgentParticipantId => a }
      .getOrElse(context.caller)
    manager.callTool(serverConfig.name, definition.name, input.json, agentId).flatMap { result =>
      val isError = result.get("isError").exists(_.asBoolean)
      if (isError) {
        val rendered = renderText(result)
        Task.pure(ToolResult.failure(if (rendered.isEmpty) "(empty error)" else rendered))
      } else {
        buildOutput(result, context).map(ToolResult.Success(_))
      }
    }.handleError { t =>
      Task.pure(ToolResult.failure(s"MCP tool error: ${t.getMessage}"))
    }
  }

  /**
   * Build the typed output from an MCP `CallToolResult`
   * (`{ content: [...], isError }`). Text blocks render to a
   * [[TextToolOutput]]; if any image block is present, its bytes are
   * stored and the first image is surfaced as an [[ImageToolOutput]]
   * (with the text channel preserved and a note when more than one
   * image was returned).
   */
  private def buildOutput(result: Json, context: ToolContext): Task[ToolOutput] = {
    val blocks = result.get("content").map(_.asVector.toList).getOrElse(Nil)
    val images = blocks.flatMap(imageRef)
    val text   = blocks.flatMap(blockToText).mkString("\n")
    images match {
      case Nil => Task.pure(TextToolOutput(text))
      case (data, mime) :: _ =>
        context.sigil.storeBytes(GlobalSpace, Base64.getDecoder.decode(data), mime,
          metadata = Map("kind" -> "mcp-tool-result", "server" -> serverConfig.name, "tool" -> definition.name))
          .map { stored =>
            val note =
              if (images.sizeIs > 1) {
                val extra = s"(${images.size} images returned; only the first is shown.)"
                if (text.isEmpty) extra else s"$text\n$extra"
              } else text
            ImageToolOutput(
              url  = context.sigil.storageUrl(stored),
              alt  = s"MCP image result from ${name.value}",
              text = Option(note).filter(_.nonEmpty)
            )
          }
    }
  }

  private def renderText(result: Json): String =
    result.get("content").map(_.asVector.toList).getOrElse(Nil).flatMap(blockToText).mkString("\n")

  private def blockToText(block: Json): Option[String] =
    if (imageRef(block).isDefined) None
    else block.get("type").map(_.asString).getOrElse("") match {
      case "text" => block.get("text").map(_.asString)
      case _      => Some(fabric.io.JsonFormatter.Compact(block))
    }

  /** `(base64Data, mimeType)` for an `image` content block or an
    * embedded `resource` block whose `mimeType` is `image/…`. */
  private def imageRef(block: Json): Option[(String, String)] = {
    val t = block.get("type").map(_.asString).getOrElse("")
    if (t == "image") {
      val mime = block.get("mimeType").map(_.asString).getOrElse("")
      if (mime.startsWith("image/")) block.get("data").map(_.asString).map(_ -> mime) else None
    } else if (t == "resource") {
      block.get("resource").flatMap { r =>
        val mime = r.get("mimeType").map(_.asString).getOrElse("")
        if (mime.startsWith("image/")) r.get("blob").map(_.asString).map(_ -> mime) else None
      }
    } else None
  }
}

object McpTool {

  /** Build the validated [[ToolSpec]] from the server-advertised
    * definition. The display name (server prefix + tool name) is
    * validated against the provider grammar; characters providers
    * reject are replaced with `_` (with a warn) so one oddly-named
    * server tool can't break the whole server's roster. A missing
    * description gets a synthesized stub. MCP declares no effect
    * metadata, so the effect is conservatively [[Effect.Mutating]]
    * with unknown targets. */
  def specFor(serverConfig: McpServerConfig, definition: McpToolDefinition): ToolSpec = {
    val rawName = serverConfig.prefix.getOrElse("") + definition.name
    val name = ToolName.parse(rawName).getOrElse {
      val sanitized = rawName.replaceAll("[^a-zA-Z0-9_-]", "_").take(64)
      scribe.warn(s"MCP tool name '$rawName' is outside the provider grammar; sanitized to '$sanitized'.")
      ToolName.parse(sanitized).fold(
        err => throw new IllegalArgumentException(s"MCP tool name '$rawName' cannot be sanitized: $err"),
        identity
      )
    }
    val description = definition.description.filterNot(_.isBlank).getOrElse(
      s"MCP tool `${definition.name}` from server `${serverConfig.name}`."
    ) match {
      case d if d.length > ToolSpec.DescriptionBudget =>
        scribe.warn(s"MCP tool '$rawName' description is ${d.length} chars; truncating to the ${ToolSpec.DescriptionBudget}-char wire budget.")
        d.take(ToolSpec.DescriptionBudget)
      case d => d
    }
    ToolSpec(
      name = name,
      description = description,
      profile = ToolProfile(effect = Effect.Mutating(MutationTargeting.none)),
      discovery = DiscoverySpec(
        keywords = Set("mcp", serverConfig.name),
        space = serverConfig.space,
        kind = McpKind
      )
    )
  }
}
