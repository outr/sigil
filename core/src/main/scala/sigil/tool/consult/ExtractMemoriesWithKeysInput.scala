package sigil.tool.consult

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Structured extraction payload used by both the per-turn
 * [[sigil.conversation.compression.extract.StandardMemoryExtractor]]
 * and the compression-time
 * [[sigil.conversation.compression.MemoryContextCompressor]]. Each
 * entry asks the model for an optional semantic `key`: when supplied,
 * the framework routes into [[sigil.Sigil.upsertMemoryByKey]] so
 * versioning / refresh semantics happen automatically; when omitted,
 * the entry persists as a new memory record.
 *
 * Each entry is self-contained: a reader seeing the fact alone must
 * still be able to act on it.
 */
case class ExtractedMemory(content: String,
                           label: String,
                           key: Option[String] = None,
                           tags: List[String] = Nil) derives RW

case class ExtractMemoriesWithKeysInput(memories: List[ExtractedMemory]) extends ToolInput derives RW
