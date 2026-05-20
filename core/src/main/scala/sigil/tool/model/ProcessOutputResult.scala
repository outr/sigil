package sigil.tool.model

import fabric.rw.*

/**
 * Typed result for [[sigil.tool.process.ProcessOutputTool]].
 *
 *   - `handle` — the subprocess handle this read targeted.
 *   - `stdout` / `stderr` — bytes accumulated since `sinceCursor`.
 *   - `sinceCursor` — the cursor the read started from.
 *   - `nextCursor` — the cursor to pass on the next read for a delta.
 *   - `status` — running or exited.
 *   - `exitCode` — the process exit code once `status` is `Exited`.
 *   - `dropped` — `true` when `sinceCursor` predates the ring
 *     buffer's earliest retained byte (the agent missed some output).
 */
case class ProcessOutputResult(handle: String,
                               stdout: String,
                               stderr: String,
                               sinceCursor: Long,
                               nextCursor: Long,
                               status: ProcessRunStatus,
                               exitCode: Option[Int],
                               dropped: Boolean) derives RW
