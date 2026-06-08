package sigil.workflow

import lightdb.id.Id
import lightdb.time.Timestamp
import rapid.Task
import sigil.{Sigil, TurnContext}
import sigil.conversation.{Conversation, TurnInput}
import sigil.db.{Model, ModelArchitecture, ModelLinks, ModelPricing, ModelTopProvider}
import sigil.participant.ParticipantId
import strider.Workflow

/**
 * Build a [[TurnContext]] for in-workflow tool execution. Workflows
 * run outside of an agent's normal turn — there's no live
 * conversation projection, no curator output, no real participant
 * chain. Tools that need any of those are at risk of mis-behaving
 * when called from a workflow; the synthetic context provides the
 * best approximation:
 *
 *   - `conversation` is loaded from the workflow's `conversationId`
 *     if set, otherwise a placeholder is built in-memory (one-off
 *     ids, no persistence).
 *   - `chain` resolves to the workflow's `createdBy` (matched
 *     against the conversation's participants list); falls back to
 *     the conversation's first participant; ultimately falls back to
 *     a synthetic anonymous chain when the conversation is itself
 *     synthetic.
 *   - `turnInput` is empty — no curator runs from a workflow step.
 *
 * Tools that strictly require a real turn (Stop dispatch, topic-
 * shift detection, etc.) won't behave correctly here. The common
 * case (file system tools, web fetches, save_memory, notifications)
 * all run cleanly because they only consult `chain`,
 * `conversation.id`, and `sigil`.
 */
object SyntheticTurnContext {

  def build(host: Sigil, workflow: Workflow): Task[TurnContext] = {
    workflow.conversationId match {
      case None => Task.pure(emptyContext(host))
      case Some(convIdStr) =>
        val convId = Id[Conversation](convIdStr)
        for {
          maybeConv <- host.withDB(_.conversations.transaction(_.get(convId)))
          parentChain <- maybeConv match {
            case Some(conv) if conv.participants.isEmpty =>
              conv.parentConversationId match {
                case Some(parentId) =>
                  host.withDB(_.conversations.transaction(_.get(parentId))).map {
                    case Some(parent) =>
                      val createdByValue = workflow.createdBy.getOrElse("")
                      val matched = parent.participants.find(_.id.value == createdByValue).map(_.id)
                      matched.orElse(parent.participants.headOption.map(_.id)).toList
                    case None => Nil
                  }
                case None => Task.pure(Nil)
              }
            case _ => Task.pure(Nil)
          }
        } yield maybeConv match {
          case None       => emptyContext(host)
          case Some(conv) =>
            val createdByValue = workflow.createdBy.getOrElse("")
            val matched = conv.participants.find(_.id.value == createdByValue).map(_.id)
            val ownChain: List[ParticipantId] =
              matched.orElse(conv.participants.headOption.map(_.id)).toList
            val chain = if (ownChain.nonEmpty) ownChain else parentChain
            TurnContext(
              sigil = host,
              chain = chain,
              conversation = conv,
              turnInput = TurnInput(conversationId = convId),
              model = syntheticModel(host),
              // A workflow step's tool output feeds a variable, not the agent's
              // prompt — capture it in full so a Loop over a discovery step's
              // result iterates the whole set, not the bounded inline head.
              overflowLargeResults = false
            )
        }
    }
  }

  /** Pick a registered Model to stamp onto the synthetic context. The
    * workflow step is responsible for routing its own provider call so
    * this Model is informational only — tool bodies that consult it
    * (cost estimation, context-length heuristics) get a real record;
    * tool bodies that don't are unaffected. Falls back to a placeholder
    * record when the registry is empty (boot-time workflow firing). */
  private def syntheticModel(host: Sigil): Model =
    host.cache.all.headOption.getOrElse(syntheticPlaceholder)

  /** In-memory placeholder used when the model registry hasn't been
    * populated yet. Carries conservative defaults — short context,
    * zero pricing — so any tool that does read model facts off the
    * synthetic context doesn't get wildly wrong numbers. */
  private lazy val syntheticPlaceholder: Model = {
    val now = Timestamp()
    Model(
      canonicalSlug       = "sigil/synthetic-workflow",
      huggingFaceId       = "",
      name                = "synthetic-workflow",
      description         = "Placeholder Model for synthetic workflow TurnContexts; not an actual provider target.",
      contextLength       = 0L,
      architecture        = ModelArchitecture(
        modality         = "text->text",
        inputModalities  = List("text"),
        outputModalities = List("text"),
        tokenizer        = "Unknown",
        instructType     = None
      ),
      pricing             = ModelPricing(prompt = BigDecimal(0), completion = BigDecimal(0), webSearch = None, inputCacheRead = None),
      topProvider         = ModelTopProvider(contextLength = None, maxCompletionTokens = None, isModerated = false),
      perRequestLimits    = None,
      supportedParameters = Set.empty,
      knowledgeCutoff     = None,
      expirationDate      = None,
      links               = ModelLinks(details = ""),
      created             = now,
      _id                 = Model.id("sigil", "synthetic-workflow")
    )
  }

  /** Fallback when no conversation context exists — synthesize a
    * placeholder conversation with no participants. Useful for
    * cron-fired workflows whose tools don't need conversational
    * grounding (e.g. the file-system or web-fetch tool families). */
  private def emptyContext(host: Sigil): TurnContext = {
    val convId: Id[Conversation] = Conversation.id("workflow-synthetic-" + rapid.Unique())
    val now = lightdb.time.Timestamp()
    val conv = Conversation(
      topics = Nil,
      participants = Nil,
      currentMode = sigil.provider.ConversationMode,
      space = sigil.GlobalSpace,
      created = now,
      modified = now,
      _id = convId
    )
    TurnContext(
      sigil = host,
      chain = Nil,
      conversation = conv,
      turnInput = TurnInput(conversationId = convId),
      model = syntheticModel(host),
      overflowLargeResults = false
    )
  }
}
