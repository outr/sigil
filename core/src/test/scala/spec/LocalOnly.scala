package spec

/**
 * ScalaTest tag for tests that must NOT run in CI — typically because
 * they rely on resources that aren't available there:
 *
 *   - A live LLM service the runner can't reliably reach (e.g. a local
 *     llama.cpp server, or the public `llama.voidcraft.ai` instance
 *     under load).
 *   - A headless Chrome / chromedriver installation.
 *   - Multi-turn agent loops whose runtime is sensitive to upstream
 *     latency (reasoning models on slow servers, multi-tool browser
 *     orchestration, etc.).
 *
 * The CI workflow runs `sbt "testOnly -- -l spec.LocalOnly"` which
 * passes `-l spec.LocalOnly` to ScalaTest's runner, telling it to
 * exclude any test tagged with this name. Local development runs the
 * full suite by default — these tests still execute when the dev has
 * the required services available.
 *
 * Lives in `core` test sources so every module (browser, workflow,
 * benchmark, …) can reach it through the standard `core %
 * "test->test"` test-classpath dependency.
 */
object LocalOnly extends org.scalatest.Tag("spec.LocalOnly")
