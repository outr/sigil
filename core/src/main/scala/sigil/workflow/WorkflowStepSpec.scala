package sigil.workflow

import fabric.Json
import fabric.io.JsonFormatter
import fabric.rw.*
import strider.step.{JoinMode, TimeoutAction}

/**
 * Flat, `kind`-tagged authoring shape for one workflow step — the model-facing
 * input for `create_workflow` / `update_workflow`. Replaces the directly-exposed
 * [[WorkflowStepInput]] union, which forced the model to pick one of seven nested
 * variants and fill its required members — a schema frontier and small models
 * alike fail to fill (sigil #338 / #372). Here every field is a flat scalar (or a
 * list of step-id strings for nested bodies), and only the fields relevant to the
 * chosen `kind` need filling.
 *
 * Nesting is by reference, not containment: a `Loop` names its body steps by id
 * (`bodyStepIds`) and a `Parallel` names each branch's steps by id
 * (`branchStepIds`); those referenced steps are defined as ordinary entries in the
 * same flat `steps` list. [[lower]] re-assembles the nesting and excludes the
 * referenced steps from the top-level sequence.
 *
 * [[lower]] validates per-kind required fields and yields the [[WorkflowStepInput]]
 * IR the engine compiles; the IR, the compiler, and the persisted
 * [[WorkflowTemplate]] are unchanged, so programmatic template construction is
 * unaffected.
 */
case class WorkflowStepSpec(@description("Unique id for this step within the workflow. Other steps reference it (a Loop's bodyStepIds, a Condition's onTrue/onFalse, a Parallel's branchStepIds).")
                            id: String,
                            @description("The step's shape: Job (run a tool or prompt), Loop (iterate over a variable), Condition, Approval, Parallel, SubWorkflow, or Trigger. Fill only the fields for this kind.")
                            kind: WorkflowStepKind,
                            name: Option[String] = None,
                            @description("Job only: an LLM prompt to run (alternative to `tool`). Reference earlier outputs as {{var}}.")
                            prompt: Option[String] = None,
                            @description("Job only: the name of the tool to invoke (e.g. grep, read_file). Alternative to `prompt`.")
                            tool: Option[String] = None,
                            @description("Job only: the tool's arguments as a JSON OBJECT (not a string), e.g. {\"pattern\":\"TODO\",\"path\":\"/src\"}. Put EVERY tool parameter inside this object. Reference earlier outputs / the loop item as {{var}} in the values.")
                            arguments: Option[Json] = None,
                            @description("The variable name this step writes its result to. A discovery Job that feeds a Loop MUST set this; the Loop's `over` then names this exact variable.")
                            output: Option[String] = None,
                            @description("Job `prompt` only: optional difficulty tier — Low, Medium, High, or VeryHigh. The model is selected AUTOMATICALLY from the conversation's mode and this tier; you never name a model. Omit to auto-classify the prompt's difficulty.")
                            complexity: Option[sigil.provider.Complexity] = None,
                            tools: List[String] = Nil,
                            continueOnError: Boolean = false,
                            retryCount: Int = 0,
                            retryDelayMs: Long = 5000L,
                            @description("Condition only: a boolean DSL expression, e.g. {{count}} > 0.")
                            expression: Option[String] = None,
                            onTrue: Option[String] = None,
                            onFalse: Option[String] = None,
                            options: List[String] = Nil,
                            timeoutMs: Option[Long] = None,
                            timeoutAction: Option[TimeoutAction] = None,
                            @description("Loop only: the variable to iterate. Set it to EXACTLY the `output` variable of an earlier Job (e.g. a grep step's output). The engine splits that step's text output into items automatically — do not hand-build a list.")
                            over: Option[String] = None,
                            @description("Loop only: the variable each item is bound to inside the body (default \"item\"); reference it as {{item}} in body steps' arguments/prompts.")
                            itemVariable: Option[String] = None,
                            @description("Loop only: the ids of the steps (defined elsewhere in this same flat list) to run once per item. Required and non-empty for a Loop.")
                            bodyStepIds: List[String] = Nil,
                            @description("Parallel only: a list of branches, each a list of step ids (defined elsewhere in this flat list) to run concurrently.")
                            branchStepIds: List[List[String]] = Nil,
                            joinMode: Option[JoinMode] = None,
                            @description("SubWorkflow only: the id of another persisted workflow template to invoke.")
                            workflowId: Option[String] = None,
                            variables: Map[String, String] = Map.empty,
                            trigger: Option[WorkflowTrigger] = None) derives RW

object WorkflowStepSpec {
  import WorkflowStepKind.*

  /** Lower a flat authoring list to the [[WorkflowStepInput]] IR, validating
    * per-kind required fields and resolving `bodyStepIds` / `branchStepIds`
    * references. `knownVariables` are workflow input variables (declared
    * `variableDefs`) a Loop's `over` may reference in addition to step outputs.
    * `Left` carries human-readable, model-recoverable violations; `Right` is the
    * top-level IR sequence (referenced body/branch steps lowered into their
    * owners, excluded from the top level). */
  def lower(specs: List[WorkflowStepSpec], knownVariables: Set[String] = Set.empty): Either[List[String], List[WorkflowStepInput]] = {
    val byId = specs.iterator.map(s => s.id -> s).toMap
    val errors = validate(specs, byId, knownVariables)
    if (errors.nonEmpty) Left(errors)
    else {
      val nested = nestedIds(specs)
      Right(specs.filterNot(s => nested.contains(s.id)).map(build(_, byId)))
    }
  }

  private def nestedIds(specs: List[WorkflowStepSpec]): Set[String] =
    specs.flatMap {
      case s if s.kind == Loop     => s.bodyStepIds
      case s if s.kind == Parallel => s.branchStepIds.flatten
      case _                       => Nil
    }.toSet

  private def validate(specs: List[WorkflowStepSpec], byId: Map[String, WorkflowStepSpec], knownVariables: Set[String]): List[String] = {
    val errors = List.newBuilder[String]
    if (specs.map(_.id).distinct.size != specs.size) errors += "Step ids must be unique within a workflow."
    def requireRef(id: String, ownerId: String, role: String): Unit =
      if (!byId.contains(id)) errors += s"Step '$ownerId' references unknown $role step id '$id'."
    // Variables a Loop's `over` may legitimately name: any step's declared
    // output plus the workflow's declared input variables.
    val producedVariables: Set[String] = specs.flatMap(s => s.output.filter(_.nonEmpty)).toSet ++ knownVariables
    specs.foreach { s =>
      if (s.id.isEmpty) errors += "Every step requires a non-empty id."
      s.kind match {
        case Job =>
          if (s.prompt.isEmpty && s.tool.isEmpty) errors += s"Job step '${s.id}' requires either a prompt or a tool."
        case Condition =>
          if (s.expression.isEmpty || s.onTrue.isEmpty || s.onFalse.isEmpty)
            errors += s"Condition step '${s.id}' requires expression, onTrue, and onFalse."
        case Approval =>
          if (s.prompt.isEmpty) errors += s"Approval step '${s.id}' requires a prompt."
        case Parallel =>
          if (s.branchStepIds.isEmpty || s.branchStepIds.forall(_.isEmpty))
            errors += s"Parallel step '${s.id}' requires at least one non-empty branch in branchStepIds."
          s.branchStepIds.flatten.foreach(requireRef(_, s.id, "branch"))
        case Loop =>
          s.over match {
            case None => errors += s"Loop step '${s.id}' requires 'over' (the variable to iterate)."
            case Some(v) if v.nonEmpty && !producedVariables.contains(v) =>
              errors += s"Loop step '${s.id}' iterates over '$v', but no earlier step writes that variable " +
                s"(set the discovery step's `output` to '$v') and it isn't a declared input variable."
            case _ => ()
          }
          if (s.bodyStepIds.isEmpty) errors += s"Loop step '${s.id}' requires bodyStepIds (the steps to run per item)."
          s.bodyStepIds.foreach(requireRef(_, s.id, "body"))
        case SubWorkflow =>
          if (s.workflowId.isEmpty) errors += s"SubWorkflow step '${s.id}' requires workflowId."
        case Trigger =>
          if (s.trigger.isEmpty) errors += s"Trigger step '${s.id}' requires a trigger."
      }
    }
    errors ++= loopScopeViolations(specs, byId, knownVariables)
    errors.result().distinct
  }

  /** Matches a `{{varName}}` placeholder — same shape the runtime
    * [[WorkflowVariableSubstitution]] resolves. */
  private val Placeholder: scala.util.matching.Regex = """\{\{(\w+)\}\}""".r

  /** Every variable a step references via `{{var}}` across its substitution
    * sites (prompt, tool arguments, condition expression, sub-workflow variable
    * values). */
  private def referencedVars(s: WorkflowStepSpec): Set[String] = {
    val sources =
      s.prompt.toList ++
        s.arguments.map(JsonFormatter.Compact(_)).toList ++
        s.expression.toList ++
        s.variables.values.toList
    sources.flatMap(Placeholder.findAllMatchIn(_).map(_.group(1))).toSet
  }

  /** The step ids that execute inside a step's nested control flow — a Loop's
    * body, a Parallel's branches, a Condition's two targets. Drives the
    * transitive-closure walk for loop-scope analysis. */
  private def childIds(s: WorkflowStepSpec): List[String] = s.kind match {
    case Loop      => s.bodyStepIds
    case Parallel  => s.branchStepIds.flatten
    case Condition => List(s.onTrue, s.onFalse).flatten
    case _         => Nil
  }

  /** All step ids reachable from `roots` through nested control flow
    * (`childIds`), transitively — so a Loop's body set includes nested loops'
    * bodies, branches, and condition targets. Cycle-guarded. */
  private def closure(roots: List[String], byId: Map[String, WorkflowStepSpec]): Set[String] = {
    @scala.annotation.tailrec
    def walk(frontier: List[String], acc: Set[String]): Set[String] = frontier match {
      case Nil => acc
      case id :: rest =>
        if (acc.contains(id)) walk(rest, acc)
        else walk(byId.get(id).map(childIds).getOrElse(Nil) ::: rest, acc + id)
    }
    walk(roots, Set.empty)
  }

  /**
   * Sigil #384 — reject a plan where a step OUTSIDE a loop references a variable
   * that is bound only INSIDE that loop. The loop's `itemVariable` and the
   * `output`s of its (transitive) body steps live for one iteration each; a
   * top-level step reading them runs once, after the loop, with the value
   * unbound (or only the last iteration's) — so per-item work silently never
   * persists, and an unresolved `{{var}}` can reach a destructive tool arg.
   *
   * A loop-scoped name is only flagged when no step OUTSIDE the loop's body
   * (nor a declared input variable) also produces it — so reusing a name that
   * has a legitimate outer binding isn't a false positive.
   */
  private def loopScopeViolations(specs: List[WorkflowStepSpec],
                                  byId: Map[String, WorkflowStepSpec],
                                  knownVariables: Set[String]): List[String] = {
    val out = List.newBuilder[String]
    specs.filter(_.kind == Loop).foreach { loop =>
      val body = closure(loop.bodyStepIds, byId)
      val itemVar = loop.itemVariable.filter(_.nonEmpty)
      val bodyOutputs = body.flatMap(id => byId.get(id).flatMap(_.output.filter(_.nonEmpty)))
      val loopScoped = itemVar.toSet ++ bodyOutputs
      // Names also produced outside this loop (or declared inputs) have a valid
      // outer binding — don't flag references to those.
      val outerProduced = knownVariables ++
        specs.iterator.filterNot(s => body.contains(s.id) || s.id == loop.id)
          .flatMap(_.output.filter(_.nonEmpty)).toSet
      val leaked = loopScoped -- outerProduced
      if (leaked.nonEmpty) {
        specs.foreach { s =>
          if (s.id != loop.id && !body.contains(s.id)) {
            (referencedVars(s) & leaked).toList.sorted.foreach { v =>
              val origin = if (itemVar.contains(v)) s"the loop item variable of '${loop.id}'"
                           else s"produced only inside loop '${loop.id}'"
              out += s"Step '${s.id}' references '{{$v}}', which is $origin — bound only per iteration. " +
                s"Add '${s.id}' to loop '${loop.id}'.bodyStepIds, or capture the value into a loop `output`."
            }
          }
        }
      }
    }
    out.result()
  }

  /** Build the IR for a validated spec. Safe `getOrElse` defaults are only
    * reached for fields `validate` already proved present. */
  private def build(s: WorkflowStepSpec, byId: Map[String, WorkflowStepSpec]): WorkflowStepInput = s.kind match {
    case Job =>
      // The IR carries `arguments` as a JSON string (the engine's variable
      // substitution + tool-arg decode run on it); the model authors a structured
      // object, which we compact to that string here (#373).
      JobStepInput(s.id, s.name, s.prompt, s.tool, s.arguments.map(JsonFormatter.Compact(_)), s.output, s.complexity, s.tools, s.continueOnError, s.retryCount, s.retryDelayMs)
    case Condition =>
      ConditionStepInput(s.id, s.expression.get, s.onTrue.get, s.onFalse.get, s.name)
    case Approval =>
      ApprovalStepInput(s.id, s.prompt.get, s.name, if (s.options.nonEmpty) s.options else List("approve", "reject"), s.output, s.timeoutMs, s.timeoutAction.getOrElse(TimeoutAction.Fail))
    case Parallel =>
      ParallelStepInput(s.id, s.branchStepIds.map(_.flatMap(byId.get).map(build(_, byId))), s.name, s.joinMode.getOrElse(JoinMode.All), s.output)
    case Loop =>
      LoopStepInput(s.id, s.over.get, s.bodyStepIds.flatMap(byId.get).map(build(_, byId)), s.name, s.itemVariable.getOrElse("item"), s.output)
    case SubWorkflow =>
      SubWorkflowStepInput(s.id, s.workflowId.get, s.name, s.variables, s.output)
    case Trigger =>
      TriggerStepInput(s.id, s.trigger.get, s.name, s.output)
  }

  /** Render lowering violations as a single recoverable failure message. */
  def violationMessage(errors: List[String]): String =
    "The workflow steps couldn't be compiled:\n- " + errors.mkString("\n- ")
}
