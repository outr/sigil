package sigil.tool

import fabric.rw.*

/**
 * Typed wrapper around a tool's name. Used everywhere a tool is
 * referenced by identity: [[Tool.schema]], [[ToolFinder.byName]],
 * [[sigil.event.ToolInvoke.toolName]], [[sigil.conversation.ContextFrame.ToolCall.toolName]],
 * [[sigil.participant.AgentParticipant.toolNames]], and the per-
 * participant `recentTools` / `suggestedTools` projections.
 *
 * AnyVal case class — zero runtime overhead, serializes as a bare
 * string through fabric's poly machinery (same pattern as
 * [[sigil.provider.CallId]]).
 *
 * Type-only discrimination: the framework can't prove the referenced
 * tool exists at compile time (agents persist toolNames as strings in
 * the DB; deserialization rehydrates them blind). Runtime validation
 * stays at `ToolFinder.byName`, which returns `Option` — callers handle
 * the miss case.
 */
case class ToolName(value: String) extends AnyVal derives RW
