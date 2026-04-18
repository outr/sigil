package sigil.provider

import fabric.rw.RW

enum Mode derives RW {

  /**
   * General chat, Q&A, conversation. The default mode.
   */
  case Conversation

  /**
   * Code generation, editing, review.
   */
  case Coding

  /**
   * Reasoning, data analysis, complex problems.
   */
  case Analysis

  /**
   * Quick intent checks, relevance scoring, classification.
   */
  case Classification

  /**
   * Writing, brainstorming, synthesis, polishing.
   */
  case Creative

  /**
   * Condensing content, title generation, context compression.
   */
  case Summarization

  /**
   * A one-line description used when rendering the current mode into the LLM's
   * prompt and when enumerating modes in the change_mode tool description.
   */
  def description: String =
    this match {
      case Conversation => "General chat, Q&A, conversation."
      case Coding => "Code generation, editing, review."
      case Analysis => "Reasoning, data analysis, complex problems."
      case Classification => "Quick intent checks, relevance scoring, classification."
      case Creative => "Writing, brainstorming, synthesis, polishing."
      case Summarization => "Condensing content, title generation, context compression."
    }
}
