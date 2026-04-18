package sigil.provider

import fabric.rw.RW

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
