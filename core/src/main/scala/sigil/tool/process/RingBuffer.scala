package sigil.tool.process

import java.util.concurrent.locks.ReentrantLock

/**
 * Bounded byte buffer with a monotonic cursor. Bytes appended past
 * `maxBytes` push out the front, so cursors older than
 * `earliestRetained` see a `dropped = true` signal in
 * [[readSince]]. `nextCursor` is total bytes ever written.
 */
final class RingBuffer(maxBytes: Int) {
  private val lock = new ReentrantLock()
  private val builder = new StringBuilder
  private var written = 0L
  private var dropped = 0L

  def append(text: String): Unit = {
    if (text.isEmpty) return
    lock.lock()
    try {
      builder.append(text)
      written += text.length
      val overflow = builder.length - maxBytes
      if (overflow > 0) {
        builder.delete(0, overflow)
        dropped += overflow
      }
    } finally lock.unlock()
  }

  /**
   * Read bytes accumulated since `cursor`. Returns `(text, newCursor, lost)`
   * where `lost` indicates the requested cursor predated the earliest
   * retained byte (agent missed some output).
   */
  def readSince(cursor: Long): (String, Long, Boolean) = {
    lock.lock()
    try {
      val effective = math.max(cursor, dropped)
      val from = (effective - dropped).toInt
      val text = if (from >= builder.length) "" else builder.substring(from)
      (text, written, cursor < dropped)
    } finally lock.unlock()
  }

  def total: Long = {
    lock.lock()
    try written
    finally lock.unlock()
  }
}
