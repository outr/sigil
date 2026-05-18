package spec

import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{Conversation, Topic, TopicEntry, TurnInput}
import sigil.db.{Model, ModelArchitecture, ModelDefaultParameters, ModelLinks, ModelPricing, ModelTopProvider}
import sigil.event.{Message, MessageRole}
import sigil.tool.model.ResponseContent
import sigil.tool.provider.{
  ModelAlias, ModelResolution, ModelResolutionResult,
  PinModelInput, PinModelTool, SwitchModelInput, SwitchModelTool
}

/**
 * Coverage for [[ModelAlias]] / [[ModelResolution]]: friendly aliases
 * map to real registered ids; unresolvable inputs refuse with a
 * helpful guidance message rather than silently routing through the
 * provider-prefix fallback and stamping phantom modelIds.
 *
 * The two ingest tools — [[PinModelTool]] and [[SwitchModelTool]] —
 * both use the resolver, so any change in resolution semantics
 * surfaces in both at once.
 */
class ModelAliasResolutionSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  // Seed the registry with one model per known provider key plus a
  // local llamacpp entry so alias resolution has concrete targets.
  private val openaiModel = stub("openai", "gpt-5.5")
  private val anthropicModel = stub("anthropic", "claude-opus-4-7")
  private val googleModel = stub("google", "gemini-2.5-pro")
  private val deepseekModel = stub("deepseek", "v4")
  private val localModel = stub("llamacpp", "qwen3.5-9b")

  TestSigil.cache.replace(List(openaiModel, anthropicModel, googleModel, deepseekModel, localModel)).sync()

  // Switch_model + pin_model assignment paths run authz through
  // `accessibleSpaces` — install a resolver granting GlobalSpace so
  // the spec exercises the full save + assign flow.
  TestSigil.setAccessibleSpaces((_: List[sigil.participant.ParticipantId]) =>
    Task.pure(Set[sigil.SpaceId](sigil.GlobalSpace)))

  private def stub(provider: String, model: String): Model = Model(
    canonicalSlug = s"$provider/$model",
    huggingFaceId = "",
    name = s"$provider/$model",
    description = s"$provider $model — fixture",
    contextLength = 32_000,
    architecture = ModelArchitecture("text->text", List("text"), List("text"), "Unknown", None),
    pricing = ModelPricing(BigDecimal(0), BigDecimal(0), None, None),
    topProvider = ModelTopProvider(Some(32_000L), Some(8_192L), false),
    perRequestLimits = None,
    supportedParameters = Set("temperature"),
    defaultParameters = ModelDefaultParameters(),
    knowledgeCutoff = None,
    expirationDate = None,
    links = ModelLinks(""),
    created = Timestamp(),
    modified = Timestamp(),
    _id = Model.id(provider, model)
  )

  private def freshConversation(): Task[Conversation] = {
    val convId = Conversation.id(s"alias-${rapid.Unique()}")
    val topic = Topic(
      conversationId = convId,
      label = "spec",
      summary = "spec",
      createdBy = TestUser
    )
    val conv = Conversation(
      topics = List(TopicEntry(topic._id, topic.label, topic.summary)),
      _id = convId
    )
    for {
      _ <- TestSigil.withDB(_.topics.transaction(_.upsert(topic))).unit
      _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv))).unit
    } yield conv
  }

  private def ctx(conv: Conversation): TurnContext =
    TurnContext(
      sigil = TestSigil,
      chain = List(TestUser, TestAgent),
      conversation = conv,
      turnInput = TurnInput(conversationId = conv.id)
    )

  private def loadConv(conv: Conversation): Task[Conversation] =
    TestSigil.withDB(_.conversations.transaction(_.get(conv.id))).map(_.getOrElse(conv))

  private def replyText(events: List[sigil.event.Event]): String =
    events.collect {
      case m: Message =>
        m.content.collect {
          case ResponseContent.Text(t) => t
          case ResponseContent.Markdown(t) => t
        }.mkString("\n")
    }.mkString("\n")

  // --- ModelAlias.resolve ---------------------------------------------------

  "ModelAlias.resolve" should {

    "map 'local' / 'llama' / 'llamacpp' to a llamacpp/* registered id" in {
      for {
        conv <- freshConversation()
        ctx0 = ctx(conv)
        a <- ModelAlias.resolve("local", ctx0)
        b <- ModelAlias.resolve("llama", ctx0)
        c <- ModelAlias.resolve("LLAMACPP", ctx0)
      } yield {
        a shouldBe Some(localModel._id)
        b shouldBe Some(localModel._id)
        c shouldBe Some(localModel._id)
      }
    }

    "map 'openai' / 'gpt' to an openai/* id" in {
      for {
        conv <- freshConversation()
        ctx0 = ctx(conv)
        a <- ModelAlias.resolve("openai", ctx0)
        b <- ModelAlias.resolve("gpt", ctx0)
      } yield {
        a shouldBe Some(openaiModel._id)
        b shouldBe Some(openaiModel._id)
      }
    }

    "map 'anthropic' / 'claude' / 'google' / 'gemini' / 'deepseek'" in {
      for {
        conv <- freshConversation()
        ctx0 = ctx(conv)
        anthropic <- ModelAlias.resolve("anthropic", ctx0)
        claude <- ModelAlias.resolve("claude", ctx0)
        google <- ModelAlias.resolve("google", ctx0)
        gemini <- ModelAlias.resolve("gemini", ctx0)
        deepseek <- ModelAlias.resolve("deepseek", ctx0)
      } yield {
        anthropic shouldBe Some(anthropicModel._id)
        claude shouldBe Some(anthropicModel._id)
        google shouldBe Some(googleModel._id)
        gemini shouldBe Some(googleModel._id)
        deepseek shouldBe Some(deepseekModel._id)
      }
    }

    "return None for inputs that aren't aliases" in {
      for {
        conv <- freshConversation()
        out <- ModelAlias.resolve("definitely-not-an-alias", ctx(conv))
      } yield out shouldBe None
    }
  }

  // --- ModelResolution.resolve ---------------------------------------------

  "ModelResolution.resolve" should {

    "match an exact registered id (Resolution.ExactId)" in {
      for {
        conv <- freshConversation()
        out <- ModelResolution.resolve("openai/gpt-5.5", ctx(conv))
      } yield out match {
        case ModelResolutionResult.Resolved(id, ModelResolutionResult.Resolution.ExactId) =>
          id shouldBe openaiModel._id
        case other => fail(s"expected ExactId Resolved, got $other")
      }
    }

    "match a bare model name via the registry's tolerant suffix lookup" in {
      // "qwen3.5-9b" appears as the model segment of exactly one
      // registered id; ModelRegistry.findTolerant matches via
      // `endsWith("/qwen3.5-9b")`. The discriminator is ExactId
      // because findTolerant returned a hit; the BareModel branch
      // only runs as a secondary fallback for inputs the tolerant
      // lookup misses.
      for {
        conv <- freshConversation()
        out <- ModelResolution.resolve("qwen3.5-9b", ctx(conv))
      } yield out match {
        case ModelResolutionResult.Resolved(id, _) =>
          id shouldBe localModel._id
        case other => fail(s"expected Resolved, got $other")
      }
    }

    "use the alias resolver before strict id matching" in {
      for {
        conv <- freshConversation()
        out <- ModelResolution.resolve("local", ctx(conv))
      } yield out match {
        case ModelResolutionResult.Resolved(id, ModelResolutionResult.Resolution.Alias) =>
          id shouldBe localModel._id
        case other => fail(s"expected Alias Resolved, got $other")
      }
    }

    "refuse with a guidance message listing aliases + sample registry ids" in {
      for {
        conv <- freshConversation()
        out <- ModelResolution.resolve("completely-made-up-model", ctx(conv))
      } yield out match {
        case ModelResolutionResult.Unresolved(input, guidance) =>
          input shouldBe "completely-made-up-model"
          guidance should include("Cannot resolve")
          guidance should include("Aliases:")
          guidance should include("local")
          guidance should include("openai")
          guidance should include("Registered models")
        case other => fail(s"expected Unresolved, got $other")
      }
    }

    "refuse on empty input" in {
      for {
        conv <- freshConversation()
        out <- ModelResolution.resolve("   ", ctx(conv))
      } yield out shouldBe a[ModelResolutionResult.Unresolved]
    }
  }

  // --- pin_model integration ------------------------------------------------

  "pin_model with the alias resolver" should {

    "resolve 'local' to the LlamaCpp-registered model and persist that id" in {
      for {
        conv <- freshConversation()
        _ <- PinModelTool.execute(PinModelInput("local"), ctx(conv)).toList
        loaded <- loadConv(conv)
      } yield loaded.pinnedModelId shouldBe Some(localModel._id)
    }

    "refuse to pin to a phantom id without persisting anything" in {
      for {
        conv <- freshConversation()
        events <- PinModelTool.execute(PinModelInput("flarbargle"), ctx(conv)).toList
        loaded <- loadConv(conv)
      } yield {
        loaded.pinnedModelId shouldBe None
        replyText(events) should include("Cannot resolve 'flarbargle'")
      }
    }

    "preserve direct id pins for known ids" in {
      for {
        conv <- freshConversation()
        _ <- PinModelTool.execute(PinModelInput("openai/gpt-5.5"), ctx(conv)).toList
        loaded <- loadConv(conv)
      } yield loaded.pinnedModelId shouldBe Some(openaiModel._id)
    }
  }

  // --- switch_model integration --------------------------------------------

  "switch_model with the alias resolver" should {

    "refuse 'flarbargle' (not an alias, not a known id) without creating an ad-hoc strategy" in {
      for {
        conv <- freshConversation()
        events <- SwitchModelTool.execute(SwitchModelInput("flarbargle"), ctx(conv)).toList
        strategies <- TestSigil.listProviderStrategies(conv.space, ctx(conv).chain)
      } yield {
        replyText(events) should include("Cannot resolve 'flarbargle'")
        // No phantom ad-hoc strategy was persisted under this conv's space.
        strategies.exists(_.label.contains("flarbargle")) shouldBe false
      }
    }

    "create an ad-hoc strategy for 'local' resolved to the llamacpp model" in {
      for {
        conv <- freshConversation()
        _ <- SwitchModelTool.execute(SwitchModelInput("local"), ctx(conv)).toList
        strategies <- TestSigil.listProviderStrategies(conv.space, ctx(conv).chain)
      } yield
        // The ad-hoc record's label embeds the resolved canonical id.
        strategies.exists(s =>
          s.defaultCandidates.headOption.exists(_.modelId == localModel._id)) shouldBe true
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
