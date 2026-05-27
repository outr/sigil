# ❌ #288 — Large agent-emitted `tool_use` content stays inline in history forever; need a mirror of `inlineContentThreshold` for the assistant side

**Where:**
- The conversation-replay path that builds the `messages[]` array for each provider request from settled events. Probably `core/src/main/scala/sigil/conversation/compression/StandardContextCurator.scala` or wherever assistant Message events are rendered into `tool_use` content blocks.
- The framework's existing `Sigil.inlineContentThreshold` governs *tool result* externalization (user-side `tool_result` content). There's no symmetric mechanism for assistant-side `tool_use` content.

**What's wrong:** When the agent calls a tool like `write_theme_file` with a 28 KB content body, that 28 KB sits inside an assistant `tool_use.input.content` field. The corresponding tool_result is small (the success message). On the next iteration:

- The tool_result honors `inlineContentThreshold` — if it were 28 KB it would externalize.
- The **agent's own tool_use.input** stays inline at full 28 KB forever, re-shipped on every subsequent provider request for the rest of the conversation.

Concrete trace (Shopkeeper, 2026-05-26):

```
[entry 79] agent emits tool_use:
   write_theme_file {
     path: "sections/corporate-wellness.liquid",
     content: "<27,925 chars of Liquid + schema>"
   }
[entry 80+] every subsequent request includes that full 27,925-char
   content block in the assistant message at message index ~80,
   repeated through to the end of the conversation
[final request size for the turn: 2.05 MB] — the corporate-wellness
   content alone accounts for ~30% of every subsequent request body
```

The asymmetry doesn't make sense in agent-mediated workflows:

- Tool *output* (read_theme_file returns 30 KB of file content): framework
  externalizes via `inlineContentThreshold` because the *agent doesn't need
  to re-see* the full text once it's processed it.
- Tool *input* (write_theme_file ships 30 KB of file content): inline
  forever because the framework treats the agent's emission as
  conversation history that has to be preserved verbatim.

But the same rationale applies. After write_theme_file lands, the agent
doesn't need to re-see the full 28 KB it sent — the conversation history
just needs to record "wrote X to path Y." A reference + summary is
enough for any subsequent reasoning ("I already wrote `sections/X.liquid`;
let me read the current state if I need to modify it").

**Suggested fix shape:**

1. **New override `Sigil.inlineToolUseContentThreshold`** (default same as `inlineContentThreshold`):
   ```scala
   /** Maximum size of an agent-emitted tool_use field value that's
     * shipped inline in the conversation history sent to the provider.
     * Fields larger than this externalize: the conversation event
     * keeps the value persisted; the provider request renders a
     * reference + brief summary ("wrote 27,925 chars to
     * sections/corporate-wellness.liquid"). The agent can re-fetch
     * via `lookup` if it needs the actual bytes back. */
   def inlineToolUseContentThreshold: Long = inlineContentThreshold
   ```

2. **Curator integration.** When rendering an assistant Message's
   tool_use blocks, walk the input fields; for any string-valued field
   over the threshold, replace the value with a reference. The persisted
   event stays whole — only the rendered prompt shrinks.

3. **Which fields to externalize.** Apps should be able to opt fields
   in/out per-tool (e.g., `respond.content` shouldn't externalize — the
   prose is the conversation). Tools opt fields in via a
   `ToolInputField.externalize: Boolean` annotation, or via a method
   on the `Tool` trait:
   ```scala
   override def externalizableInputFields: Set[String] =
     Set("content")  // for write_theme_file
   ```

4. **Reference format the agent can re-fetch.** Mirror the existing
   `lookup` flow. The externalized field becomes something like:
   ```json
   { "type": "tool_use", "name": "write_theme_file",
     "input": { "path": "sections/X.liquid",
                "content": { "$ref": "storage://tu/abc123", "size": 27925 } } }
   ```
   The agent sees the reference, knows the size, and can `lookup` if
   needed.

**Why this matters beyond Shopkeeper:**

Any agent that emits large structured content via tool calls hits this:

- Coding agents writing files (`write_file` with full file contents)
- Data agents shipping SQL queries with embedded value lists
- Note-taking agents writing documents
- Image generators with detailed text prompts

The current behavior — "agent-emitted history is sacred and inline forever"
— scales linearly with the number of large writes in a session,
multiplied by the iterations after each write.

**Cross-refs:**
- #283 / #284 — rate-limit guard. Externalizing large tool_use content
  drops per-iteration size, making the guard's threshold rarely needed.
- #285 — intra-turn compaction. #288 is the missing piece on the
  assistant side; together they cover both directions of the bloat.
- #286 / #287 — tool roster narrowing. Orthogonal — that's the fixed
  per-request cost; this is the growing per-iteration cost.

**Diagnostic data:**
- Wire log:
  `/home/mhicks/projects/clients/outr/shopkeeper/backend/logs/shopkeeper-wire.jsonl`
- Entry 79 (write_theme_file response, 689 KB on the wire — the inflated
  JSON-escaped form of 27,925 chars of Liquid content). Entries 80+ all
  include that content in their `messages[]` array.
