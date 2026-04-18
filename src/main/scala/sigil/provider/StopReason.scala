package sigil.provider

import fabric.rw.*

/**
 * Why a streaming provider response ended.
 */
enum StopReason derives RW {

  /**
   * Model finished naturally (stop, end_turn).
   */
  case Complete

  /**
   * Model emitted a tool call and is waiting for results.
   */
  case ToolCall

  /**
   * Hit output token limit.
   */
  case MaxTokens

  /**
   * Provider safety filter triggered.
   */
  case ContentFiltered

  /**
   * Stream was cancelled by the consumer.
   */
  case Cancelled

  /**
   * Provider returned a stop reason we didn't recognize.
   */
  case Unknown(raw: String)
}
