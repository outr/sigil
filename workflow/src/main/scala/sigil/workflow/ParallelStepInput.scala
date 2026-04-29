package sigil.workflow

import fabric.rw.*

/**
 * Step that forks into N branches and joins on either all or any.
 * Each branch is a `List[WorkflowStepInput]` — a mini-workflow
 * executed in parallel.
 *
 * `joinMode = "all"` waits for every branch to complete; the
 * outputs are collected into a list under `output`. `joinMode =
 * "any"` returns when the first branch completes (the rest are
 * cancelled); the winner's output lands at `output`.
 *
 * Branches that fail follow each branch step's individual
 * `continueOnError` / `retryCount` settings — the parallel step
 * itself fails only if the join mode demands it.
 */
case class ParallelStepInput(id: String,
                             name: String = "",
                             branches: List[List[WorkflowStepInput]],
                             joinMode: String = "all",
                             output: String = "") extends WorkflowStepInput derives RW
