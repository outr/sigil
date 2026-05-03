package sigil.workflow

import fabric.rw.*
import strider.step.JoinMode

/**
 * Step that forks into N branches and joins on either all or any.
 * Each branch is a `List[WorkflowStepInput]` — a mini-workflow
 * executed in parallel.
 *
 * `joinMode = JoinMode.All` waits for every branch to complete; the
 * outputs are collected into a list under `output`.
 * `joinMode = JoinMode.Any` returns when the first branch completes
 * (the rest are cancelled); the winner's output lands at `output`.
 *
 * Branches that fail follow each branch step's individual
 * `continueOnError` / `retryCount` settings — the parallel step
 * itself fails only if the join mode demands it.
 */
case class ParallelStepInput(id: String,
                             branches: List[List[WorkflowStepInput]],
                             name: Option[String] = None,
                             joinMode: JoinMode = JoinMode.All,
                             output: Option[String] = None) extends WorkflowStepInput derives RW
