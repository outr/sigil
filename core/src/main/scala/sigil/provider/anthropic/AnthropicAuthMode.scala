package sigil.provider.anthropic

/**
 * Credential-header strategy for [[AnthropicProvider]]. The direct
 * Anthropic API uses `x-api-key` + `anthropic-version`; vendor
 * mirrors that expose `/v1/messages` (DigitalOcean Inference for
 * `anthropic-claude-*` models) use `Authorization: Bearer`. The wire
 * body is identical — only the auth shape differs.
 */
enum AnthropicAuthMode {
  case XApiKey
  case Bearer
}
