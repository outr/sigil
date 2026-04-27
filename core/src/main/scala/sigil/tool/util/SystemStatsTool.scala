package sigil.tool.util

import fabric.io.JsonFormatter
import fabric.{Json, Null, num, obj, str}
import rapid.Stream
import sigil.TurnContext
import sigil.event.{Event, Message, Role}
import sigil.signal.EventState
import sigil.tool.fs.FileSystemContext
import sigil.tool.model.{ResponseContent, SystemStatsInput}
import sigil.tool.{ToolExample, ToolName, TypedTool}

/**
 * Report basic host resource usage (CPU, memory, disk, load
 * average). Implementation runs Linux shell utilities via the
 * supplied [[FileSystemContext]] (`top`, `free`, `df`, `uptime`)
 * and parses the output. Best-effort — values default to 0 if
 * parsing fails.
 *
 * Diagnostic-only. Apps that want deeper observability should
 * surface metrics via their own provider.
 */
final class SystemStatsTool(context: FileSystemContext)
  extends TypedTool[SystemStatsInput](
    name = ToolName("system_stats"),
    description = "Report system resource usage — CPU usage, memory, disk free, load average — by parsing standard Linux shell utilities.",
    examples = List(
      ToolExample("Default — everything", SystemStatsInput()),
      ToolExample("Only memory", SystemStatsInput(includeCpu = false, includeDisk = false, includeLoadAvg = false))
    ),
    keywords = Set("system", "stats", "cpu", "memory", "disk", "load", "uptime")
  ) {

  override protected def executeTyped(input: SystemStatsInput, ctx: TurnContext): Stream[Event] = {
    val parts = List(
      if (input.includeCpu) Some("top -bn1 | head -5") else None,
      if (input.includeMemory) Some("free -m") else None,
      if (input.includeDisk) Some("df -h --output=target,size,used,avail,pcent 2>/dev/null || df -h") else None,
      if (input.includeLoadAvg) Some("cat /proc/loadavg 2>/dev/null; uptime") else None
    ).flatten
    val cmd = parts.mkString(" && echo '---SEPARATOR---' && ")

    Stream.force(context.executeCommand(cmd).map { result =>
      val sections = result.stdout.split("---SEPARATOR---").map(_.trim)
      var idx = 0
      def next(): String = { val s = if (idx < sections.length) sections(idx) else ""; idx += 1; s }

      val cpu = if (input.includeCpu) {
        val s = next()
        val cpuLine = s.linesIterator.find(_.contains("Cpu")).getOrElse("")
        val idle = """(\d+\.\d+)\s+id""".r.findFirstMatchIn(cpuLine).flatMap(m => m.group(1).toDoubleOption).getOrElse(100.0)
        Some(obj(
          "usagePct" -> num(Math.round((100.0 - idle) * 10) / 10.0),
          "cores"    -> num(Runtime.getRuntime.availableProcessors())
        ))
      } else None

      val memory = if (input.includeMemory) {
        val s = next()
        val memLine = s.linesIterator.find(_.startsWith("Mem:")).getOrElse("")
        val parts = memLine.split("\\s+")
        if (parts.length >= 4) {
          val total = parts(1).toLongOption.getOrElse(0L)
          val used  = parts(2).toLongOption.getOrElse(0L)
          val avail = if (parts.length >= 7) parts(6).toLongOption.getOrElse(total - used) else total - used
          Some(obj(
            "totalMb"   -> num(total),
            "usedMb"    -> num(used),
            "availMb"   -> num(avail),
            "usagePct"  -> num(if (total > 0) Math.round(used.toDouble / total * 1000) / 10.0 else 0.0)
          ))
        } else None
      } else None

      val disks = if (input.includeDisk) {
        val s = next()
        s.linesIterator.drop(1).flatMap { line =>
          val cols = line.trim.split("\\s+")
          if (cols.length >= 5) {
            Some(obj(
              "mount"    -> str(cols(0)),
              "size"     -> str(cols(1)),
              "used"     -> str(cols(2)),
              "avail"    -> str(cols(3)),
              "usagePct" -> str(cols(4))
            ))
          } else None
        }.toVector
      } else Vector.empty

      val loadAvg = if (input.includeLoadAvg) {
        val s = next()
        val line = s.linesIterator.toList.headOption.getOrElse("").trim
        val parts = line.split("\\s+")
        if (parts.length >= 3)
          Some(obj(
            "load1"  -> num(parts(0).toDoubleOption.getOrElse(0.0)),
            "load5"  -> num(parts(1).toDoubleOption.getOrElse(0.0)),
            "load15" -> num(parts(2).toDoubleOption.getOrElse(0.0))
          ))
        else None
      } else None

      val payload = obj(
        "cpu"            -> cpu.getOrElse(Null),
        "memory"         -> memory.getOrElse(Null),
        "disks"          -> fabric.Arr(disks),
        "loadAverage"    -> loadAvg.getOrElse(Null)
      )

      Stream.emit[Event](Message(
        participantId  = ctx.caller,
        conversationId = ctx.conversation.id,
        topicId        = ctx.conversation.currentTopicId,
        content        = Vector(ResponseContent.Text(JsonFormatter.Compact(payload))),
        state          = EventState.Complete,
        role           = Role.Tool
      ))
    })
  }
}
