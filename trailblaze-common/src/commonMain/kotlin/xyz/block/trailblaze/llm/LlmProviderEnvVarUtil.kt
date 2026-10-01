package xyz.block.trailblaze.llm

import xyz.block.trailblaze.llm.config.BuiltInLlmModelRegistry
import xyz.block.trailblaze.llm.config.LlmProviderConfig
import xyz.block.trailblaze.util.readPlatformEnvVar

object LlmProviderEnvVarUtil {

  fun getEnvironmentVariableKeyForProvider(provider: TrailblazeLlmProvider): String? {
    return BuiltInLlmModelRegistry.authForProvider(provider)?.envVar
  }

  /**
   * Gets the environment variable key for a provider, consulting the YAML config first.
   * Falls back to the built-in mapping if the config doesn't specify an env var.
   */
  fun getEnvironmentVariableKeyForProviderConfig(
    config: LlmProviderConfig?,
    provider: TrailblazeLlmProvider,
  ): String? {
    return config?.auth?.envVar ?: getEnvironmentVariableKeyForProvider(provider)
  }

  fun getEnvironmentVariableValueForProvider(provider: TrailblazeLlmProvider): String? {
    val key = getEnvironmentVariableKeyForProvider(provider)
    return key?.let { readPlatformEnvVar(it) }
  }

  /**
   * Gets the environment variable value for a provider, consulting the YAML config first.
   */
  fun getEnvironmentVariableValueForProviderConfig(
    config: LlmProviderConfig?,
    provider: TrailblazeLlmProvider,
  ): String? {
    val key = getEnvironmentVariableKeyForProviderConfig(config, provider)
    return key?.let { readPlatformEnvVar(it) }
  }
}
