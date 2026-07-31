package spec

import fabric.*
import fabric.rw.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import rapid.Task
import sigil.db.Model
import sigil.provider.{
  ConversationMode,
  GenerationSettings,
  MessageContent,
  Provider,
  ProviderCall,
  ProviderEvent,
  ProviderMessage,
  ProviderStreamException,
  SchemaDialect,
  StrictSchema,
  ToolChoice
}
import sigil.tool.ToolContext
import sigil.tool.{
  DiscoverySpec,
  Effect,
  Freshness,
  JsonInput,
  Resolution,
  TextToolOutput,
  Tool,
  ToolIO,
  ToolInput,
  ToolName,
  ToolProfile,
  ToolResult,
  ToolRoster,
  ToolSpec
}

/** Corpus input — optionals alongside a required field. The strict
  * dialect must widen the optionals to nullable and require every
  * property; lenient dialects must leave `required` alone. */
case class ConformanceOptionalsInput(title: String,
                                     note: Option[String] = None,
                                     count: Option[Int] = None) extends ToolInput derives RW

/** Corpus input — constrained string fields. Grammar-only keywords
  * (`pattern`, `minLength`) appear in the canonical schema; every
  * dialect except Identity strips them from the wire. */
case class ConformanceConstrainedInput(@pattern("^[a-z][a-z0-9-]*$") slug: String,
                                       @minLength(1) label: String) extends ToolInput derives RW

/** Corpus union — a mixed sealed hierarchy (payload case + singletons)
  * emitted as discriminated `oneOf` branches. */
sealed trait ConformanceShape derives RW
case class ConformanceBox(side: Int) extends ConformanceShape
case object ConformanceDot extends ConformanceShape

/** Corpus input — union-typed field. Optional by design: the
  * schema-ergonomics lint forbids a REQUIRED union with a
  * payload-requiring variant (unfillable for models), so the corpus
  * carries the union in the sanctioned optional-advanced-form shape. */
case class ConformanceUnionInput(label: String, shape: Option[ConformanceShape] = None) extends ToolInput derives RW

/**
 * Conformance matrix every shipped [[Provider]] extends. Four pins:
 *
 *   1. **Dialect round-trip** over a canonical tool-input corpus —
 *      optionals, unions, `DefType.Json` fields, constrained strings —
 *      asserting the emitted wire-schema shape appropriate to the
 *      provider's declared [[SchemaDialect]].
 *   2. **Streaming event order** — a canned tool-call turn must arrive
 *      as deltas → completes → usage → done.
 *   3. **Mid-stream error surfacing** — an inline error on a 200-OK
 *      stream must surface (a thrown [[ProviderStreamException]] or an
 *      emitted [[ProviderEvent.Error]]), never be silently dropped.
 *   4. **ToolChoice rendering** — every mode renders a valid body, and
 *      a forced call over an open-JSON tool never ships `strict: true`
 *      (the closed-object requirement can't hold).
 *
 * New providers inherit the matrix; the weakest backend is the default
 * test target instead of the strongest.
 */
trait AbstractProviderConformanceSpec extends AnyWordSpec with Matchers {

  TestSigil.initFor(getClass.getSimpleName)

  /** The provider under test. Construction must be network-free. */
  protected def providerInstance: Provider

  /** Model id for rendered bodies — registered in TestSigil's cache. */
  protected def modelId: Id[Model]

  /** Parse this wire's canonical tool-call turn (some assistant content,
    * one complete tool call, an authoritative usage block, terminal
    * marker) into the ProviderEvent sequence the orchestrator would
    * see. */
  protected def toolTurnEvents: Vector[ProviderEvent]

  /** Parse this wire's canonical mid-stream inline-error fixture.
    * `Left` when the parser throws (the standard
    * [[ProviderStreamException]] path); `Right` with the emitted events
    * when the provider surfaces the error as an event instead. */
  protected def midStreamErrorOutcome: Either[Throwable, Vector[ProviderEvent]]

  protected def dialect: SchemaDialect = providerInstance.schemaDialect

  // ---- corpus tools ----

  protected final class CorpusTool[I <: ToolInput, O <: sigil.tool.ToolOutput](
    toolName: String,
    toolIO: ToolIO[I, O],
    result: O
  ) extends Tool {
    type Input = I
    type Output = O
    val io: ToolIO[I, O] = toolIO
    val spec: ToolSpec = ToolSpec(
      name = ToolName.parse(toolName).fold(sys.error, identity),
      description = s"Conformance corpus tool $toolName.",
      profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Volatile)),
      discovery = DiscoverySpec(keywords = Set("conformance", toolName))
    )
    protected def resolve: Resolution[I, O] = Resolution.Simple((_, _) => Task.pure(result))
  }

  protected lazy val optionalsTool: Tool =
    new CorpusTool("conformance_optionals", ToolIO.derived[ConformanceOptionalsInput, TextToolOutput], TextToolOutput("ok"))
  protected lazy val constrainedTool: Tool =
    new CorpusTool("conformance_constrained", ToolIO.derived[ConformanceConstrainedInput, TextToolOutput], TextToolOutput("ok"))
  protected lazy val unionTool: Tool =
    new CorpusTool("conformance_union", ToolIO.derived[ConformanceUnionInput, TextToolOutput], TextToolOutput("ok"))
  protected lazy val openJsonTool: Tool =
    new CorpusTool("conformance_open_json", ToolIO.derived[JsonInput, TextToolOutput], TextToolOutput("ok"))

  protected def corpus: Vector[Tool] = Vector(optionalsTool, constrainedTool, unionTool, openJsonTool)

  // ---- helpers ----

  private def keysIn(json: Json): Set[String] = json match {
    case Obj(map)      => map.keySet.toSet ++ map.values.flatMap(keysIn)
    case Arr(items, _) => items.flatMap(keysIn).toSet
    case _             => Set.empty
  }

  private def objectNodes(json: Json): Vector[Map[String, Json]] = json match {
    case Obj(map)      => Vector(map.toMap) ++ map.values.flatMap(objectNodes)
    case Arr(items, _) => items.flatMap(objectNodes)
    case _             => Vector.empty
  }

  protected def wireBody(toolChoice: ToolChoice, tools: Vector[Tool]): String =
    providerInstance.httpRequestFor(callWith(toolChoice, tools)).sync().content match {
      case Some(c: spice.http.content.StringContent) => c.value
      case _                                         => ""
    }

  private def callWith(toolChoice: ToolChoice, tools: Vector[Tool]): ProviderCall = ProviderCall(
    model = TestSigil.testModel(modelId),
    system = "conformance system",
    messages = Vector(ProviderMessage.User(Vector(MessageContent.Text("conformance turn")))),
    roster = ToolRoster(tools),
    builtInTools = Set.empty,
    toolChoice = toolChoice,
    generationSettings = GenerationSettings(),
    currentMode = ConversationMode
  )

  // ---- pin 1: dialect round-trip over the corpus ----

  s"${getClass.getSimpleName} dialect (${providerInstance.schemaDialect.name})" should {

    "transform every corpus schema without throwing" in {
      corpus.foreach { t =>
        val transformed = dialect(t)
        transformed should not be Null
      }
    }

    "never engage strict for the open-JSON corpus tool" in {
      dialect.strictForTool(openJsonTool) shouldBe false
    }

    "strip grammar-only keywords from constrained strings unless the dialect is Identity" in {
      val transformed = dialect(constrainedTool)
      val keys = keysIn(transformed)
      dialect match {
        case SchemaDialect.Identity =>
          keys should contain("pattern")
          keys should contain("minLength")
        case _ =>
          (keys & StrictSchema.UnsupportedKeys) shouldBe empty
      }
    }

    "shape the optionals corpus per the declared dialect" in {
      val canonical = optionalsTool.wireSurface.schema
      val transformed = dialect(optionalsTool)
      val canonicalRequired = canonical("required").asVector.map(_.asString).toSet
      dialect match {
        case SchemaDialect.OpenAIStrict =>
          // Strict widens: every property required, optionals nullable,
          // closed objects everywhere.
          val properties = transformed("properties").asObj.value.keys.toSet
          val required = transformed("required").asVector.map(_.asString).toSet
          withClue(s"strict schema must require every property; missing: ${properties -- required}: ") {
            properties.subsetOf(required) shouldBe true
          }
          transformed("additionalProperties").asBoolean shouldBe false
        case _ =>
          // Lenient dialects keep the canonical required set — optional
          // stays optional on the wire.
          transformed("required").asVector.map(_.asString).toSet shouldBe canonicalRequired
      }
    }

    "shape the union corpus per the declared dialect" in {
      val transformed = dialect(unionTool)
      val keys = keysIn(transformed)
      dialect match {
        case SchemaDialect.OpenAIStrict =>
          // Strict rejects oneOf; the dialect converts to anyOf.
          keys should not contain "oneOf"
          keys should contain("anyOf")
        case SchemaDialect.Gemini =>
          // Gemini rejects const and additionalProperties (any value).
          keys should not contain "const"
          keys should not contain "additionalProperties"
        case _ =>
          keys should contain("oneOf")
      }
    }

    "leave the canonical schema untouched under Identity" in {
      if (dialect == SchemaDialect.Identity)
        corpus.foreach(t => dialect(t) shouldBe t.wireSurface.schema)
      else succeed
    }
  }

  // ---- pins 2 + 3: streaming event order and mid-stream errors ----

  s"${getClass.getSimpleName} streaming" should {

    "order a tool-call turn as deltas -> completes -> usage -> done" in {
      val events = toolTurnEvents
      val kinds = events.map {
        case _: ProviderEvent.ToolCallComplete => "complete"
        case ProviderEvent.Usage(u) if !u.isEstimated => "usage"
        case _: ProviderEvent.Done => "done"
        case _ => "other"
      }
      val completeIdx = kinds.indexOf("complete")
      val usageIdx = kinds.indexOf("usage")
      val doneIdx = kinds.lastIndexOf("done")
      withClue(s"event order: ${kinds.mkString(" -> ")}: ") {
        completeIdx should be >= 0
        usageIdx should be > completeIdx
        doneIdx should be > usageIdx
        doneIdx shouldBe (events.size - 1)
      }
    }

    "surface a mid-stream inline error instead of dropping it" in {
      midStreamErrorOutcome match {
        case Left(t) =>
          t shouldBe a[ProviderStreamException]
        case Right(events) =>
          withClue(s"no error surfaced from mid-stream failure; events: $events: ") {
            events.collect { case e: ProviderEvent.Error => e } should not be empty
          }
      }
    }
  }

  // ---- pin 4: ToolChoice rendering ----

  s"${getClass.getSimpleName} tool-choice rendering" should {

    "render a valid body for ToolChoice.Auto" in {
      val body = wireBody(ToolChoice.Auto, corpus)
      noException should be thrownBy fabric.io.JsonParser(body)
      corpus.foreach(t => body should include(t.name.value))
    }

    "render a valid body for ToolChoice.Required" in {
      val body = wireBody(ToolChoice.Required, corpus)
      noException should be thrownBy fabric.io.JsonParser(body)
      corpus.foreach(t => body should include(t.name.value))
    }

    "render a valid body for ToolChoice.Specific" in {
      val body = wireBody(ToolChoice.Specific(optionalsTool.name), corpus)
      noException should be thrownBy fabric.io.JsonParser(body)
      body should include(optionalsTool.name.value)
    }

    "render a valid body for ToolChoice.None" in {
      val body = wireBody(ToolChoice.None, corpus)
      noException should be thrownBy fabric.io.JsonParser(body)
    }

    "never ship strict:true when forcing a call over an open-JSON-only roster" in {
      val roster = Vector(openJsonTool)
      List(
        ToolChoice.Auto,
        ToolChoice.Required,
        ToolChoice.Specific(openJsonTool.name)
      ).foreach { choice =>
        val body = wireBody(choice, roster)
        val parsed = fabric.io.JsonParser(body)
        val offenders = objectNodes(parsed).filter(_.get("strict").contains(bool(true)))
        withClue(s"$choice rendered strict:true over an open-JSON schema: ") {
          offenders shouldBe empty
        }
      }
    }
  }
}
