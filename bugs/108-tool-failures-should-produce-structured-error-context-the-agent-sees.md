# ❌ #108 — Tool failures should produce structured `ErrorContext` (exception class + message + stack head + classification) so the agent can learn from them and report bugs

**Where:** The tool-result envelope in
`core/src/main/scala/sigil/event/ToolResults.scala` and every
tool's `executeTyped` error-handling path (currently each tool
hand-rolls a string-only failure response, if any).

**What's wrong:** When a tool fails today, two things happen:

  1. **The exception escapes the Stream and crashes
     `runAgentLoop`.** Per #106 (`bsp_test`), #107 (`grep`),
     and others — exception not caught at the tool boundary,
     framework dies. Conversation broken.

  2. **Or the tool produces a flat string error.** The agent
     sees `"bsp_test failed: key not found"` as an opaque
     `Failure`-outcome payload. No exception class, no stack
     trace, no indication of whether this is a transient
     issue ("retry"), a bug ("file feedback to the framework
     team"), or a user error ("try a different argument").

Either way the agent can't:

  - Recognise "this looks like a bug, the framework should
    know about it" vs. "this is a normal user-input error,
    fix the input"
  - Tell the user *"I hit a `MalformedInputException` on a
    binary file in `.bloop/` — that's a tool-side bug; want
    me to file feedback?"*
  - Decide between retry, alternative tool, and user
    notification based on the failure shape

The agent runs blind on what went wrong. The user sees the
exception in the backend log but the agent doesn't know about
it.

**Suggested fix:** Add a structured `ErrorContext` payload
on `ToolResults` that the framework auto-fills whenever a
tool fails:

```scala
case class ErrorContext(
  classification: ErrorClassification,
  exceptionClass: Option[String],          // e.g., "java.nio.charset.MalformedInputException"
  message: String,
  stackHead: List[String],                 // top 5-10 frames as text — ENOUGH context, not raw bytes
  suggestion: Option[String],              // tool-specified hint: "retry with smaller input" etc.
  framework_bug_likelihood: Double         // 0.0–1.0 heuristic: classification == FrameworkBug → 1.0, etc.
)

enum ErrorClassification {
  case UserInputError      // bad arguments, missing path, etc.
  case TransientError      // network blip, rate limit, retry-likely-to-help
  case ResourceExhausted   // OOM, disk full, file too large
  case FrameworkBug        // exceptions that look like framework-side defects (NoSuchElement,
                           //   MalformedInput, NPE on internal data, etc.)
  case ProviderError       // upstream LLM/API returned an error
  case Unknown
}

case class ToolResults(
  ...,
  errorContext: Option[ErrorContext] = None  // populated when outcome = Failure
)
```

The framework's tool-execution wrapper auto-classifies
common exceptions:

```scala
def classifyException(t: Throwable): ErrorContext = t match {
  case _: MalformedInputException        => ErrorContext(FrameworkBug,    Some(t.getClass.getName), t.getMessage, stackHead(t), Some("file may be binary or non-UTF-8 encoded"), 0.9)
  case _: NoSuchElementException         => ErrorContext(FrameworkBug,    Some(t.getClass.getName), t.getMessage, stackHead(t), None, 0.85)
  case _: java.nio.file.NoSuchFileException => ErrorContext(UserInputError, ..., None, 0.0)
  case _: java.net.SocketTimeoutException => ErrorContext(TransientError,  ..., Some("retry — likely transient"), 0.1)
  case _: OutOfMemoryError              => ErrorContext(ResourceExhausted, ..., None, 0.3)
  case _                                 => ErrorContext(Unknown,          Some(t.getClass.getName), t.getMessage, stackHead(t), None, 0.5)
}
```

Tools can override the auto-classification when they have
domain knowledge (e.g., `bsp_test` knows that a
`NoSuchElementException` from sbt's task lookup means
"target doesn't support testing — user picked the wrong
target," which is a `UserInputError` not a `FrameworkBug`).

### Agent prompt addition

Update the system prompt with a brief paragraph about
handling `ErrorContext`:

> When a tool returns a `Failure` outcome with an
> `errorContext`, read its `classification` to decide:
>   - `UserInputError` — fix the args and retry, or explain
>     to the user what input shape is needed.
>   - `TransientError` — retry once before giving up.
>   - `FrameworkBug` (high `framework_bug_likelihood`) —
>     surface to the user with the exception class + message
>     and ask if they want this filed as feedback. Don't
>     keep retrying.
>   - `ProviderError` — report the upstream issue verbatim.
>   - `Unknown` — explain what you tried and what the error
>     said; defer to the user on next steps.

This turns failures into actionable signal for the agent.
It can distinguish "I called this wrong" from "the framework
broke" from "the network blipped" — and respond accordingly.

### Test

```scala
class StructuredErrorContextSpec extends AbstractToolSpec {

  test("MalformedInputException auto-classifies as FrameworkBug with high likelihood") {
    val tool = grepToolWithBinaryFile()
    val result = tool.execute(...).sync()
    assert(result.outcome == Failure)
    assert(result.errorContext.exists { ec =>
      ec.classification == FrameworkBug &&
      ec.framework_bug_likelihood >= 0.85 &&
      ec.exceptionClass.contains("MalformedInputException")
    })
  }

  test("NoSuchFileException auto-classifies as UserInputError") {
    val result = readFileTool.execute(ReadFileInput("/nonexistent/path")).sync()
    assert(result.errorContext.exists(_.classification == UserInputError))
    // framework_bug_likelihood should be 0.0 — user gave bad path, not a bug
  }

  test("Tool override of auto-classification") {
    // bspTest with wrong-target argument: framework would auto-classify as FrameworkBug
    // (NoSuchElementException), but the tool knows it's a UserInputError shape
    val result = bspTestTool.execute(BspTestInput(targets = List("nonexistent-target"))).sync()
    assert(result.errorContext.exists(_.classification == UserInputError))
  }
}
```

Pairs naturally with #106 + #107 (exception envelopes at the
tool boundary): once the exception is caught, give the agent
a structured story about what happened so it can act on it
intelligently. The agent becomes capable of recognising
and reporting framework bugs as feedback to whoever maintains
the tool — same role this human conversation has been doing
for the past several days.
