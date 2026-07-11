package sigil.workflow

import fabric.Json

import scala.util.matching.Regex

/**
 * Resolve `{{varName}}` placeholders in a string against the
 * workflow's variable map. A simple substitution model — the agent
 * authors prompts and tool arguments with `{{outputOfPriorStep}}`
 * to thread state forward.
 *
 * Values are rendered as their JSON representation: strings drop
 * their surrounding quotes (so the substituted prompt reads
 * naturally), arrays / objects render as compact JSON, primitives
 * render as themselves.
 *
 * Unknown variables are left as their raw `{{var}}` literal — this
 * lets agents debug missing inputs by seeing the unresolved
 * placeholder in the running workflow's step output.
 */
object WorkflowVariableSubstitution {
  private val Pattern: Regex = """\{\{(\w+)\}\}""".r

  def substitute(template: String, variables: Map[String, Json]): String =
    if (template.isEmpty || variables.isEmpty) template
    else Pattern.replaceAllIn(
      template,
      m => {
        val key = m.group(1)
        variables.get(key) match {
          case None => Regex.quoteReplacement(m.matched)
          case Some(json) => Regex.quoteReplacement(render(json))
        }
      }
    )

  /**
   * Sigil #382 — distinct variable names still present as unresolved
   * `{{var}}` placeholders after substitution. Used to HARD-FAIL a step whose
   * resolved tool arguments still carry a template: a mis-wired variable must
   * never reach a tool (`write_file` overwrote real files with the literal
   * `{{editedContents}}`). Empty when everything resolved.
   */
  def unresolvedVars(resolved: String): List[String] =
    Pattern.findAllMatchIn(resolved).map(_.group(1)).distinct.toList

  private val ExactPattern: Regex = """^\{\{(\w+)\}\}$""".r

  /**
   * Sigil #392 — substitute `{{var}}` placeholders INTO a parsed JSON structure
   * rather than into the raw JSON text. Walks `json`; for each string leaf:
   *   - exactly `{{var}}` (whole string) → the variable's TYPED JSON value, so an
   *     object/array/number-valued variable lands as that JSON (not a stringified
   *     blob), and a string value is placed as a proper escaped JSON string;
   *   - embedded `{{var}}` in surrounding text → the variable rendered as a string
   *     interpolated in place (still a single JSON string leaf, escaped on
   *     re-serialize);
   *   - unknown variable → left as the literal `{{var}}` (caught by
   *     [[unresolvedVarsInJson]]).
   *
   * This is the fix for the silent `{}` collapse: the old path string-spliced a
   * value into the args text and re-parsed, so a value carrying quotes/newlines
   * broke the JSON and `getOrElse(obj())` swallowed the whole object.
   */
  def substituteJson(json: Json, variables: Map[String, Json]): Json = json match {
    case fabric.Str(s, _) => substituteStringLeaf(s, variables)
    case fabric.Arr(values, _) => fabric.Arr(values.map(v => substituteJson(v, variables)))
    case fabric.Obj(map) => fabric.Obj(map.map { case (k, v) => k -> substituteJson(v, variables) })
    case other => other
  }

  private def substituteStringLeaf(s: String, variables: Map[String, Json]): Json = s match {
    case ExactPattern(key) =>
      variables.get(key) match {
        case Some(value) => value
        case None => fabric.Str(s)
      }
    case _ =>
      fabric.Str(Pattern.replaceAllIn(
        s,
        m =>
          variables.get(m.group(1)) match {
            case None => Regex.quoteReplacement(m.matched)
            case Some(value) => Regex.quoteReplacement(render(value))
          }))
  }

  /**
   * Sigil #392 — distinct variable names still unresolved (`{{var}}`) in any
   * string leaf of a substituted JSON structure. The structured analogue of
   * [[unresolvedVars]], used to fail-loud on a tool's arguments.
   */
  def unresolvedVarsInJson(json: Json): List[String] = {
    val builder = List.newBuilder[String]
    def walk(j: Json): Unit = j match {
      case fabric.Str(s, _) => Pattern.findAllMatchIn(s).foreach(m => builder += m.group(1))
      case fabric.Arr(values, _) => values.foreach(walk)
      case fabric.Obj(map) => map.values.foreach(walk)
      case _ => ()
    }
    walk(json)
    builder.result().distinct
  }

  private def render(j: Json): String = j match {
    case fabric.Str(s, _) => s
    case fabric.NumInt(n, _) => n.toString
    case fabric.NumDec(d, _) => d.toString
    case fabric.Bool(b, _) => b.toString
    case fabric.Null => ""
    case other => fabric.io.JsonFormatter.Compact(other)
  }
}
