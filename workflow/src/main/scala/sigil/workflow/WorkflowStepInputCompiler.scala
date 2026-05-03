package sigil.workflow

import fabric.rw.*
import lightdb.id.Id
import sigil.Sigil
import strider.step.{JoinMode, Loop, Parallel, Step, SubWorkflow}

/**
 * Compile the agent-authored [[WorkflowStepInput]] tree into the
 * flat `List[strider.step.Step]` Strider's engine consumes, plus the
 * top-level execution queue. Recursive bodies (Parallel branches,
 * Loop body) are flattened into the result list with their parent
 * step referencing IDs.
 *
 * Returns:
 *
 *   - `steps`: every compiled [[strider.step.Step]] in the workflow,
 *     including bodies of Parallel / Loop / SubWorkflow
 *   - `queue`: the top-level execution order — the IDs of the
 *     direct children of the workflow root, in order
 *   - `idsByInputId`: lookup from `WorkflowStepInput.id` (the
 *     agent-facing string id) to the concrete `Id[Step]` that
 *     compiled instance got. Used by Condition step compilation
 *     to wire `onTrue` / `onFalse` to the right concrete IDs.
 */
object WorkflowStepInputCompiler {

  final case class Compiled(steps: List[Step],
                            queue: List[Id[Step]],
                            idsByInputId: Map[String, Id[Step]])

  /** Compile a workflow's `List[WorkflowStepInput]` to the
    * Strider-side flat shape. `stepRWImplicit` supplies the
    * RW[Step] needed by SubWorkflow's encoded child-steps field.
    */
  def compile(inputs: List[WorkflowStepInput])(implicit stepRW: RW[Step]): Compiled = {
    val builder = new Builder()
    val topLevelIds = inputs.map(builder.compileOne)
    builder.resolveConditionTargets()
    Compiled(builder.allSteps.result(), topLevelIds, builder.idsByInputId.toMap)
  }

  /** Mutable accumulator used during the recursive compilation
    * walk. Builds out the flat step list and the
    * input-id-to-concrete-id mapping in one pass; condition steps
    * record their target string ids and resolve them once the full
    * map is known. */
  private final class Builder()(implicit stepRW: RW[Step]) {
    val allSteps: scala.collection.mutable.ListBuffer[Step] = scala.collection.mutable.ListBuffer.empty
    val idsByInputId: scala.collection.mutable.Map[String, Id[Step]] = scala.collection.mutable.Map.empty
    private val pendingConditions: scala.collection.mutable.ListBuffer[(Id[Step], ConditionStepInput)] = scala.collection.mutable.ListBuffer.empty

    def compileOne(input: WorkflowStepInput): Id[Step] = input match {
      case j: JobStepInput =>
        val step = SigilJobStep(j)
        register(j.id, step)
        step.id

      case c: ConditionStepInput =>
        // Targets resolve in a second pass — for now, plant
        // placeholder Ids that get patched once every input id is
        // mapped.
        val placeholder = SigilCondition(c, Step.id(), Step.id())
        register(c.id, placeholder)
        pendingConditions += (placeholder.id -> c)
        placeholder.id

      case a: ApprovalStepInput =>
        val step = SigilApproval(a)
        register(a.id, step)
        step.id

      case p: ParallelStepInput =>
        val branches: List[List[Id[Step]]] = p.branches.map(_.map(compileOne))
        val joinMode = p.joinMode.toLowerCase match {
          case "any" => JoinMode.Any
          case _     => JoinMode.All
        }
        val step = Parallel(branches, joinMode)
        register(p.id, step)
        step.id

      case l: LoopStepInput =>
        val bodyIds = l.body.map(compileOne)
        val step = Loop(
          itemsVariable = l.over,
          bodySteps = bodyIds,
          itemVariableName = l.itemVariable,
          outputVariable = l.output.filter(_.nonEmpty).getOrElse("loopResults")
        )
        register(l.id, step)
        step.id

      case s: SubWorkflowStepInput =>
        // Strider's SubWorkflow stores child steps as encoded JSON;
        // for the cross-workflow invocation case (referencing a
        // persisted template by id) we model it instead as a
        // placeholder that the manager resolves at run time. The
        // current Strider type doesn't expose that shape directly —
        // for now we encode an empty child-step list and rely on
        // the manager's `resolveParent` to pick up the referenced
        // template. Apps that want true inline sub-workflows compile
        // those locally first.
        val step = SubWorkflow(
          childName = s.workflowId,
          childSteps = Nil,
          inputVariables = s.variables.map { case (k, v) => k -> (fabric.str(v): fabric.Json) }
        )
        register(s.id, step)
        step.id

      case t: TriggerStepInput =>
        val step = t.trigger.compile(WorkflowHost.get)
        register(t.id, step)
        step.id

      case other =>
        throw new IllegalStateException(s"Unknown WorkflowStepInput subtype: ${other.getClass.getName}")
    }

    private def register(inputId: String, step: Step): Unit = {
      idsByInputId += (inputId -> step.id)
      allSteps += step
    }

    /** After every input has been compiled, patch each
      * SigilCondition's `onTrueId` / `onFalseId` to point at the
      * concrete step ids resolved from its input-id references. */
    def resolveConditionTargets(): Unit = pendingConditions.foreach { case (placeholderId, input) =>
      val onTrue = idsByInputId.getOrElse(input.onTrue,
        throw new IllegalStateException(s"ConditionStepInput '${input.id}' references unknown step id '${input.onTrue}' in onTrue")
      )
      val onFalse = idsByInputId.getOrElse(input.onFalse,
        throw new IllegalStateException(s"ConditionStepInput '${input.id}' references unknown step id '${input.onFalse}' in onFalse")
      )
      val idx = allSteps.indexWhere(_.id == placeholderId)
      val cond = allSteps(idx).asInstanceOf[SigilCondition]
      allSteps.update(idx, cond.copy(onTrueId = onTrue, onFalseId = onFalse))
    }
  }
}
