package sigil.script

import sigil.conversation.ActiveSkillSlot
import sigil.provider.{Mode, ToolPolicy}
import sigil.tool.ToolName

/**
 * Mode the agent enters when it intends to author a runtime script
 * tool. Bug #59 — making script-authoring a first-class mode rather
 * than a side-effect of any conversation gives the agent three things
 * it didn't have before:
 *
 *   1. A skill (cookbook + best-practices) that teaches the executor's
 *      library surface and idioms — so the agent doesn't have to
 *      guess at Spice / Fabric / Rapid APIs the way it does from
 *      vanilla [[sigil.provider.ConversationMode]].
 *   2. A scoped tool roster ([[ToolPolicy.Active]]) that adds the
 *      script-authoring family — `library_lookup`, `class_signatures`,
 *      `read_source`, `create_script_tool`, `update_script_tool`,
 *      `delete_script_tool`, `list_script_tools` — only while the
 *      agent is in this mode. Outside this mode, those tools aren't
 *      even discoverable; the conversation surface stays uncluttered.
 *   3. Mode discipline that emerges naturally: the agent that wants
 *      to create a tool must first `change_mode("script-authoring")`,
 *      which surfaces the skill that tells it to look up unfamiliar
 *      APIs before writing code. The "1 lookup beats 3 broken
 *      compiles" loop closes by construction.
 *
 * Apps stir this in by mixing [[ScriptSigil]] (which auto-registers
 * the mode and the introspection tools).
 */
case object ScriptAuthoringMode extends Mode {
  override val name: String = "script-authoring"

  override val description: String =
    "Author runtime script tools. Adds library introspection (`library_lookup`, `class_signatures`, " +
      "`read_source`) and the script-tool management surface (`create_script_tool`, " +
      "`update_script_tool`, `delete_script_tool`, `list_script_tools`)."

  override val skill: Option[ActiveSkillSlot] = Some(ActiveSkillSlot(
    name = "script-authoring",
    content =
      """You are authoring a Scala 3 script that will be persisted as a runtime tool.
        |
        |### Hard constraints
        |  - Scala 3 only (the executor uses dotty's REPL ScriptEngine).
        |  - Pre-imported — DO NOT re-import:
        |      fabric.*, fabric.io.{JsonParser, JsonFormatter}, fabric.rw.*,
        |      spice.http.client.HttpClient, spice.http.{HttpRequest, HttpResponse},
        |      spice.net.* (URL case class plus the `url"…"` / `path"…"` / `ip"…"` /
        |        `port"…"` / `email"…"` literal interpolators),
        |      rapid.Task, scala.jdk.CollectionConverters.*
        |  - Forbidden (removed / replaced in Scala 3):
        |      `scala.util.parsing.json`,
        |      `scala.io.Source.fromURL` for HTTP,
        |      `scala.collection.JavaConversions`.
        |  - The script's last expression is its return value. The agent's tool
        |    result will render as that value's `.toString`.
        |
        |### Three Spice / Fabric quirks the cookbook below honors
        |
        |  - `HttpClient.url(_: URL)` requires a typed `spice.net.URL`, NOT a `String`.
        |    Use the `url"…"` interpolator for literals (`url"https://example.com/foo"`)
        |    or `URL.parse("…")` for runtime strings.
        |  - `Content.asString` returns `Task[String]`, NOT `String`. Either chain via
        |    `.flatMap` / `.map`, or `.sync()` at the script boundary to materialize.
        |  - `HttpClient.post` is a no-arg method (sets the HTTP method to POST). To
        |    attach a JSON body, chain `.json(jsonBody)`. For arbitrary content use
        |    `.content(StringContent(...))`.
        |
        |### Cookbook
        |
        |**HTTP GET returning JSON, extract a field:**
        |```
        |val response = HttpClient.url(url"https://random.dog/woof.json").send().sync()
        |val body     = response.content.get.asString.sync()
        |val json     = JsonParser(body)
        |json("url").asString
        |```
        |
        |**HTTP POST with JSON body:**
        |```
        |val body = obj("name" -> str("alice"), "age" -> num(30))
        |val response = HttpClient
        |  .url(url"https://api.example.com/users")
        |  .post
        |  .json(body)
        |  .send()
        |  .sync()
        |response.content.get.asString.sync()
        |```
        |
        |**Render a markdown image (when the user expects to see an image):**
        |```
        |val response = HttpClient.url(url"…/random-image-source").send().sync()
        |val body     = response.content.get.asString.sync()
        |val imageUrl = JsonParser(body)("url").asString
        |s"![Random]($imageUrl)"
        |```
        |
        |**Async chain — stay in `Task` until the script's last line:**
        |```
        |val task: Task[String] = HttpClient.url(url"…").send().flatMap { resp =>
        |  resp.content.get.asString.map { body =>
        |    JsonParser(body)("field").asString
        |  }
        |}
        |task.sync()  // materialize at the boundary
        |```
        |
        |**Dynamic URL (when the URL string isn't a compile-time literal):**
        |```
        |val target: String = args("endpoint").asString
        |val response = HttpClient.url(URL.parse(target)).send().sync()
        |response.content.get.asString.sync()
        |```
        |
        |**Java↔Scala collection bridging (rare; usually you don't need it):**
        |```
        |val javaList: java.util.List[String] = someJavaApi()
        |val scalaList: List[String] = javaList.asScala.toList
        |```
        |
        |### Best practices
        |  - **Look up before writing.** When you'd guess at a method signature,
        |    call `library_lookup(symbol)` first. One round-trip beats one
        |    failed compile + retry.
        |  - **Fail loud, not silent.** If the script can't produce a useful
        |    result, `throw new RuntimeException("clear human-readable reason")`
        |    so the framework surfaces it as an error rather than returning an
        |    empty string. The `error` field of `ScriptResult` is what the agent
        |    (and the user) reads when something goes wrong.
        |  - **Single responsibility.** Scripts that fetch + transform + render
        |    in 5 lines are good. Scripts with conditional branches, loops,
        |    helper objects, and 30+ lines should be split into multiple smaller
        |    tools that the agent composes.
        |  - **No global state.** The executor's REPL persists state across
        |    calls. Scripts that mutate a top-level `var` or rely on prior
        |    bindings are reproducibility hazards. Treat each script body as
        |    self-contained — declare what you need, return a value, exit.
        |  - **Async hygiene.** If you call `.sync()` inside a `Task.map`
        |    chain, you've serialized work that wanted to be parallel. Either
        |    stay in `Task` until the script boundary OR materialize early
        |    and use plain values. Don't mix.
        |  - **No secrets in script source.** Persisted scripts are visible to
        |    every agent in scope. Read secrets via the framework's
        |    `secrets.get("name")` API rather than embedding them as string
        |    literals.
        |  - **Idempotence first.** A script the user might invoke multiple times
        |    should produce the same shape of result (different content is fine).
        |    Avoid scripts that delete, mutate, or have side effects on first call
        |    that other invocations depend on.
        |""".stripMargin
  ))

  /** Adds the script-authoring tool family to the roster. The
    * baseline (respond, no_response, change_mode, stop, find_capability)
    * is preserved by `ToolPolicy.Active`'s semantics — these tools
    * supplement, don't replace.
    *
    * Bug #60 — names are written as [[ToolName]] literals rather than
    * referencing the tool objects' `.name` field. The tool objects
    * carry `modes = Set(ScriptAuthoringMode.id)` in their super-
    * constructor, so referencing them here would create a circular
    * static-init dependency: whichever side loads first re-enters the
    * other's still-running `<clinit>` and reads `MODULE$ == null`,
    * throwing `ExceptionInInitializerError`. Literals break the
    * cycle — this side no longer touches the tool objects, so the
    * tools load lazily on first use against an already-initialised
    * `ScriptAuthoringMode`. */
  override val tools: ToolPolicy = ToolPolicy.Active(List(
    ToolName("library_lookup"),
    ToolName("class_signatures"),
    ToolName("read_source"),
    ToolName("create_script_tool"),
    ToolName("update_script_tool"),
    ToolName("delete_script_tool"),
    ToolName("list_script_tools")
  ))
}
