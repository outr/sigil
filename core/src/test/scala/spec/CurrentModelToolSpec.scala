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
import sigil.signal.EventState
import sigil.tool.provider.{
  CurrentModelInput, CurrentModelTool, ListModelsInput, ListModelsTool,
  PinModelInput, PinModelTool
}

/**
 * Coverage for the agent-facing model introspection surface.
 *
 *   - `current_model` reports pinned / assigned-strategy / last-used
 *     / resolved id for the active conversation.
 *   - `list_models` filters the registry by provider and substring
 *     query.
 *
 * Both tools emit typed output; assertions are against the typed
 * shape, not rendered text.
 */
class CurrentModelToolSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  // Seed the cache with a deterministic model set so the spec is
  // independent of upstream OpenRouter availability.
  private val openaiModel = stubModel("openai", "gpt-5.5")
  private val anthropicModel = stubModel("anthropic", "claude-opus-4.7")
  private val localModel = stubModel("local", "qwen3.5-9b")

  TestSigil.cache.replace(List(openaiModel, anthropicModel, localModel)).sync()

  private def stubModel(provider: String, model: String): Model = Model(
    canonicalSlug = s"$provider/$model",
    huggingFaceId = "",
    name = s"$provider $model",
    description = s"$provider's $model — test fixture",
    contextLength = 100_000,
    architecture = ModelArchitecture(
      modality         = "text->text",
      inputModalities  = List("text"),
      outputModalities = List("text"),
      tokenizer        = "cl100k_base",
      instructType     = None
    ),
    pricing = ModelPricing(
      prompt        = BigDecimal("0.000001"),
      completion    = BigDecimal("0.000002"),
      webSearch     = None,
      inputCacheRead = None
    ),
    topProvider = ModelTopProvider(
      contextLength       = Some(100_000L),
      maxCompletionTokens = Some(8_192L),
      isModerated         = false
    ),
    perRequestLimits     = None,
    supportedParameters  = Set("temperature", "max_tokens"),
    defaultParameters    = ModelDefaultParameters(),
    knowledgeCutoff      = None,
    expirationDate       = None,
    links                = ModelLinks(details = ""),
    created              = Timestamp(),
    modified             = Timestamp(),
    _id                  = Model.id(provider, model)
  )

  private def freshConversation(): Task[Conversation] = {
    val convId = Conversation.id(s"current-${rapid.Unique()}")
    val topic = Topic(
      conversationId = convId,
      label          = "spec",
      summary        = "spec",
      createdBy      = TestUser
    )
    val conv = Conversation(
      topics = List(TopicEntry(topic._id, topic.label, topic.summary)),
      _id    = convId
    )
    for {
      _ <- TestSigil.withDB(_.topics.transaction(_.upsert(topic))).unit
      _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv))).unit
    } yield conv
  }

  private def ctx(conv: Conversation): TurnContext =
    TurnContext(
      sigil        = TestSigil,
      chain        = List(TestUser, TestAgent),
      conversation = conv,
      turnInput    = TurnInput(conversationId = conv.id)
    )

  /** In-memory conversation skeleton — no DB persistence. Adequate for
    * `list_models` which doesn't read the conversation. */
  private def stubConv(suffix: String): Conversation = {
    val convId = Conversation.id(s"$suffix-${rapid.Unique()}")
    Conversation(
      topics = List(TopicEntry(
        id      = Topic.id(s"$suffix-topic"),
        label   = "stub",
        summary = "stub"
      )),
      _id = convId
    )
  }

  private def reloadConv(conv: Conversation): Task[Conversation] =
    TestSigil.withDB(_.conversations.transaction(_.get(conv.id))).map(_.getOrElse(conv))

  "current_model" should {

    "return all-None for a fresh conversation with no pin / strategy / history" in {
      for {
        conv <- freshConversation()
        out  <- CurrentModelTool.invoke(CurrentModelInput(), ctx(conv))
      } yield {
        out.pinned shouldBe None
        out.assignedStrategy shouldBe None
        out.lastUsed shouldBe None
        // resolved is None because no strategy or pin applies.
        out.resolved shouldBe None
      }
    }

    "report the pinned model id and a registry-resolved summary when pin_model is active" in {
      for {
        conv <- freshConversation()
        _    <- PinModelTool.execute(PinModelInput(localModel._id.value), ctx(conv)).toList
        reloaded <- reloadConv(conv)
        out  <- CurrentModelTool.invoke(CurrentModelInput(), ctx(reloaded))
      } yield {
        out.pinned.map(_.id) shouldBe Some(localModel._id.value)
        // The registry contains the model, so the summary is populated.
        out.pinned.flatMap(_.summary).map(_.provider) shouldBe Some("local")
        out.pinned.flatMap(_.summary).map(_.model) shouldBe Some("qwen3.5-9b")
        // `resolved` matches `pinned` because pinned wins over every other
        // resolution layer.
        out.resolved.map(_.id) shouldBe Some(localModel._id.value)
      }
    }

    "leave the registry summary empty when the persisted pin id isn't in the catalog" in {
      // PinModelTool refuses unregistered ids; bypass to write the
      // pin directly. Exercises current_model's resilience when an
      // older persisted pin no longer matches a catalog entry (e.g.
      // provider rotated the model name).
      val phantomId = lightdb.id.Id[Model]("phantom/unknown")
      for {
        conv <- freshConversation()
        _    <- TestSigil.withDB(_.conversations.transaction(_.modify(conv.id) {
          case None    => Task.pure(None)
          case Some(c) => Task.pure(Some(c.copy(pinnedModelId = Some(phantomId))))
        })).unit
        reloaded <- reloadConv(conv)
        out  <- CurrentModelTool.invoke(CurrentModelInput(), ctx(reloaded))
      } yield {
        out.pinned.map(_.id) shouldBe Some("phantom/unknown")
        out.pinned.flatMap(_.summary) shouldBe None
      }
    }

    "report lastUsed from the most recent agent Message stamped with a modelId" in {
      for {
        conv <- freshConversation()
        // Older agent message — stamped with anthropic.
        olderTs = Timestamp(System.currentTimeMillis() - 10_000)
        _ <- TestSigil.withDB(_.events.transaction(_.upsert(
          Message(
            participantId  = TestAgent,
            conversationId = conv.id,
            topicId        = conv.currentTopicId,
            role           = MessageRole.Standard,
            state          = EventState.Complete,
            modelId        = Some(anthropicModel._id),
            timestamp      = olderTs
          )
        ))).unit
        // Newer agent message — stamped with openai. lastUsed should
        // pick this up, ignoring the older anthropic stamp.
        _ <- TestSigil.withDB(_.events.transaction(_.upsert(
          Message(
            participantId  = TestAgent,
            conversationId = conv.id,
            topicId        = conv.currentTopicId,
            role           = MessageRole.Standard,
            state          = EventState.Complete,
            modelId        = Some(openaiModel._id),
            timestamp      = Timestamp()
          )
        ))).unit
        out <- CurrentModelTool.invoke(CurrentModelInput(), ctx(conv))
      } yield {
        out.lastUsed.map(_.id) shouldBe Some(openaiModel._id.value)
      }
    }
  }

  "list_models" should {

    "return every model when no filter is supplied" in {
      ListModelsTool.invoke(ListModelsInput(), ctx(stubConv("list-noop"))).map { out =>
        // The registry has at least our three test models; downstream
        // catalog refreshes may add more, so assert >= and that ours
        // appear.
        out.total should be >= 3
        val ids = out.models.map(_.id).toSet
        ids should contain (openaiModel._id.value)
        ids should contain (anthropicModel._id.value)
        ids should contain (localModel._id.value)
      }
    }

    "filter by provider exact-match" in {
      ListModelsTool.invoke(
        ListModelsInput(provider = Some("openai")),
        ctx(stubConv("list-openai"))
      ).map { out =>
        out.models.map(_.provider).distinct shouldBe List("openai")
        out.models.map(_.id) should contain (openaiModel._id.value)
      }
    }

    "filter by provider 'local' to surface the local llamacpp model" in {
      ListModelsTool.invoke(
        ListModelsInput(provider = Some("local")),
        ctx(stubConv("list-local"))
      ).map { out =>
        out.models.map(_.id) should contain (localModel._id.value)
        out.models.foreach(_.provider shouldBe "local")
        succeed
      }
    }

    "filter by query substring against id / name / description" in {
      ListModelsTool.invoke(
        ListModelsInput(query = Some("qwen")),
        ctx(stubConv("list-qwen"))
      ).map { out =>
        out.models.map(_.id) should contain (localModel._id.value)
      }
    }

    "respect limit when supplied" in {
      ListModelsTool.invoke(
        ListModelsInput(limit = Some(1)),
        ctx(stubConv("list-limited"))
      ).map { out =>
        out.returned shouldBe 1
        out.models.size shouldBe 1
        out.total should be >= 3
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
