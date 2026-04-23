package sigil.provider.debug

import fabric.*
import fabric.io.JsonParser

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/**
 * Post-hoc validator for jsonl wire logs produced by
 * [[JsonLinesInterceptor]]. Walks every request body in every log
 * file under a directory and flags structurally broken or
 * suspicious patterns that stricter provider validators will reject
 * (and weaker ones will silently work around — e.g. gpt-4o-mini
 * rejected `enum: []` while qwen tolerated it).
 *
 * Usage pattern: enable wire logging via `sigil.wire.log.dir` for a
 * full test run, then invoke [[analyze]] over the directory. Any
 * returned [[Finding]]s indicate code that renders wire payloads
 * incorrectly.
 */
object WireLogAnalyzer {

  /** A structural problem in a request body. */
  case class Finding(file: String, requestIdx: Int, path: String, issue: String) {
    override def toString: String = s"$file [request #$requestIdx] $path — $issue"
  }

  /** Walk `dir` (non-recursive); return all findings across every
    * `*.jsonl` file. Ordered by file name, then by request index. */
  def analyze(dir: Path): List[Finding] = {
    if (!Files.exists(dir)) return Nil
    val files = Files.list(dir).iterator().asScala.toList
      .filter(p => p.getFileName.toString.endsWith(".jsonl"))
      .sortBy(_.getFileName.toString)
    files.flatMap(analyzeFile)
  }

  def analyzeFile(path: Path): List[Finding] = {
    val findings = List.newBuilder[Finding]
    val fileName = path.getFileName.toString
    var requestIdx = 0
    Files.readAllLines(path).asScala.foreach { line =>
      if (line.nonEmpty) {
        try {
          val json = JsonParser(line)
          val kind = json.get("kind").map(_.asString).getOrElse("")
          if (kind == "request") {
            val body = json.get("body").getOrElse(Null)
            findings ++= analyzeBody(fileName, requestIdx, body)
            requestIdx += 1
          }
        } catch {
          case _: Throwable => // Skip malformed lines
        }
      }
    }
    findings.result()
  }

  /** Validate a request body recursively. The interesting patterns
    * live inside the `tools` array (custom function tools use JSON
    * Schema, and that's where strict validators bite), but we also
    * catch top-level smells (empty `input`, missing `model`). */
  private def analyzeBody(file: String, idx: Int, body: Json): List[Finding] = {
    if (!body.isObj) return Nil
    val out = List.newBuilder[Finding]
    val root = body.asObj

    // Top-level must identify a model.
    root.value.get("model") match {
      case None => out += Finding(file, idx, "model", "missing `model` field")
      case Some(m) if m.asString.trim.isEmpty =>
        out += Finding(file, idx, "model", "empty `model` field")
      case _ => ()
    }

    // Tools array (OpenAI Responses and chat-completions both use it)
    val toolChoice = root.value.get("tool_choice").map(_.asString)
    val toolsList: Vector[Json] = root.value.get("tools").collect {
      case a if a.isArr => a.asVector
    }.getOrElse(Vector.empty)
    if (toolsList.isEmpty && toolChoice.contains("required")) {
      out += Finding(file, idx, "tool_choice", "tool_choice=required but tools array is empty")
    }
    val names = scala.collection.mutable.Map.empty[String, Int]
    toolsList.zipWithIndex.foreach { case (tool, ti) =>
      out ++= analyzeTool(file, idx, s"tools[$ti]", tool)
      // Track duplicate names within this tools array.
      if (tool.isObj) {
        val fnObj = tool.asObj.value.get("function").collect { case o: Obj => o.value }
          .orElse(Some(tool.asObj.value))
        fnObj.flatMap(_.get("name")).map(_.asString).filter(_.nonEmpty).foreach { n =>
          names.updateWith(n) { case Some(c) => Some(c + 1); case None => Some(1) }
        }
      }
    }
    names.foreach { case (n, c) if c > 1 =>
      out += Finding(file, idx, "tools", s"duplicate tool name '$n' appears $c times")
    case _ => ()
    }

    // Message-log surfaces (chat-completions `messages`, Responses `input`)
    root.value.get("messages").foreach { msgs =>
      if (msgs.isArr && msgs.asVector.isEmpty)
        out += Finding(file, idx, "messages", "empty messages array")
    }
    root.value.get("input").foreach { in =>
      if (in.isArr && in.asVector.isEmpty)
        out += Finding(file, idx, "input", "empty input array")
    }

    out.result()
  }

  private def analyzeTool(file: String, idx: Int, pathPrefix: String, tool: Json): List[Finding] = {
    if (!tool.isObj) return Nil
    val out = List.newBuilder[Finding]
    val obj = tool.asObj
    // OpenAI chat-completions has {function: {name, description, parameters}};
    // Responses has {type: function, name, description, parameters} at top level.
    // Check both layouts.
    val fnObj = obj.value.get("function").collect { case o: Obj => o.value }.orElse(Some(obj.value))
    fnObj.foreach { fields =>
      val rawName = fields.get("name").map(_.asString)
      val toolName = rawName.getOrElse("(unnamed)")
      rawName match {
        case None => out += Finding(file, idx, pathPrefix, "tool is missing `name`")
        case Some("") => out += Finding(file, idx, s"$pathPrefix.name", "tool name is empty")
        case _ => ()
      }
      fields.get("description") match {
        case None => out += Finding(file, idx, s"$pathPrefix($toolName)", "tool is missing `description`")
        case Some(d) if d.asString.trim.isEmpty =>
          out += Finding(file, idx, s"$pathPrefix($toolName).description", "tool description is empty")
        case _ => ()
      }
      fields.get("parameters").foreach { params =>
        out ++= analyzeSchema(file, idx, s"$pathPrefix($toolName).parameters", params)
      }
    }
    out.result()
  }

  /** Recursively walk a JSON Schema fragment looking for structural
    * issues that strict validators (OpenAI gpt-4o-mini, JSON Schema
    * validators like ajv in strict mode) reject. */
  private def analyzeSchema(file: String, idx: Int, path: String, schema: Json): List[Finding] = {
    if (!schema.isObj) return Nil
    val out = List.newBuilder[Finding]
    val fields = schema.asObj.value

    // Empty enum — no valid values; schema is unsatisfiable
    fields.get("enum").foreach { e =>
      if (e.isArr && e.asVector.isEmpty) out += Finding(file, idx, s"$path.enum", "empty enum")
    }
    // Empty oneOf / anyOf / allOf — same issue
    List("oneOf", "anyOf", "allOf").foreach { k =>
      fields.get(k).foreach { arr =>
        if (arr.isArr && arr.asVector.isEmpty) out += Finding(file, idx, s"$path.$k", s"empty $k")
      }
    }
    // `required` references fields that don't exist in `properties`,
    // and duplicates within `required` are invalid.
    (fields.get("required"), fields.get("properties")) match {
      case (Some(req), propsOpt) if req.isArr =>
        val reqKeys = req.asVector.map(_.asString)
        val propKeys = propsOpt.filter(_.isObj).map(_.asObj.value.keySet).getOrElse(Set.empty)
        reqKeys.foreach { key =>
          if (propKeys.nonEmpty && !propKeys.contains(key))
            out += Finding(file, idx, s"$path.required", s"'$key' not in properties")
        }
        val dupes = reqKeys.groupBy(identity).collect { case (k, vs) if vs.size > 1 => k }
        dupes.foreach { k =>
          out += Finding(file, idx, s"$path.required", s"'$k' listed more than once")
        }
      case _ => ()
    }
    // An object schema with declared `required` should also declare `properties`.
    if (fields.contains("required") && !fields.contains("properties")) {
      out += Finding(file, idx, s"$path", "`required` set but no `properties` defined")
    }
    // `enum` on a non-string type is usually a bug in our schema generator.
    (fields.get("type"), fields.get("enum")) match {
      case (Some(t), Some(e)) if t.asString != "string" && e.isArr && e.asVector.nonEmpty =>
        out += Finding(file, idx, s"$path", s"enum on type=${t.asString} (enum typically applies to strings)")
      case _ => ()
    }
    // Recurse into properties
    fields.get("properties").foreach {
      case p: Obj => p.value.foreach { case (k, v) => out ++= analyzeSchema(file, idx, s"$path.properties.$k", v) }
      case _ => ()
    }
    // Recurse into items
    fields.get("items").foreach(i => out ++= analyzeSchema(file, idx, s"$path.items", i))
    // Recurse into variant schemas
    List("oneOf", "anyOf", "allOf").foreach { k =>
      fields.get(k).foreach { arr =>
        if (arr.isArr) arr.asVector.zipWithIndex.foreach {
          case (v, i) => out ++= analyzeSchema(file, idx, s"$path.$k[$i]", v)
        }
      }
    }
    out.result()
  }
}
