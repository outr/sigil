# ❌ #286 — Tool roster doesn't narrow to the active sub-task; fixed per-request cost scales with total tool count, not active need

**Where:**
- `core/src/main/scala/sigil/tool/ToolPolicy.scala` — the policy ADT (`ActiveOnly(names)`, `Active(names)`, etc.) that consumers configure.
- Wherever Sigil renders the active tool roster into the provider request (probably `provider/anthropic/AnthropicProvider.scala`'s `renderTools`).
- Mode system in `core/src/main/scala/sigil/mode/` — already provides per-mode tool affinity but isn't dynamically scoped to the current sub-task within a mode.

**What's wrong:** Consumers like Shopkeeper currently register all their tools as `ToolPolicy.ActiveOnly(toolNames)` with the full roster (45 tools). Every request to the provider includes the full tool-definition JSON for all 45 — ~10 K tokens per request — regardless of what sub-task the agent is currently doing.

At any moment during a Shopkeeper session, the agent is doing exactly one of:
- editing theme files (`read_theme_file`, `write_theme_file`, `list_theme_files`, `liquid_reference` — 4 tools)
- browsing a URL (`browser_navigate`, `browser_save_html`, `browser_screenshot`, `browser_xpath_query` — 4 tools)
- managing pages/products (`create_page`, `update_page`, `list_pages`, `create_product`, `update_product`, `list_products` — 6 tools)
- previewing / verifying (`preview_theme`, `view_theme_image` — 2 tools)
- responding (`respond`, `respond_options`, `stop` — 3 tools)

Most turns touch one of those families plus respond. The other ~35 tools' schemas are dead weight in the prompt for that turn. With 32 iterations in a heavy turn, that's 10 K tokens × 32 = 320 K tokens of *unused tool definitions* paid in a single user prompt's lifetime. Even cached, the rate limit budget still ticks against them.

Modes partly address this (`Sigil.modeFor(...)` returns a tool subset), but:

1. Modes are coarse — typically one mode active per turn, not one per sub-task within a turn.
2. Modes require the consumer to *predict* which sub-tasks will happen — they're a configuration knob, not a dynamic narrowing.
3. The agent doesn't get a "switch mode mid-turn" affordance that's cheap enough to use casually — `change_mode` is a deliberate user-visible step, not a "I'm done browsing, swap rosters" reflex.

**Suggested fix sketch — dynamic per-iteration roster narrowing:**

1. **Tool-family declarations.** Let consumers tag tools with families:
   ```scala
   ToolPolicy.Families(Map(
     "browser"   -> Set("browser_navigate","browser_save_html",…),
     "shopify"   -> Set("read_theme_file","write_theme_file","create_page",…),
     "preview"   -> Set("preview_theme","view_theme_image"),
     "respond"   -> Set("respond","respond_options","stop")
   ))
   ```
2. **Active-family inference.** Sigil tracks "which families did the last K iterations touch?" Default K = 5. Only those families' tools (plus `respond` always) ship in the next request.
3. **Re-discovery on miss.** When the agent emits a `tool_use` for a tool not in the current narrowed roster, the provider gets back `invalid_tool` from Anthropic (or Sigil intercepts pre-send). Sigil re-widens to the full roster for the NEXT iteration so the agent can find the tool, runs `find_capability` if needed, then re-narrows. The agent never sees a hard rejection — just a one-turn detour.
4. **Family hints in `respond`.** When the agent emits `respond(content, expectedNextAction = "browse")`, Sigil pre-narrows to the browser family for the user's next prompt. The optional hint is plain text the agent can drop.

**Why I don't propose "just use Modes more":**

Modes work great for *user-driven mode shifts* — "switch to coding mode, switch to research mode." They don't work for *agent-internal* narrowing during a single user prompt, because the agent is in the middle of doing work and can't pause to issue `change_mode` between every sub-task without dumping that operation into the conversation visibility. Mode switches are too heavyweight for "I'm done reading files, I'm going to write now."

Dynamic narrowing IS the mode mechanism applied finer-grained, inferred from observed tool use, without the consumer having to think about it.

**Why this matters beyond Shopkeeper:**

Any app with > 20 tools hits this. Coding agents (file IO + shell + LSP + git + dependency mgmt) typically have 30-60 tools. Data agents (SQL + warehouse + viz + notebook + memory) similar. Each pays ~10-20 K tokens per request in dead-weight schemas. Multiply by long turns (#285) and the constant becomes the dominant cost.

**Cross-refs:**
- #285 — mid-turn compression. #286 reduces the FIXED per-request cost (tool schemas); #285 reduces the GROWING per-request cost (history). Both are needed; they compose.
- #283 / #284 — pre-flight guard + Anthropic provider auto-populating `inputTokensPerMinute`. #286 means the guard fires later (smaller requests = more headroom).

**Diagnostic data:**
- Wire log showing 45 tool schemas re-sent on every request in a 78-call session:
  `/home/mhicks/projects/clients/outr/shopkeeper/backend/logs/shopkeeper-wire.jsonl`
- Each request's `tools` array is ~36-37 K characters (10 K+ tokens) of schemas the active sub-task doesn't reference.
