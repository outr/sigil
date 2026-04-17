package sigil.provider

import fabric.Json
import fabric.define.Definition
import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import sigil.db.Model

trait Provider {
  def `type`: ProviderType
  def models: List[Model]
}

trait Conversation {
  def id: Id[Conversation]
}

case class ProviderRequest(conversationId: Id[Conversation],
                           modelId: Id[Model],
                           instructions: Instructions,
                           events: Vector[Event],
                           currentMode: Mode,
                           generationSettings: GenerationSettings,
                           requestId: Id[ProviderRequest] = Id()) derives RW

case class Instructions(system: String, developer: Option[String]) derives RW

trait Event {
  def id: Id[Event]
  def visibility: Set[EventVisibility]
  def timestamp: Timestamp
}

object Event {
  private var types: List[RW[? <: Event]] = Nil

  /** Mutable poly — rebuilt when register() is called.
   * Delegating given ensures all serialization uses the latest version. */
  @volatile private var _poly: RW[Event] = generate()

  private def generate(): RW[Event] = RW.poly[Event]()(types*)

  /** Register additional Event subtypes into the poly RW.
   * Call this at backend startup BEFORE any serialization occurs. */
  def register(types: RW[? <: Event]*): Unit = synchronized {
    this.types = this.types ++ types.toList
    _poly = generate()
  }

  given RW[Event] = new RW[Event] {
    override def read(t: Event): Json = _poly.read(t)
    override def write(json: Json): Event = _poly.write(json)
    override def definition: Definition = _poly.definition
  }

  /** Generate a new event ID. */
  def id(): Id[Event] = Id[Event]()
}

enum EventVisibility derives RW {
  case UI
  case Model
}

enum Mode derives RW {
  /** General chat, Q&A, conversation. The default mode. */
  case Conversation
  /** Code generation, editing, review. */
  case Coding
  /** Reasoning, data analysis, complex problems. */
  case Analysis
  /** Quick intent checks, relevance scoring, classification. */
  case Classification
  /** Writing, brainstorming, synthesis, polishing. */
  case Creative
  /** Condensing content, title generation, context compression. */
  case Summarization
}

final case class GenerationSettings(temperature: Option[Double] = None,
                                     maxOutputTokens: Option[Int] = None,
                                     effort: Option[Effort] = None,
                                     topP: Option[Double] = None,
                                     stopSequences: Vector[String] = Vector.empty) derives RW

/** How much reasoning effort the model should apply.
 * The provider translates this to the appropriate wire format:
 * - Anthropic: thinking.budget_tokens (scaled from effort level)
 * - OpenAI: reasoning_effort (low/medium/high)
 * - Others: best effort or ignored */
enum Effort derives RW {
  case Low
  case Medium
  case High
  case Max
  case Custom(tokens: Int) // explicit token budget for providers that support it
}