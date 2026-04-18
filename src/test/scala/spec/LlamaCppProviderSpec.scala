package spec

import rapid.Task
import sigil.provider.Provider
import sigil.provider.llamacpp.LlamaCppProvider

class LlamaCppProviderSpec extends AbstractProviderSpec {
  override protected val provider: Task[Provider] = LlamaCppProvider()
    .singleton
}
