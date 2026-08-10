package xyz.block.trailblaze.llm.config

/**
 * The `num_ctx` (context window) value Trailblaze requests from an Ollama server.
 *
 * A request that omits `num_ctx` does not get the model's own maximum context. Ollama
 * sizes the window itself, against the memory it has available rather than against what
 * the model declares (and `OLLAMA_CONTEXT_LENGTH`, when set on the server, replaces that
 * automatic sizing). The effective context is therefore a property of the host machine: on
 * a memory-constrained one it lands well below a single Trailblaze agent turn — ~20K
 * tokens for a set-of-mark screenshot + view hierarchy + tool definitions on a
 * content-heavy screen — and the turn fails with `exceed_context_size_error` no matter what
 * `context_length` the model's registry entry declares. Requesting `num_ctx` per call is
 * what makes the context predictable instead of hardware-dependent.
 *
 * The value is deliberately a stable constant rather than fitted per prompt: Ollama
 * reloads the model whenever the requested context length changes, and a reload costs
 * far more than the memory headroom saved. 64K is the middle of the range — several
 * multi-turn agent loops fit, and the KV cache still fits a laptop — and it is clamped
 * down to the model's own declared maximum by the client-side strategy when the model
 * supports less. On a machine with a lot of memory Ollama's automatic choice can exceed
 * 64K, and an explicit request replaces it; [ENV_VAR] is how such a machine raises it.
 *
 * **Every `OllamaClient` construction must pass this** as
 * `contextWindowStrategy = ContextWindowStrategy.Fixed(...)`; a site that omits it silently
 * runs at whatever window Ollama picks for the machine and fails on real screens there.
 * Host-side sites resolve the value through [resolveNumCtx] with [ENV_VAR]; on-device sites
 * use [DEFAULT_NUM_CTX] directly, since the instrumentation process has no host environment
 * to read.
 *
 * That split means [ENV_VAR] does not reach on-device clients, and both ends can address the
 * same Ollama server (the device's base URL is host-forwarded). Setting [ENV_VAR] to
 * anything but [DEFAULT_NUM_CTX] while on-device AI legs run against that server therefore
 * makes the two ends request different lengths, and Ollama reloads the model on every
 * alternation. Closing this means forwarding the resolved value to the device as an
 * instrumentation arg alongside the base URL, in `LlmAuthResolver.toInstrumentationArgs`.
 */
object OllamaContextWindow {

  /** Environment variable that overrides the requested `num_ctx` on the host. */
  const val ENV_VAR: String = "TRAILBLAZE_OLLAMA_NUM_CTX"

  /** Default `num_ctx` requested from Ollama when [ENV_VAR] is unset. */
  const val DEFAULT_NUM_CTX: Long = 65536

  /**
   * Resolves the `num_ctx` to request: [rawOverride] (the value of [ENV_VAR]) when it is
   * a positive integer, otherwise [DEFAULT_NUM_CTX]. Malformed or non-positive values
   * fall back to the default, matching the repo's other environment knobs.
   */
  fun resolveNumCtx(rawOverride: String?): Long {
    val parsed = rawOverride?.trim()?.toLongOrNull()
    return if (parsed != null && parsed > 0) parsed else DEFAULT_NUM_CTX
  }
}
