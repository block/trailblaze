package xyz.block.trailblaze.cli

import kotlinx.serialization.json.Json
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeJson
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.ui.TrailblazePortManager
import xyz.block.trailblaze.ui.TrailblazeDesktopUtil
import xyz.block.trailblaze.ui.models.TrailblazeServerState.SavedTrailblazeAppConfig
import java.io.File
import xyz.block.trailblaze.util.Console

/**
 * Describes a single config key: how to read it, write it, and what values are valid.
 */
data class ConfigKey(
  val name: String,
  val description: String,
  val validValues: String,
  val get: (SavedTrailblazeAppConfig) -> String,
  /** Returns the updated config, or null if the value is invalid. */
  val set: (SavedTrailblazeAppConfig, String) -> SavedTrailblazeAppConfig?,
)

/** Registry of all config keys supported by `trailblaze config <key> [<value>]`. */
val CONFIG_KEYS: Map<String, ConfigKey> = listOf(
  ConfigKey(
    name = "llm",
    description = "LLM provider and model (shorthand: provider/model)",
    validValues = "provider/model (e.g., openai/gpt-4-1, anthropic/claude-sonnet-4-20250514)",
    get = { config -> "${config.llmProvider}/${config.llmModel}" },
    set = { config, value ->
      val parts = value.split("/", limit = 2)
      if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) {
        null
      } else {
        config.copy(llmProvider = parts[0].lowercase(), llmModel = parts[1])
      }
    },
  ),
  ConfigKey(
    name = "llm-provider",
    description = "LLM provider",
    validValues = "openai, anthropic, google, ollama, openrouter, etc.",
    get = { config -> config.llmProvider },
    set = { config, value -> config.copy(llmProvider = value.lowercase()) },
  ),
  ConfigKey(
    name = "llm-model",
    description = "LLM model ID",
    validValues = "e.g., gpt-4-1, claude-sonnet-4-20250514, gemini-3-flash",
    get = { config -> config.llmModel },
    set = { config, value -> config.copy(llmModel = value) },
  ),
  ConfigKey(
    name = "agent",
    description = "Agent implementation",
    validValues = AgentImplementation.entries.joinToString(", ") { it.name },
    get = { config -> config.agentImplementation.name },
    set = { config, value ->
      CliConfigHelper.parseAgent(value)?.let { config.copy(agentImplementation = it) }
    },
  ),
  ConfigKey(
    name = "android-driver",
    description = "Android driver type",
    validValues = "HOST, ONDEVICE, ACCESSIBILITY",
    get = { config ->
      (config.selectedTrailblazeDriverTypes[TrailblazeDevicePlatform.ANDROID] ?: "not set").toString()
    },
    set = { config, value ->
      CliConfigHelper.parseAndroidDriver(value)?.let { driverType ->
        config.copy(
          selectedTrailblazeDriverTypes = config.selectedTrailblazeDriverTypes +
            (TrailblazeDevicePlatform.ANDROID to driverType)
        )
      }
    },
  ),
  ConfigKey(
    name = "ios-driver",
    description = "iOS driver type",
    validValues = "HOST",
    get = { config ->
      (config.selectedTrailblazeDriverTypes[TrailblazeDevicePlatform.IOS] ?: "not set").toString()
    },
    set = { config, value ->
      CliConfigHelper.parseIosDriver(value)?.let { driverType ->
        config.copy(
          selectedTrailblazeDriverTypes = config.selectedTrailblazeDriverTypes +
            (TrailblazeDevicePlatform.IOS to driverType)
        )
      }
    },
  ),
  ConfigKey(
    name = "set-of-mark",
    description = "Enable/disable Set of Mark mode",
    validValues = "true, false",
    get = { config -> config.setOfMarkEnabled.toString() },
    set = { config, value ->
      value.toBooleanStrictOrNull()?.let { config.copy(setOfMarkEnabled = it) }
    },
  ),
  ConfigKey(
    name = "ai-fallback",
    description = "Enable/disable AI fallback when recorded steps fail",
    validValues = "true, false",
    get = { config -> config.aiFallbackEnabled.toString() },
    set = { config, value ->
      value.toBooleanStrictOrNull()?.let { config.copy(aiFallbackEnabled = it) }
    },
  ),
).associateBy { it.name }

/**
 * Lightweight helper for CLI config commands.
 * Directly reads/writes the settings JSON without requiring full app initialization.
 */
object CliConfigHelper {
  
  private val json: Json = TrailblazeJson.defaultWithoutToolsInstance

  /**
   * Gets the path to the settings file.
   */
  fun getSettingsFile(): File = File("build/${TrailblazeDesktopUtil.SETTINGS_FILENAME}")
  
  /**
   * Reads the current config from disk, or returns null if not found.
   */
  fun readConfig(): SavedTrailblazeAppConfig? {
    val file = getSettingsFile()
    return try {
      if (file.exists()) {
        json.decodeFromString(SavedTrailblazeAppConfig.serializer(), file.readText())
      } else {
        null
      }
    } catch (e: Exception) {
      Console.error("Error reading config: ${e.message}")
      null
    }
  }
  
  /**
   * Resolves the effective HTTP port using CLI settings.
   */
  fun resolveEffectiveHttpPort(): Int {
    return TrailblazePortManager.resolveEffectiveHttpPort(::readConfig)
  }
  
  /**
   * Resolves the effective HTTPS port using CLI settings.
   */
  fun resolveEffectiveHttpsPort(): Int {
    return TrailblazePortManager.resolveEffectiveHttpsPort(::readConfig)
  }
  
  /**
   * Writes the config to disk.
   */
  fun writeConfig(config: SavedTrailblazeAppConfig) {
    val file = getSettingsFile()
    file.parentFile?.mkdirs()
    file.writeText(json.encodeToString(SavedTrailblazeAppConfig.serializer(), config))
  }
  
  /**
   * Returns a default config if none exists.
   */
  fun defaultConfig(): SavedTrailblazeAppConfig = SavedTrailblazeAppConfig(
    selectedTrailblazeDriverTypes = mapOf(
      TrailblazeDevicePlatform.ANDROID to TrailblazeDriverType.DEFAULT_ANDROID_ON_DEVICE,
      TrailblazeDevicePlatform.IOS to TrailblazeDriverType.IOS_HOST,
    )
  )
  
  /**
   * Reads or creates config with defaults.
   */
  fun getOrCreateConfig(): SavedTrailblazeAppConfig {
    return readConfig() ?: defaultConfig().also { writeConfig(it) }
  }
  
  /**
   * Updates config with the given modifier function.
   */
  fun updateConfig(modifier: (SavedTrailblazeAppConfig) -> SavedTrailblazeAppConfig) {
    val current = getOrCreateConfig()
    val updated = modifier(current)
    writeConfig(updated)
  }
  
  /**
   * Parse Android driver string to TrailblazeDriverType.
   */
  fun parseAndroidDriver(driver: String): TrailblazeDriverType? {
    return when (driver.uppercase()) {
      "HOST", "ANDROID_HOST" -> TrailblazeDriverType.ANDROID_HOST
      "ONDEVICE", "INSTRUMENTATION", "ANDROID_ONDEVICE_INSTRUMENTATION" ->
        TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION
      "ACCESSIBILITY", "ANDROID_ONDEVICE_ACCESSIBILITY" ->
        TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY
      else -> null
    }
  }
  
  /**
   * Parse iOS driver string to TrailblazeDriverType.
   */
  fun parseIosDriver(driver: String): TrailblazeDriverType? {
    return when (driver.uppercase()) {
      "HOST", "IOS_HOST" -> TrailblazeDriverType.IOS_HOST
      else -> null
    }
  }
  
  /**
   * Parse agent implementation string.
   */
  fun parseAgent(agent: String): AgentImplementation? {
    return try {
      AgentImplementation.valueOf(agent.uppercase())
    } catch (e: IllegalArgumentException) {
      null
    }
  }
}
