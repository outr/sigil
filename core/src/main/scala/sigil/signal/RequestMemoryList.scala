package sigil.signal

import fabric.rw.*
import sigil.conversation.MemoryType
import sigil.conversation.MemoryType.given

/**
 * Sigil #292 — client→server [[Notice]]: "send me a list of memories
 * I authored that match these filters." The default
 * [[sigil.Sigil.handleNotice]] arm responds with a
 * [[MemoryListSnapshot]] targeted at the requesting viewer.
 *
 * Mirrors the [[RequestConversationList]] / [[ConversationListSnapshot]]
 * pattern: fire-and-forget, no correlation id. The snapshot type is
 * the binding key on the UI side.
 *
 * Viewer scope: the framework filters to memories whose
 * [[sigil.conversation.ContextMemory.createdBy]] equals the requesting
 * viewer. Apps that want a wider view (admin / shared spaces) override
 * `handleNotice` and run their own query.
 *
 *   - `query`       — substring match against `label` / `summary` /
 *                     `fact` (case-insensitive). `None` returns every
 *                     viewer-owned memory.
 *   - `memoryType`  — narrow to one [[MemoryType]] value
 *                     (`Fact` / `Preference` / `Decision` / ...).
 *   - `pinned`      — narrow to pinned (`Some(true)`) /
 *                     non-pinned (`Some(false)`) only.
 *   - `hasLocation` — when `true`, narrow to memories carrying a
 *                     `Place`. Default `false` (no constraint).
 *   - `limit`       — hard cap on the returned list. Default 100.
 */
case class RequestMemoryList(query: Option[String] = None,
                              memoryType: Option[MemoryType] = None,
                              pinned: Option[Boolean] = None,
                              hasLocation: Boolean = false,
                              limit: Int = 100) extends Notice derives RW
