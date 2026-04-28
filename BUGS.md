# Bugs

Tracking bugs found in Sigil, Spice, Fabric, and related libraries while building a downstream consumer on top of Sigil. Each entry: where it lives, what's wrong, what we observed, suggested fix.

**Status legend:** ⚠️ workaround in place · ❌ open · ✅ fixed.

Numbering preserves history — gaps reflect entries that were filed, fixed, and pruned.

### 7. ❌ LlamaCpp placeholder-user injection breaks Qwen's "system message must be at beginning" rule for greet-on-join

**Where:** `~/projects/open/sigil/core/src/main/scala/sigil/provider/llamacpp/LlamaCppProvider.scala` — `buildBody` placeholder-user injection (added in bug #4 fix) interacts badly with the existing `foldMidArraySystems` pre-processor when the input has only System messages.

**Symptom:** Fresh conversation, `agent.greetsOnJoin = true`, no prior user/assistant turns. llama.cpp returns HTTP 500:

```
Jinja Exception: System message must be at the beginning.
```

The agent's greeting turn fails, no greeting reaches the client.

**Root cause:** the wire payload arrives at Qwen's chat template as `[systemMsg, placeholderUser, System1, System2, ...]` — multiple System messages with the second one not at index 0. Qwen's template walks the messages and raises on the first non-leading System entry.

`foldMidArraySystems` was designed to fold mid-array System content into the *next* non-System message:

```scala
case ProviderMessage.System(content) if !seenNonSystem =>
  out += ProviderMessage.System(content)
case ProviderMessage.System(content) =>
  pending += content
case other =>
  seenNonSystem = true
  out += (if (pending.nonEmpty) prependSystem(pending.toList, other) else other)
```

For a greeting turn, `input.messages` is typically all leading System frames (topic context, role description, etc.) with **no non-System messages at all**. The `if !seenNonSystem` branch fires for every entry, all System frames pass through unchanged, `seenNonSystem` never flips, the trailing-pending fold never triggers. Then `buildBody` prepends `placeholderUser` *before* this list of leading-System frames:

```scala
val withUserAnchor =
  if (rendered.exists(m => m.get("role").exists(_.asString == "user"))) rendered
  else placeholderUserMessage +: rendered      // placeholder at index 0 of withUserAnchor
"messages" -> arr((Vector(systemMsg) ++ withUserAnchor)*)
```

Result: `[systemMsg, placeholderUser, System1, System2, ...]`. Qwen rejects `System1` for not being at index 0.

**Suggested fix (in `buildBody`):** collapse all leading System content from `input.messages` into the framework's `input.system` before building `systemMsg`, so the rendered tail is guaranteed user/assistant only:

```scala
val (extraSystem, nonSystemMessages) = input.messages.span {
  case _: ProviderMessage.System => true
  case _ => false
}
val combinedSystem = (input.system +: extraSystem.collect { case s: ProviderMessage.System => s.content }).filter(_.nonEmpty).mkString("\n\n")
val systemMsg = obj("role" -> str("system"), "content" -> str(combinedSystem))
val rendered = renderMessages(nonSystemMessages)  // foldMidArraySystems still handles any non-leading systems
val withUserAnchor =
  if (rendered.exists(m => m.get("role").exists(_.asString == "user"))) rendered
  else placeholderUserMessage +: rendered
```

Now `withUserAnchor` contains either user-anchored messages or just `[placeholderUser]`; either way, the final array starts `[systemMsg, ...]` with a single System message in front. The existing `foldMidArraySystems` continues to handle the live-conversation case (mid-conversation `TopicChange` System frames after non-System content).

**Sage-side state:** No workaround. Fresh greet-on-join turn fails on every connect against Qwen3.5; the user sees no greeting. WireSmoke happens to push a non-greeting prompt fast enough that some greetings succeed, but the browser path consistently breaks.
