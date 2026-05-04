package sigil.workflow

import fabric.rw.*
import lightdb.id.Id
import rapid.Task
import strider.Workflow
import strider.step.{Condition, Step}

/**
 * Strider [[Condition]] subclass that evaluates a small expression
 * against the workflow's variable map. Compiled from a
 * [[ConditionStepInput]] by [[WorkflowStepInputCompiler]].
 *
 * Expression grammar (intentionally minimal):
 *
 *   - `{{var}} == "literal"` / `{{var}} != "literal"` — string equality
 *   - `{{var}} > N` / `>= N` / `< N` / `<= N` / `== N` — numeric
 *   - `{{var}}` (alone) — truthy if the variable is present + non-empty
 *
 * Apps that need richer expressions (boolean composition, regex,
 * arithmetic) override the manager's condition path or build a
 * custom step that reads `workflow.variables` directly. The minimal
 * grammar covers the "branch on prior step output" cases agents
 * actually compose.
 */
final case class SigilCondition(input: ConditionStepInput,
                                onTrueId: Id[Step],
                                onFalseId: Id[Step],
                                id: Id[Step] = Step.id()) extends Condition derives RW {
  override def name: String = input.name.getOrElse(input.id)

  override def onTrue: Id[Step] = onTrueId
  override def onFalse: Id[Step] = onFalseId

  override def evaluate(workflow: Workflow): Task[Boolean] = Task {
    val resolved = WorkflowVariableSubstitution.substitute(input.expression, workflow.variables)
    SigilCondition.evaluateExpression(resolved.trim)
  }
}

object SigilCondition {
  private val EqualityPattern = """^"?([^"]*?)"?\s*(==|!=)\s*"?([^"]*?)"?$""".r
  private val NumericPattern = """^([0-9.+-]+)\s*(<=|>=|<|>|==|!=)\s*([0-9.+-]+)$""".r

  private[workflow] def evaluateExpression(expr: String): Boolean = expr match {
    case "" | "false" | "0" | "null" => false
    case "true"                       => true
    case NumericPattern(left, op, right) =>
      val l = left.toDouble; val r = right.toDouble
      op match {
        case "==" => l == r
        case "!=" => l != r
        case "<"  => l < r
        case "<=" => l <= r
        case ">"  => l > r
        case ">=" => l >= r
      }
    case EqualityPattern(left, op, right) =>
      op match {
        case "==" => left == right
        case "!=" => left != right
      }
    case nonEmpty => nonEmpty.nonEmpty
  }
}
