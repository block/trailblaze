package xyz.block.trailblaze.llm.config

/**
 * Resolved authentication for an LLM provider. Produced by [LlmAuthResolver] on the host side
 * and consumed by both the reverse proxy (Path A) and instrumentation args (Path B).
 *
 * @param providerId The provider key from the YAML config (e.g., "openai", "corp_gateway")
 * @param token The resolved auth token, or null if not available. Resolution priority:
 *   1. Environment variable (always wins — critical for CI)
 *   2. Custom token provider (e.g., Databricks OAuth)
 *   3. Built-in env var mapping (fallback)
 * @param envVarKey The environment variable key used for this provider's token.
 *   Used as the instrumentation arg key when passing tokens to devices.
 * @param headers Additional HTTP headers to include with requests (from auth.headers in YAML)
 * @param baseUrl The provider's base URL (for reverse proxy URL matching)
 * @param providerConfig The full provider config, for device-side client creation in standalone mode
 */
data class ResolvedProviderAuth(
  val providerId: String,
  val token: String?,
  val envVarKey: String?,
  val headers: Map<String, String>,
  val baseUrl: String?,
  val providerConfig: LlmProviderConfig?,
)
