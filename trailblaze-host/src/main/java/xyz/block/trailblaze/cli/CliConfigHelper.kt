package xyz.block.trailblaze.cli

import kotlinx.serialization.json.Json
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeJson
import xyz.block.trailblaze.ui.TrailblazeDesktopUtil
import xyz.block.trailblaze.ui.models.TrailblazeServerState.SavedTrailblazeAppConfig
import java.io.File
import xyz.block.trailblaze.util.Console

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
      TrailblazeDevicePlatform.ANDROID to TrailblazeDriverType.ANDROID_HOST,
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
  
}
