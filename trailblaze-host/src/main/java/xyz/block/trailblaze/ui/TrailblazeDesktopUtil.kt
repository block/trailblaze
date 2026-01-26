package xyz.block.trailblaze.ui

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import xyz.block.trailblaze.ui.goose.GooseRecipe
import xyz.block.trailblaze.ui.goose.createGooseRecipe
import xyz.block.trailblaze.ui.goose.defaultOpenSourceActivities
import xyz.block.trailblaze.ui.goose.gooseRecipeJson
import xyz.block.trailblaze.ui.goose.TrailblazeGooseExtension
import xyz.block.trailblaze.ui.models.TrailblazeServerState
import java.awt.Desktop
import java.awt.Taskbar
import java.io.File
import java.io.FileWriter
import java.net.URI
import java.net.URLEncoder
import javax.imageio.ImageIO
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

object TrailblazeDesktopUtil {

  const val DOT_TRAILBLAZE_DIR_NAME: String = ".trailblaze"

  /**
   * The filename for Trailblaze settings.
   */
  const val SETTINGS_FILENAME = "trailblaze-settings.json"

  /**
   * Gets the default app data directory path.
   * @return The default app data directory: ~/.trailblaze
   */
  fun getDefaultAppDataDirectory(): File {
    return File(System.getProperty("user.home"), DOT_TRAILBLAZE_DIR_NAME)
  }

  /**
   * Gets the settings file in the default app data directory.
   * @return The settings file
   */
  fun getDefaultSettingsFile(): File {
    return File(getDefaultAppDataDirectory(), SETTINGS_FILENAME)
  }

  /**
   * Gets the effective app data directory based on the app config.
   * @param appConfig The current app configuration
   * @return The effective app data directory (configured or default)
   */
  fun getEffectiveAppDataDirectory(appConfig: TrailblazeServerState.SavedTrailblazeAppConfig): String {
    return appConfig.appDataDirectory ?: getDefaultAppDataDirectory().canonicalPath
  }

  /**
   * Gets the effective logs directory based on the app config.
   * @param appConfig The current app configuration
   * @return The effective logs directory (configured or default relative to app data directory)
   */
  fun getEffectiveLogsDirectory(appConfig: TrailblazeServerState.SavedTrailblazeAppConfig): String {
    return appConfig.logsDirectory ?: "${getEffectiveAppDataDirectory(appConfig)}/logs"
  }

  /**
   * Gets the effective trails directory based on the app config.
   * @param appConfig The current app configuration
   * @return The effective trails directory (configured or default relative to app data directory)
   */
  fun getEffectiveTrailsDirectory(appConfig: TrailblazeServerState.SavedTrailblazeAppConfig): String {
    return appConfig.trailsDirectory ?: "${getEffectiveAppDataDirectory(appConfig)}/trails"
  }

  /**
   * Sets the taskbar icon for macOS.
   *
   * This method sets the icon shown in the macOS Dock and app switcher.
   * It uses the image located at "icons/icon.png" in the classpath.
   */
  fun setAppConfigForTrailblaze() {
    if (Taskbar.isTaskbarSupported()) {
      // This sets the icon shown in the macOS Dock and app switcher

      Taskbar.getTaskbar().apply {
        iconImage = ImageIO.read(TrailblazeDesktopUtil::class.java.classLoader.getResource("icons/icon.png"))
      }
    }
  }

  fun openInDefaultBrowser(url: String) {
    try {
      if (Desktop.isDesktopSupported()) {
        Desktop.getDesktop().browse(URI(url))
      } else {
        println("Desktop is not supported on this platform.")
      }
    } catch (e: Exception) {
      e.printStackTrace()
    }
  }

  fun openInFileBrowser(file: File) {
    if (file.exists()) {
      Desktop.getDesktop().open(file)
    } else {
      println("File does not exist: ${file.absolutePath}")
    }
  }

  /**
   * Reveals a file in Finder (macOS) or opens its parent directory (other platforms).
   * On macOS, this will open Finder and select the file.
   * On other platforms, it will open the parent directory containing the file.
   */
  fun revealFileInFinder(file: File) {
    if (!file.exists()) {
      println("File does not exist: ${file.absolutePath}")
      return
    }

    try {
      val osName = System.getProperty("os.name").lowercase()
      when {
        osName.contains("mac") -> {
          // macOS: Use 'open -R' to reveal the file in Finder
          Runtime.getRuntime().exec(arrayOf("open", "-R", file.absolutePath))
        }

        osName.contains("win") -> {
          // Windows: Use explorer /select to highlight the file
          Runtime.getRuntime().exec(arrayOf("explorer.exe", "/select,", file.absolutePath))
        }

        else -> {
          // Linux and other platforms: Just open the parent directory
          if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().open(file.parentFile)
          }
        }
      }
    } catch (e: Exception) {
      println("Failed to reveal file in Finder: ${e.message}")
      e.printStackTrace()
      // Fallback: just open the parent directory
      try {
        if (Desktop.isDesktopSupported()) {
          Desktop.getDesktop().open(file.parentFile)
        }
      } catch (fallbackException: Exception) {
        println("Fallback also failed: ${fallbackException.message}")
        fallbackException.printStackTrace()
      }
    }
  }

  /**
   * Gets the Goose config file path.
   * @return The Goose config file: ~/.config/goose/config.yaml
   */
  fun getGooseConfigFile(): File {
    return File(System.getProperty("user.home"), ".config/goose/config.yaml")
  }

  /**
   * Result of ensuring the Trailblaze extension is installed in Goose.
   */
  sealed class GooseExtensionResult {
    /** Extension was already installed with matching type and URI */
    data object AlreadyInstalled : GooseExtensionResult()

    /** Extension was successfully added to the config */
    data object Added : GooseExtensionResult()

    /** Goose config file was not found */
    data object ConfigNotFound : GooseExtensionResult()

    /** An error occurred while processing the config */
    data class Error(val message: String) : GooseExtensionResult()
  }

  /**
   * Ensures the Trailblaze extension is installed in the Goose config.
   * Checks if an extension with matching type and URI already exists.
   * If not found, adds the trailblaze extension to the config.
   *
   * @return [GooseExtensionResult] indicating the outcome
   */
  @Suppress("UNCHECKED_CAST")
  fun ensureTrailblazeExtensionInstalledInGoose(): GooseExtensionResult {
    val configFile = getGooseConfigFile()

    if (!configFile.exists()) {
      println("Goose config file not found at: ${configFile.absolutePath}")
      return GooseExtensionResult.ConfigNotFound
    }

    return try {
      val yaml = Yaml()
      val config: MutableMap<String, Any> = configFile.inputStream().use { yaml.load(it) }
        ?: mutableMapOf()

      // Get or create extensions map
      val extensions = config.getOrPut("extensions") { mutableMapOf<String, Any>() }
        as? MutableMap<String, Any>
        ?: return GooseExtensionResult.Error("Invalid extensions format in config")

      // Check if an extension with matching type and URI already exists
      val targetType = TrailblazeGooseExtension.type
      val targetUri = TrailblazeGooseExtension.uri

      val alreadyExists = extensions.values.any { ext ->
        val extMap = ext as? Map<*, *> ?: return@any false
        val extType = extMap["type"] as? String
        val extUri = extMap["uri"] as? String
        extType == targetType && extUri == targetUri
      }

      if (alreadyExists) {
        println("Trailblaze extension already installed in Goose config")
        return GooseExtensionResult.AlreadyInstalled
      }

      // Add the trailblaze extension
      val extensionConfig = mutableMapOf(
        "enabled" to TrailblazeGooseExtension.enabled,
        "type" to TrailblazeGooseExtension.type,
        "name" to TrailblazeGooseExtension.name,
        "description" to TrailblazeGooseExtension.description,
        "uri" to TrailblazeGooseExtension.uri,
        "envs" to TrailblazeGooseExtension.envs,
        "env_keys" to TrailblazeGooseExtension.env_keys,
        "timeout" to TrailblazeGooseExtension.timeout,
        "bundled" to TrailblazeGooseExtension.bundled,
      )

      extensions["trailblaze"] = extensionConfig

      // Write the updated config back
      val dumperOptions = DumperOptions().apply {
        defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
        isPrettyFlow = true
        indent = 2
        indicatorIndent = 0
      }
      val yamlWriter = Yaml(dumperOptions)

      FileWriter(configFile).use { writer ->
        yamlWriter.dump(config, writer)
      }

      println("Trailblaze extension added to Goose config")
      GooseExtensionResult.Added
    } catch (e: Exception) {
      println("Error processing Goose config: ${e.message}")
      e.printStackTrace()
      GooseExtensionResult.Error(e.message ?: "Unknown error")
    }
  }

  /**
   * Opens Goose with the Trailblaze recipe.
   * Ensures the Trailblaze extension is installed before opening.
   * @param activities Optional list of activities to include. Defaults to [defaultOpenSourceActivities].
   */
  @OptIn(ExperimentalEncodingApi::class)
  fun openGoose(activities: List<String> = defaultOpenSourceActivities) {
    // Ensure the extension is installed before opening Goose
    ensureTrailblazeExtensionInstalledInGoose()

    val recipe = createGooseRecipe(activities)
    val recipeJsonString = gooseRecipeJson.encodeToString(GooseRecipe.serializer(), recipe)
    val recipeBase64 = Base64.encode(recipeJsonString.toByteArray())
    val recipeEncoded = URLEncoder.encode(recipeBase64, Charsets.UTF_8)
    val gooseUrl = "goose://recipe?config=$recipeEncoded"
    println(gooseUrl)
    openInDefaultBrowser(gooseUrl)
  }
}
