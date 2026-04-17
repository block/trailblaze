package xyz.block.trailblaze.llm.config

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import java.io.File
import xyz.block.trailblaze.util.Console

/**
 * Loads and merges LLM configuration from YAML files.
 *
 * Both user-level and project-level configs use the same `trailblaze.yaml` format
 * with LLM settings nested under the `llm:` key.
 *
 * Loading order (later overrides earlier):
 * 1. User-level: `~/.trailblaze/trailblaze.yaml` (under `llm:` key)
 * 2. Project-level: `./trailblaze.yaml` (under `llm:` key)
 * 3. Environment variable overrides
 */
object LlmConfigLoader {

  private const val CONFIG_FILENAME = TrailblazeConfigPaths.CONFIG_FILENAME
  private const val DOT_TRAILBLAZE_DIR = TrailblazeConfigPaths.DOT_TRAILBLAZE_DIR

  internal val yaml = Yaml(
    configuration = YamlConfiguration(
      strictMode = false,
      encodeDefaults = false,
    ),
  )

  /**
   * Loads the merged LLM config from user-level and project-level YAML files.
   * Returns an empty [LlmConfig] if no config files exist (preserving backward compatibility).
   *
   * After loading, runs semantic validation and logs warnings for common mistakes
   * (e.g. default model not found in any provider, empty provider definitions).
   */
  fun load(
    userHomeDir: File = File(System.getProperty("user.home") ?: "."),
    projectDir: File? = File("."),
  ): LlmConfig {
    var config = LlmConfig()

    // 1. User-level config: ~/.trailblaze/trailblaze.yaml (llm: key)
    val userConfigFile = File(userHomeDir, "$DOT_TRAILBLAZE_DIR/$CONFIG_FILENAME")
    config = mergeFromConfigFile(config, userConfigFile, "user-level")

    // 2. Project-level config: ./trailblaze.yaml (llm: key)
    if (projectDir != null) {
      val projectConfigFile = File(projectDir, CONFIG_FILENAME)
      config = mergeFromConfigFile(config, projectConfigFile, "project-level")
    }

    // 3. Environment variable overrides
    config = applyEnvVarOverrides(config)

    // 4. Validate and warn about common issues
    validate(config).forEach { Console.log("Warning: $it") }

    return config
  }

  private fun mergeFromConfigFile(base: LlmConfig, file: File, label: String): LlmConfig {
    if (!file.exists()) return base
    return try {
      val content = file.readText()
      if (content.isBlank()) return base
      val projectConfig = yaml.decodeFromString(TrailblazeProjectYamlConfig.serializer(), content)
      val llmConfig = projectConfig.llm ?: return base
      Console.log("Loaded $label LLM config from ${file.absolutePath}")
      LlmConfigMerger.merge(base, llmConfig)
    } catch (e: Exception) {
      Console.log(
        "Warning: Failed to parse $label LLM config at ${file.absolutePath}: ${e.message}\n" +
          "  Hint: LLM settings should be nested under the 'llm:' key. Example:\n" +
          "    llm:\n" +
          "      providers:\n" +
          "        openai:\n" +
          "          models:\n" +
          "            - id: gpt-4.1\n" +
          "      defaults:\n" +
          "        model: openai/gpt-4.1",
      )
      base
    }
  }

  /**
   * Validates a loaded config and returns a list of human-readable warnings.
   * An empty list means no issues were found.
   */
  internal fun validate(config: LlmConfig): List<String> {
    if (config.providers.isEmpty()) return emptyList()
    val warnings = mutableListOf<String>()

    // Check for providers with no models defined
    for ((key, provider) in config.providers) {
      if (provider.models.isEmpty()) {
        warnings += "Provider '$key' has no models defined. " +
          "Add a 'models:' list or the provider will only use built-in models."
      }
    }

    // Check that defaults.model references a model in a configured provider
    val defaultModel = config.defaults.model
    if (defaultModel != null) {
      val allModelIds = config.providers.flatMap { (key, provider) ->
        provider.models.map { entry -> "$key/${entry.id}" } + provider.models.map { it.id }
      }.toSet()
      if (defaultModel !in allModelIds) {
        warnings += "Default model '$defaultModel' not found in any configured provider. " +
          "Available models: ${allModelIds.filter { '/' in it }.sorted().joinToString(", ").ifEmpty { "(none)" }}. " +
          "The model may still resolve from built-in definitions."
      }
    }

    return warnings
  }

  private fun applyEnvVarOverrides(config: LlmConfig): LlmConfig {
    val model = System.getenv("TRAILBLAZE_DEFAULT_MODEL")

    if (model == null) return config

    return config.copy(
      defaults = config.defaults.copy(
        model = model,
      ),
    )
  }
}
