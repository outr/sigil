package sigil.provider

import fabric.rw.*

enum ProviderType derives RW {
  case OpenAI
  case Anthropic
  case Google
  case DeepSeek
  case Groq
  case Mistral
  case LlamaCpp
}
