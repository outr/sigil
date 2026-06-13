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
    else Pattern.replaceAllIn(template, m => {
      val key = m.group(1)
      variables.get(key) match {
        case None       => Regex.quoteReplacement(m.matched)
        case Some(json) => Regex.quoteReplacement(render(json))
      }
    })

  /** Sigil #382 — distinct variable names still present as unresolved
    * `{{var}}` placeholders after substitution. Used to HARD-FAIL a step whose
    * resolved tool arguments still carry a template: a mis-wired variable must
    * never reach a tool (`write_file` overwrote real files with the literal
    * `{{editedContents}}`). Empty when everything resolved. */
  def unresolvedVars(resolved: String): List[String] =
    Pattern.findAllMatchIn(resolved).map(_.group(1)).distinct.toList

  private def render(j: Json): String = j match {
    case fabric.Str(s, _)    => s
    case fabric.NumInt(n, _) => n.toString
    case fabric.NumDec(d, _) => d.toString
    case fabric.Bool(b, _)   => b.toString
    case fabric.Null         => ""
    case other               => fabric.io.JsonFormatter.Compact(other)
  }
}
