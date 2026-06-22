package xyz.block.trailblaze.ui

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlMap
import com.sun.jna.NativeLibrary
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import xyz.block.trailblaze.bundle.yaml.YamlEmitter
import xyz.block.trailblaze.util.DesktopOsType
import xyz.block.trailblaze.devices.TrailblazeDevicePort
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
import xyz.block.trailblaze.util.Console

object TrailblazeDesktopUtil {

  private val trailblazeAppIcon by lazy {
    ImageIO.read(TrailblazeDesktopUtil::class.java.classLoader.getResource("icons/icon.png"))
  }

  /**
   * Aborts startup with a clear error if the current OS+arch is not one we ship
   * Skiko native libraries for in the uber JAR. Without this check, an unsupported
   * host would fail several layers deep inside Skiko's JNI loader the first time
   * anything touched WebP screenshot encoding — typically as
   * `LibraryLoadException: Cannot find libskiko-<os>-<arch>.so.sha256`.
   *
   * Supported: macOS Apple Silicon (arm64), Linux x64, Linux arm64.
   * Unsupported: Intel macOS, Windows, FreeBSD, and any other OS/arch — deliberately
   * omitted from the per-OS `skiko-awt-runtime-*` declarations in this module's build
   * to keep the uber JAR small.
   *
   * Reads `os.name` / `os.arch` directly rather than going through `DesktopOsType`'s
   * mac/Windows/Linux trichotomy: the latter classifies every non-Mac/non-Windows host
   * as Linux, which would let a FreeBSD or Linux-PowerPC host pass this gate and fail
   * later inside the JNI loader instead of here. We also require an explicit
   * `x86_64` / `amd64` / `aarch64` / `arm64` value so unusual Linux architectures
   * (s390x, ppc64le, riscv64) are rejected up front.
   *
   * On failure: prints the message to stderr via [Console.error] and `exitProcess(1)`
   * — same pattern as [xyz.block.trailblaze.host.WorkspaceCompileBootstrap.bootstrapOrExit] —
   * so users see a clean message instead of an uncaught-exception stack trace.
   *
   * Called once per JVM at the top of [xyz.block.trailblaze.cli.TrailblazeCli.run].
   * That funnel covers every entry point: CLI subcommands, the daemon
   * (`app start`), MCP server, and the desktop GUI.
   */
  fun assertSupportedPlatform() {
    val osName = System.getProperty("os.name") ?: ""
    val osArch = System.getProperty("os.arch") ?: ""
    val osNameLower = osName.lowercase()
    val osArchLower = osArch.lowercase()

    val isMacKernel = osNameLower.contains("mac")
    val isLinuxKernel = osNameLower.contains("linux")
    val isX86_64 = osArchLower == "x86_64" || osArchLower == "amd64"
    val isArm64 = osArchLower == "aarch64" || osArchLower == "arm64"

    val supported = (isMacKernel && isArm64) || (isLinuxKernel && (isX86_64 || isArm64))
    if (supported) return

    Console.error(
      buildString {
        appendLine("Trailblaze does not support this platform: $osName ($osArch).")
        appendLine("Supported platforms:")
        appendLine("  - macOS Apple Silicon (arm64)")
        appendLine("  - Linux x64 (x86_64 / amd64)")
        append("  - Linux arm64 (aarch64)")
      },
    )
    kotlin.system.exitProcess(1)
  }

  const val DOT_TRAILBLAZE_DIR_NAME: String = ".trailblaze"

  /**
   * The filename for Trailblaze settings.
   */
  const val SETTINGS_FILENAME = "trailblaze-settings.json"

  /**
   * Subdirectory (relative to the app data dir) where the daemon caches bundled
   * inline scripted tools. Files inside are named by SHA-256 of the source `.ts`
   * bytes, so a cache hit on unchanged source skips re-bundling. See
   * `DaemonScriptedToolBundler` for the bundling pipeline.
   *
   * **Operational note — unbounded growth.** The cache currently has no automatic
   * cleanup: every distinct `.ts` source SHA accumulates a `<sha>.bundle.js` entry
   * across daemon restarts. A developer iterating on tool variants over months
   * can collect many MBs to GBs in this directory. The bundles themselves are
   * small (single-digit KB each in typical cases), so the growth is mostly from
   * source-version churn, not from one large entry. If oncall sees disk pressure
   * pointing at this directory, `rm -rf $HOME/.trailblaze/cache/scripted-bundles`
   * is safe — the next daemon start will rebundle on demand. An automatic
   * age/LRU cleanup is tracked as a follow-up to #2749.
   */
  const val SCRIPTED_BUNDLES_CACHE_SUBDIR: String = "cache/scripted-bundles"

  /**
   * Gets the default app data directory path.
   * @return The default app data directory: ~/.trailblaze
   */
  fun getDefaultAppDataDirectory(): File {
    // TRAILBLAZE_HOME lets multiple daemons on one host isolate their state dir (logs, TLS
    // keystore); without it concurrent daemons race the shared keystore and one dies on boot.
    System.getenv("TRAILBLAZE_HOME")?.takeIf { it.isNotBlank() }?.let { return File(it) }
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
   * Gets the desktop application logs directory.
   * This is for the desktop app's own runtime logs (e.g. logback output),
   * separate from the Trailblaze session/test logs.
   * @return The desktop logs directory: ~/.trailblaze/desktop-logs
   */
  fun getDesktopLogsDirectory(): File {
    return File(getDefaultAppDataDirectory(), "desktop-logs").apply { mkdirs() }
  }

  /**
   * Returns the path to the daemon's combined stdout/stderr log file.
   *
   * Centralized here (rather than constructed at each call site) so the daemon,
   * MCP proxy, and any tooling that surfaces "where do I look when the daemon
   * dies?" all agree on a single canonical location.
   *
   * @return `~/.trailblaze/daemon.log` — directory is created if missing.
   */
  fun getDaemonLogFile(): File {
    return File(getDefaultAppDataDirectory().apply { mkdirs() }, "daemon.log")
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
      Taskbar.getTaskbar().iconImage = trailblazeAppIcon
    }
  }

  /**
   * Shows or hides Trailblaze in the macOS Dock and app switcher.
   *
   * A hidden Trailblaze window should behave as a menu-bar accessory: the daemon and tray icon
   * keep running, but there is no inert Dock icon. Switching back to the regular activation
   * policy before showing the window restores normal Dock and app-switcher behavior.
   *
   * AWT exposes APIs for setting the Dock icon image, but not for changing the application's
   * activation policy. Use the Objective-C runtime to call `NSApplication.setActivationPolicy`.
   * Other desktop platforms intentionally keep their existing taskbar behavior.
   */
  internal fun setDockIconVisible(visible: Boolean) {
    if (DesktopOsType.current() != DesktopOsType.MAC_OS) return

    try {
      MacOsApplication.setActivationPolicy(
        if (visible) MacOsApplication.REGULAR else MacOsApplication.ACCESSORY,
      )
      if (visible) {
        // Switching from accessory back to regular recreates the Dock tile with the JVM
        // executable's generic icon. Reapply Trailblaze's image after the policy transition.
        setAppConfigForTrailblaze()
      }
    } catch (e: Exception) {
      // Losing the dynamic Dock behavior should not take down the daemon or its tray icon.
      Console.log("Unable to update the macOS Dock icon visibility: ${e.message}")
    }
  }

  /**
   * Open [url] in the OS default browser. Returns `true` on success, `false` if the
   * platform doesn't support the BROWSE action or the underlying call threw (malformed
   * URL, missing X server on Linux, etc.). Callers that care about the outcome — e.g.
   * [xyz.block.trailblaze.cli.ShowCommand], which must distinguish "browser opened" from
   * "browser failed silently" for its exit code — should branch on the return value.
   * UI fire-and-forget callers can keep ignoring the return without a behavior change.
   */
  fun openInDefaultBrowser(url: String): Boolean {
    return try {
      if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
        Desktop.getDesktop().browse(URI(url))
        true
      } else {
        Console.log("[TrailblazeDesktopUtil] Desktop.BROWSE not supported on this platform.")
        false
      }
    } catch (e: Exception) {
      Console.log("[TrailblazeDesktopUtil] openInDefaultBrowser failed for $url: ${e.message}")
      false
    }
  }

  fun openInFileBrowser(file: File) {
    if (file.exists()) {
      Desktop.getDesktop().open(file)
    } else {
      Console.log("File does not exist: ${file.absolutePath}")
    }
  }

  /**
   * Reveals a file in Finder (macOS) or opens its parent directory (other platforms).
   * On macOS, this will open Finder and select the file.
   * On other platforms, it will open the parent directory containing the file.
   */
  fun revealFileInFinder(file: File) {
    if (!file.exists()) {
      Console.log("File does not exist: ${file.absolutePath}")
      return
    }

    try {
      when (DesktopOsType.current()) {
        DesktopOsType.MAC_OS -> {
          // macOS: Use 'open -R' to reveal the file in Finder
          Runtime.getRuntime().exec(arrayOf("open", "-R", file.absolutePath))
        }

        DesktopOsType.WINDOWS -> {
          // Windows: Use explorer /select to highlight the file
          Runtime.getRuntime().exec(arrayOf("explorer.exe", "/select,", file.absolutePath))
        }

        DesktopOsType.LINUX -> {
          // Linux: Just open the parent directory
          if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().open(file.parentFile)
          }
        }
      }
    } catch (e: Exception) {
      Console.log("Failed to reveal file in Finder: ${e.message}")
      e.printStackTrace()
      // Fallback: just open the parent directory
      try {
        if (Desktop.isDesktopSupported()) {
          Desktop.getDesktop().open(file.parentFile)
        }
      } catch (fallbackException: Exception) {
        Console.log("Fallback also failed: ${fallbackException.message}")
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
   * **YAML library: kaml.** Uses kaml's tree API to read the user-owned config (which has
   * arbitrary structure — third-party extensions, custom fields), converts to a mutable
   * Kotlin tree, splices in the Trailblaze extension entry, and re-emits via the shared
   * `xyz.block.trailblaze.bundle.yaml.YamlEmitter`. SnakeYAML used to handle this round-trip
   * in one shot via its mutable Map representation; kaml's tree types are immutable, hence
   * the conversion step. The trade is acceptable: Goose-config interop is a one-off
   * integration, the file format is simple (no multi-doc, no anchors, no flow style in
   * practice), and the resulting dependency surface is one library instead of two.
   *
   * @return [GooseExtensionResult] indicating the outcome
   */
  fun ensureTrailblazeExtensionInstalledInGoose(): GooseExtensionResult =
    ensureTrailblazeExtensionInstalledIn(getGooseConfigFile())

  /**
   * Internal seam for tests — same logic as the no-arg public function but operates on
   * an arbitrary config-file path. Public-facing callers use the path from
   * [getGooseConfigFile]; tests pass a fixture path inside a temp dir.
   */
  internal fun ensureTrailblazeExtensionInstalledIn(configFile: File): GooseExtensionResult {
    if (!configFile.exists()) {
      Console.log("Goose config file not found at: ${configFile.absolutePath}")
      return GooseExtensionResult.ConfigNotFound
    }

    return try {
      val yaml = Yaml(configuration = YamlConfiguration(strictMode = false, encodeDefaults = false))
      val rootNode = yaml.parseToYamlNode(configFile.readText())
      val rootMapNode = rootNode as? YamlMap
        ?: return GooseExtensionResult.Error("Goose config root is not a YAML map")
      val config = YamlEmitter.yamlMapToMutable(rootMapNode)

      // Get or create extensions map.
      @Suppress("UNCHECKED_CAST")
      val extensions = (config["extensions"] as? MutableMap<String, Any?>)
        ?: run {
          val fresh = LinkedHashMap<String, Any?>()
          config["extensions"] = fresh
          fresh
        }

      // Check if an extension with matching type and URI already exists.
      val targetType = TrailblazeGooseExtension.type
      val targetUri = TrailblazeGooseExtension.uri

      val alreadyExists = extensions.values.any { ext ->
        val extMap = ext as? Map<*, *> ?: return@any false
        val extType = extMap["type"] as? String
        val extUri = extMap["uri"] as? String
        extType == targetType && extUri == targetUri
      }

      if (alreadyExists) {
        Console.log("Trailblaze extension already installed in Goose config")
        return GooseExtensionResult.AlreadyInstalled
      }

      // Add the trailblaze extension.
      val extensionConfig = LinkedHashMap<String, Any?>().apply {
        this["enabled"] = TrailblazeGooseExtension.enabled
        this["type"] = TrailblazeGooseExtension.type
        this["name"] = TrailblazeGooseExtension.name
        this["description"] = TrailblazeGooseExtension.description
        this["uri"] = TrailblazeGooseExtension.uri
        this["envs"] = TrailblazeGooseExtension.envs
        this["env_keys"] = TrailblazeGooseExtension.env_keys
        this["timeout"] = TrailblazeGooseExtension.timeout
        this["bundled"] = TrailblazeGooseExtension.bundled
      }

      extensions["trailblaze"] = extensionConfig

      // Write the updated config back via the shared emitter. The `Any?`-typed mutable
      // tree doesn't fit kaml's typed-serializer model, so we go through `YamlEmitter`
      // (which produces block-style 2-space indent, list dashes flush with parent key).
      FileWriter(configFile).use { writer ->
        writer.write(YamlEmitter.renderMap(config))
      }

      Console.log("Trailblaze extension added to Goose config")
      GooseExtensionResult.Added
    } catch (e: Exception) {
      Console.log("Error processing Goose config: ${e.message}")
      e.printStackTrace()
      GooseExtensionResult.Error(e.message ?: "Unknown error")
    }
  }

  /**
   * Opens Goose with the Trailblaze recipe.
   * Ensures the Trailblaze extension is installed before opening.
   * @param port The HTTP port the Trailblaze server is running on, used to construct the default
   *   recipe. Ignored when a custom [recipe] is provided.
   * @param recipe The Goose recipe to launch with. Defaults to the base Trailblaze recipe with
   *   [defaultOpenSourceActivities] configured for [port].
   */
  @OptIn(ExperimentalEncodingApi::class)
  fun openGoose(
    port: Int = TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTP_PORT,
    recipe: GooseRecipe = createGooseRecipe(defaultOpenSourceActivities, port),
  ) {
    // Ensure the extension is installed before opening Goose
    ensureTrailblazeExtensionInstalledInGoose()

    val recipeJsonString = gooseRecipeJson.encodeToString(GooseRecipe.serializer(), recipe)
    val recipeBase64 = Base64.encode(recipeJsonString.toByteArray())
    val recipeEncoded = URLEncoder.encode(recipeBase64, Charsets.UTF_8)
    val gooseUrl = "goose://recipe?config=$recipeEncoded"
    Console.log(gooseUrl)
    openInDefaultBrowser(gooseUrl)
  }

  private object MacOsApplication {
    const val REGULAR = 0L
    const val ACCESSORY = 1L

    private val objectiveC by lazy { NativeLibrary.getInstance("objc") }
    private val getClass by lazy { objectiveC.getFunction("objc_getClass") }
    private val registerSelector by lazy { objectiveC.getFunction("sel_registerName") }
    private val sendMessage by lazy { objectiveC.getFunction("objc_msgSend") }

    fun setActivationPolicy(policy: Long) {
      val applicationClass = getClass.invokePointer(arrayOf("NSApplication"))
      check(applicationClass != Pointer.NULL) { "NSApplication class is unavailable" }

      val application = sendMessage.invokePointer(
        arrayOf(applicationClass, selector("sharedApplication")),
      )
      check(application != Pointer.NULL) { "NSApplication.sharedApplication is unavailable" }

      val succeeded = sendMessage.invokeInt(
        arrayOf(application, selector("setActivationPolicy:"), NativeLong(policy)),
      ) != 0
      check(succeeded) { "NSApplication rejected activation policy $policy" }
    }

    private fun selector(name: String): Pointer =
      registerSelector.invokePointer(arrayOf(name))
  }
}
