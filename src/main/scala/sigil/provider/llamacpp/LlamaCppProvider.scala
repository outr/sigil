package sigil.provider.llamacpp

import fabric.Json
import fabric.io.JsonFormatter
import fabric.rw.*
import rapid.Task
import sigil.db.Model
import sigil.provider.{Provider, ProviderType}
import spice.http.client.HttpClient
import spice.net.*

case class LlamaCppProvider(url: URL, models: List[Model]) extends Provider {
  override def `type`: ProviderType = ProviderType.LlamaCpp
}

object LlamaCppProvider {
  def apply(url: URL = url"http://localhost:8081"): Task[LlamaCppProvider] = LlamaCpp.loadModels(url)
    .map { models =>
      LlamaCppProvider(url, models)
    }
}