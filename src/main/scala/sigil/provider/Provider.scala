package sigil.provider

import sigil.db.Model

trait Provider {
  def `type`: ProviderType
  def models: List[Model]
}