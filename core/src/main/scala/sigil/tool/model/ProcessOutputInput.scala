package sigil.tool.model

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for `process_output` — read accumulated stdout/stderr from
 * a registered subprocess. `sinceCursor` is the previous call's
 * `nextCursor`; pass 0 on the first read. Optional `waitForLines`
 * / `waitForPattern` cause the call to block (briefly) until the
 * subprocess emits enough output, capped by `waitTimeoutMs`
 * (default 0 = return immediately).
 */
case class ProcessOutputInput(handle: String,
                              sinceCursor: Long = 0L,
                              waitForLines: Option[Int] = None,
                              waitForPattern: Option[String] = None,
                              waitTimeoutMs: Long = 0L)
  extends ToolInput derives RW
