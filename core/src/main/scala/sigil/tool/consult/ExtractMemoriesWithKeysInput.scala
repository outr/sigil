package sigil.tool.consult

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Structured extraction payload used by
 * [[sigil.conversation.compression.extract.StandardMemoryExtractor]].
 * Unlike [[ExtractMemoriesInput]] (which surfaces flat fact strings),
 * this variant asks the model for a semantic key per fact so the
 * extractor can route into
 * [[sigil.Sigil.upsertMemoryByKey]] and get versioning / refresh
 * semantics automatically.
 *
 * Each entry is self-contained: a reader seeing the fact alone must
 * still be able to act on it.
 */
case class ExtractedMemory(key: String,
                           label: String,
                           content: String,
                           tags: List[String] = Nil) derives RW

case class ExtractMemoriesWithKeysInput(memories: List[ExtractedMemory]) extends ToolInput derives RW
