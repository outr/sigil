package sigil.tool.consult

import lightdb.id.Id
import rapid.{Stream, Task}
import sigil.{Sigil, TurnContext}
import sigil.db.Model
import sigil.event.{Event, Message}
import sigil.participant.ParticipantId
import sigil.provider.{GenerationSettings, OneShotRequest, ProviderEvent}
import sigil.tool.model.ResponseContent
import sigil.tool.{Tool, ToolExample, ToolInput, ToolName, TypedTool}

import scala.reflect.ClassTag

/**
 * One-shot LLM consultation. Two surfaces on the same object:
 *
 * 1. `executeTyped` — the LLM-callable path. The agent invokes the tool
 *    with system + user prompts; the consulted model returns free-form
 *    text, which is emitted as a [[Message]] back into the conversation.
 *
 * 2. [[invoke]] — the framework-callable path. Returns a typed
 *    `Option[I]` directly, no events emitted. Used by framework
 *    machinery that needs structured sub-decisions.
 */
case object ConsultTool extends TypedTool[ConsultInput](
  name = ToolName("consult"),
  description =
    """Consult another model — or yourself with no conversation history — for a focused sub-question.
      |
      |Use this when:
      |  - the current model isn't strong enough for a hard sub-task and a stronger model should answer
      |  - you want a fresh perspective without the current conversation's context biasing the answer
      |  - you want to delegate a focused, self-contained sub-question and read its answer
      |
      |The consulted model receives ONLY the `systemPrompt` and `userPrompt` you supply — no conversation
      |history, no tools beyond what the framework forces. Its answer comes back as a Message you read on
      |your next turn.
      |
      |`modelId` — the model to consult (e.g. "anthropic/claude-opus-4-7", "openai/gpt-5"). Use any model
      |the framework knows about.
      |
      |`systemPrompt` — set the consulted model's role / context for this question. One paragraph max.
      |
      |`userPrompt` — the actual question or task. Be specific; the consulted model has no other context.""".stripMargin,
  examples = List(
    ToolExample(
      "Ask a stronger model for a focused legal interpretation",
      ConsultInput(
        modelId = Id("anthropic/claude-opus-4-7"),
        systemPrompt = "You are a careful legal reasoner. Quote relevant clauses verbatim.",
        userPrompt = "Given this contract clause, does it permit unilateral termination? <clause text>"
      )
    )
  )
) {
  override protected def executeTyped(input: ConsultInput, context: TurnContext): Stream[Event] = Stream.force {
    context.sigil.providerFor(input.modelId, context.chain).flatMap { provider =>
      val request = OneShotRequest(
        modelId = input.modelId,
        systemPrompt = input.systemPrompt,
        userPrompt = input.userPrompt,
        chain = context.chain
      )
      provider(request).toList.map { events =>
        val text = ConsultTool.collectText(events)
        val message = Message(
          participantId = context.caller,
          conversationId = context.conversation.id,
          topicId = context.conversation.currentTopicId,
          content = Vector(ResponseContent.Text(if (text.isEmpty) "(no response)" else text))
        )
        Stream.emits(List[Event](message))
      }
    }
  }

  /**
   * Framework-facing typed consult. Builds a one-shot provider request
   * forced to invoke `tool` (`tool_choice = "required"`), drains the
   * result, and returns the parsed input cast to `I` (or `None` if the
   * model didn't produce a tool call of the expected type).
   */
  def invoke[I <: ToolInput: ClassTag](sigil: Sigil,
                                       modelId: Id[Model],
                                       chain: List[ParticipantId],
                                       systemPrompt: String,
                                       userPrompt: String,
                                       tool: Tool,
                                       generationSettings: GenerationSettings = GenerationSettings()): Task[Option[I]] = {
    val ct = summon[ClassTag[I]]
    sigil.providerFor(modelId, chain).flatMap { provider =>
      val request = OneShotRequest(
        modelId = modelId,
        systemPrompt = systemPrompt,
        userPrompt = userPrompt,
        generationSettings = generationSettings,
        tools = Vector(tool),
        chain = chain
      )
      provider(request).toList.map { events =>
        events.collectFirst {
          case ProviderEvent.ToolCallComplete(_, parsedInput) if ct.runtimeClass.isInstance(parsedInput) =>
            parsedInput.asInstanceOf[I]
        }
      }
    }
  }

  private[consult] def collectText(events: List[ProviderEvent]): String = {
    val sb = new StringBuilder
    events.foreach {
      case ProviderEvent.ContentBlockDelta(_, t) => sb.append(t)
      case ProviderEvent.TextDelta(t)            => sb.append(t)
      case _                                     => ()
    }
    sb.toString
  }
}
