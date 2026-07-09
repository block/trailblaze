package xyz.block.trailblaze.cli

import kotlinx.serialization.json.Json
import xyz.block.trailblaze.api.EffectiveScreenshotScalingConfig
import xyz.block.trailblaze.api.TrailblazeImageFormat
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.logs.client.TrailblazeJson
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
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

/**
 * Sentinel value indicating no LLM is configured. Mirrors the framework's
 * [TrailblazeLlmProvider.NONE] so that `trailblaze config llm none` resolves
 * to the NoOpLlmClient and fails fast on any live inference attempt.
 */
val LLM_NONE: String = TrailblazeLlmProvider.NONE.id

private fun String.isLlmNoneValue(): Boolean = equals(LLM_NONE, ignoreCase = true)

/**
 * Lower bound on `screenshot-quality`. Quality 0 is technically valid for JPEG/WebP encoders
 * but produces images so degraded the LLM can't read them — a footgun that's never useful in
 * practice. Shared between CLI parsing and the desktop Settings slider.
 */
internal const val SCREENSHOT_QUALITY_MIN: Float = 0.05f

/** Registry of all config keys supported by `trailblaze config <key> [<value>]`. */
val CONFIG_KEYS: Map<String, ConfigKey> = listOf(
  ConfigKey(
    name = "llm",
    description = "LLM provider and model (shorthand: provider/model)",
    validValues = "provider/model (e.g., openai/gpt-4-1, anthropic/claude-sonnet-4-20250514) or 'none' to disable",
    get = { config ->
      if (config.llmProvider == LLM_NONE) LLM_NONE
      else "${config.llmProvider}/${config.llmModel}"
    },
    set = { config, value ->
      if (value.isLlmNoneValue()) {
        config.copy(llmProvider = LLM_NONE, llmModel = LLM_NONE)
      } else {
        val parts = value.split("/", limit = 2)
        if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) {
          null
        } else {
          config.copy(llmProvider = parts[0].lowercase(), llmModel = parts[1])
        }
      }
    },
  ),
  ConfigKey(
    name = "llm-provider",
    description = "LLM provider",
    validValues = "openai, anthropic, google, ollama, openrouter, etc. or 'none' to disable",
    get = { config -> config.llmProvider },
    set = { config, value ->
      if (value.isLlmNoneValue()) {
        config.copy(llmProvider = LLM_NONE, llmModel = LLM_NONE)
      } else {
        config.copy(llmProvider = value.lowercase())
      }
    },
  ),
  ConfigKey(
    name = "llm-model",
    description = "LLM model ID",
    validValues = "e.g., gpt-4-1, claude-sonnet-4-20250514, gemini-3-flash or 'none' to disable",
    get = { config -> config.llmModel },
    set = { config, value ->
      if (value.isLlmNoneValue()) {
        config.copy(llmProvider = LLM_NONE, llmModel = LLM_NONE)
      } else {
        config.copy(llmModel = value)
      }
    },
  ),
  ConfigKey(
    name = "target",
    description = "Target app for device connections and custom tools",
    validValues = "App target ID. Run 'trailblaze config target' to see all.",
    get = { config -> config.selectedTargetAppId ?: "(not set)" },
    set = { config, value ->
      // Preserve caller-supplied casing so lowerCamelCase trailmap ids (e.g. `playwrightSample`)
      // round-trip correctly. The validator widened to accept uppercase letters in the
      // 2026-05-27 trailmap-scoped tool naming work — forcing `.lowercase()` here would defeat
      // that by silently flattening the id before lookup.
      if (!TrailblazeHostAppTarget.isValidId(value)) {
        null
      } else {
        config.copy(selectedTargetAppId = value)
      }
    },
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
    validValues = TrailblazeDriverType.selectableForPlatform(TrailblazeDevicePlatform.ANDROID)
      .joinToString(", ") { it.cliShortName!! },
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
    validValues = TrailblazeDriverType.selectableForPlatform(TrailblazeDevicePlatform.IOS)
      .joinToString(", ") { it.cliShortName!! },
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
    name = "self-heal",
    description = "Enable/disable self-heal (AI takes over) when recorded steps fail",
    validValues = "true, false",
    get = { config -> config.selfHealEnabled.toString() },
    set = { config, value ->
      value.toBooleanStrictOrNull()?.let { config.copy(selfHealEnabled = it) }
    },
  ),
  ConfigKey(
    // Rollout gate for the unified-format recorder. Off (default) keeps the legacy
    // per-classifier save-back byte-identical; on, new recordings merge into the unified
    // trail.yaml. The default flips once the surrounding tooling fully supports unified.
    name = "unified-recordings",
    description = "Save new recordings in the unified trail.yaml format instead of legacy <classifier>.trail.yaml siblings",
    validValues = "true, false",
    get = { config -> config.unifiedRecordingsEnabled.toString() },
    set = { config, value ->
      value.toBooleanStrictOrNull()?.let { config.copy(unifiedRecordingsEnabled = it) }
    },
  ),
  ConfigKey(
    // Per-step natural-language description is what self-heal retries against
    // when a recorded selector goes stale. Permissive default keeps ad-hoc
    // tire-kicking unblocked; flip to true for serious authoring so every
    // recorded tool call captures intent.
    name = "require-steps",
    description = "Require -s/--step on every tool / step / ask / verify call (default: false)",
    validValues = "true, false",
    get = { config -> config.requireSteps.toString() },
    set = { config, value ->
      value.toBooleanStrictOrNull()?.let { config.copy(requireSteps = it) }
    },
  ),
  ConfigKey(
    // Per-machine fallback below the workspace `trailblaze.yaml` `defaults.max-llm-calls`.
    // Use case: a developer wants a lower local cap for a debugging session without
    // editing the committed workspace file. The CLI flag and env var still win when set.
    name = "max-llm-calls",
    description = "Per-objective LLM call cap for the legacy TRAILBLAZE_RUNNER agent",
    validValues = "positive integer, or 'unset' to clear",
    get = { config -> config.maxLlmCalls?.toString() ?: "(not set)" },
    set = { config, value ->
      if (value.equals("unset", ignoreCase = true)) {
        config.copy(maxLlmCalls = null)
      } else {
        val parsed = value.toIntOrNull()
        if (parsed == null || parsed <= 0) null else config.copy(maxLlmCalls = parsed)
      }
    },
  ),
  ConfigKey(
    // The annotated (set-of-mark) screenshot is always sent to the LLM regardless
    // of this flag — model accuracy depends on it. This flag only controls which
    // variant is persisted to logs for inspection. Flip off when downstream tooling
    // (e.g. waypoint authoring) wants clean screenshots in committed artifacts.
    name = "annotated-screenshots",
    description = "Save set-of-mark annotated screenshots to logs (LLM always receives annotated)",
    validValues = "true, false",
    get = { config -> config.saveAnnotatedScreenshots.toString() },
    set = { config, value ->
      value.toBooleanStrictOrNull()?.let { config.copy(saveAnnotatedScreenshots = it) }
    },
  ),
  ConfigKey(
    name = "mode",
    description = "CLI working mode: trail (author reproducible trails) or blaze (explore device)",
    validValues = "trail, blaze",
    get = { config -> config.cliMode ?: "not set" },
    set = { config, value ->
      val mode = value.lowercase()
      if (mode in setOf("trail", "blaze")) config.copy(cliMode = mode) else null
    },
  ),
  ConfigKey(
    name = "web-headless",
    description = "Default for `--headless` on web devices (CLI flag still wins when explicitly passed)",
    validValues = "true, false",
    // Backed by the existing `showWebBrowser` setting (semantics inverted): showing the
    // browser means *not* headless. Reusing that field keeps the CLI and desktop-app
    // toggles in sync — flipping the toggle in either surface affects both.
    get = { config -> (!config.showWebBrowser).toString() },
    set = { config, value ->
      value.toBooleanStrictOrNull()?.let { headless -> config.copy(showWebBrowser = !headless) }
    },
  ),
  ConfigKey(
    name = "device",
    description = "Default device platform for CLI commands",
    validValues = "android, ios, web",
    get = { config -> config.cliDevicePlatform?.lowercase() ?: "not set" },
    set = { config, value ->
      val platform = value.uppercase()
      if (platform in setOf("ANDROID", "IOS", "WEB")) {
        config.copy(cliDevicePlatform = platform)
      } else {
        null
      }
    },
  ),
  ConfigKey(
    // Per-machine screenshot encoding. Workspace yaml `defaults.screenshot.format` still
    // wins when it's set; this kicks in for everything else (host screenshots in the
    // timeline, scaled bytes sent to the LLM when the workspace is silent).
    name = "screenshot-format",
    description = "Image format used for screenshots sent to the LLM and shown in the timeline",
    validValues = "png, jpeg, webp, or 'unset' to use the framework default (webp)",
    get = { config -> config.screenshotImageFormat?.name?.lowercase() ?: "(framework default)" },
    set = { config, value ->
      if (value.equals("unset", ignoreCase = true)) {
        config.copy(screenshotImageFormat = null)
      } else {
        TrailblazeImageFormat.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
          ?.let { config.copy(screenshotImageFormat = it) }
      }
    },
  ),
  ConfigKey(
    // Stored as two ints (longer/shorter side) on disk to match the underlying
    // ScreenshotScalingConfig shape, but parsed/printed as WIDTHxHEIGHT here so the CLI
    // surface matches the workspace yaml `max_dimensions: 1536x768` shape.
    name = "screenshot-max-dimensions",
    description = "Max screenshot dimensions as <longer>x<shorter> (e.g. 1536x768, 2048x1024)",
    validValues = "WIDTHxHEIGHT (positive ints), or 'unset' to use the framework default (1536x768)",
    get = { config ->
      val longer = config.screenshotMaxLongerSide
      val shorter = config.screenshotMaxShorterSide
      if (longer == null && shorter == null) "(framework default)"
      else "${longer ?: "?"}x${shorter ?: "?"}"
    },
    set = { config, value ->
      if (value.equals("unset", ignoreCase = true)) {
        config.copy(screenshotMaxLongerSide = null, screenshotMaxShorterSide = null)
      } else {
        val parts = value.split("x", ignoreCase = true)
        val longer = parts.getOrNull(0)?.trim()?.toIntOrNull()
        val shorter = parts.getOrNull(1)?.trim()?.toIntOrNull()
        if (parts.size != 2 || longer == null || shorter == null || longer <= 0 || shorter <= 0) {
          null
        } else {
          config.copy(screenshotMaxLongerSide = longer, screenshotMaxShorterSide = shorter)
        }
      }
    },
  ),
  ConfigKey(
    name = "screenshot-quality",
    description = "Compression quality 0.05..1.0 for lossy formats (jpeg, webp); ignored for png",
    validValues = "0.05..1.0, or 'unset' to use the framework default (0.80)",
    get = { config -> config.screenshotCompressionQuality?.toString() ?: "(framework default)" },
    set = { config, value ->
      if (value.equals("unset", ignoreCase = true)) {
        config.copy(screenshotCompressionQuality = null)
      } else {
        val parsed = value.toFloatOrNull()
        // Lower bound is 0.05 (not 0.0) — quality 0 produces effectively unreadable
        // images on JPEG/WebP that the LLM can't interpret, so it's never a useful
        // production value. The desktop UI slider's lower bound matches.
        if (parsed == null || parsed < SCREENSHOT_QUALITY_MIN || parsed > 1f) null
        else config.copy(screenshotCompressionQuality = parsed)
      }
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
   * Gets the path to the settings file, matching the resolution order used by the Block
   * desktop app (see BlockTrailblazeDesktopAppConfig.defaultAppDataDir):
   * 1. `trailblaze.appdata.dir` system property (set by the Homebrew wrapper)
   * 2. Git repository root `<repo>/.trailblaze/trailblaze-settings.json` (dev workflow)
   * 3. `~/.trailblaze/trailblaze-settings.json` (default)
   *
   * Without this, the CLI would write to the home-dir file while the daemon — running in
   * a git repo — reads from the repo-local one, silently dropping every `config` change.
   */
  fun getSettingsFile(): File {
    val systemPropertyDir = System.getProperty("trailblaze.appdata.dir")
    val appDataDir = when {
      systemPropertyDir != null -> File(systemPropertyDir)
      else -> findGitRoot()?.let { File(it, TrailblazeDesktopUtil.DOT_TRAILBLAZE_DIR_NAME) }
        ?: TrailblazeDesktopUtil.getDefaultAppDataDirectory()
    }
    return File(appDataDir, TrailblazeDesktopUtil.SETTINGS_FILENAME)
  }

  /**
   * Cache the git-root lookup for the lifetime of this JVM. `git rev-parse --show-toplevel`
   * is cheap but nonzero (subprocess fork + IO), and [getSettingsFile] is called many times
   * per CLI invocation. The repo root doesn't move within a single process.
   *
   * Sentinel: we cache the nullability too, so repeated "not in a git repo" lookups don't
   * re-shell. Marker value `MISSING_GIT_ROOT` distinguishes "not yet resolved" from "resolved
   * to null".
   */
  private val MISSING_GIT_ROOT = File("")
  @Volatile private var cachedGitRoot: File? = null

  private fun findGitRoot(): File? {
    val cached = cachedGitRoot
    if (cached != null) {
      return if (cached === MISSING_GIT_ROOT) null else cached
    }
    val resolved = resolveGitRoot()
    cachedGitRoot = resolved ?: MISSING_GIT_ROOT
    return resolved
  }

  private fun resolveGitRoot(): File? = try {
    val process = ProcessBuilder("git", "rev-parse", "--show-toplevel")
      .redirectErrorStream(false)
      .start()
    val finished = process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
    if (!finished) {
      process.destroyForcibly()
      null
    } else if (process.exitValue() == 0) {
      File(process.inputStream.bufferedReader().readText().trim()).takeIf { it.isDirectory }
    } else {
      null
    }
  } catch (_: Exception) {
    null
  }
  
  /**
   * Reads the current config from disk, or returns null if not found.
   *
   * Applies in-memory hydration for forward-compat: older config files may
   * predate [SavedTrailblazeAppConfig.selectedTargetAppId] and deserialize to
   * `null`. We materialize the default target (`"default"`) on read instead of
   * writing to disk, so that a plain setup check is read-only. The value is
   * persisted naturally the next time the user mutates their config.
   *
   * If you need the exact on-disk state (e.g. for a diagnostic command that
   * shows the raw settings file), use [readConfigRaw] instead.
   */
  fun readConfig(): SavedTrailblazeAppConfig? =
    readConfigRaw()?.hydrateDefaults()?.also {
      // Seed the effective screenshot config from disk so standalone CLI processes
      // (no daemon) honour the user's saved overrides on the very first call.
      // The daemon's TrailblazeSettingsRepo collector keeps it fresh thereafter.
      // Pass null when nothing is overridden so the web path can fall back to its own default
      // (see EffectiveScreenshotScalingConfig.effectiveForWeb).
      EffectiveScreenshotScalingConfig.setEffectiveDefault(it.screenshotScalingConfigOrNull())
    }

  /**
   * Reads the config from disk without any hydration — returns exactly what
   * was deserialized. Intended for diagnostic tooling that needs to report the
   * true on-disk state (e.g. to distinguish "user explicitly set default" from
   * "field is missing and we're defaulting"). Normal callers should use
   * [readConfig].
   */
  fun readConfigRaw(): SavedTrailblazeAppConfig? {
    // When running inside the daemon (IPC fast path), the daemon's in-memory
    // appConfig is the source of truth — the on-disk file may lag behind it.
    // Wrap the bridge read so a daemon mid-init or partially-broken state falls
    // through to the on-disk file rather than propagating the exception.
    DaemonSettingsBridge.settingsRepo?.let { repo ->
      runCatching { repo.serverStateFlow.value.appConfig }
        .onFailure { Console.log("Daemon settings bridge read failed; falling back to file: ${it.message}") }
        .getOrNull()
        ?.let { return it }
    }
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

  private fun SavedTrailblazeAppConfig.hydrateDefaults(): SavedTrailblazeAppConfig {
    val derivedAppDataDir = effectiveAppDataDirectory()
    val existingDriverTypes = selectedTrailblazeDriverTypes
    val defaultDriverTypes = defaultDriverTypes()
    return copy(
      selectedTrailblazeDriverTypes = existingDriverTypes +
        defaultDriverTypes.filterKeys { it !in existingDriverTypes },
      selectedTargetAppId = selectedTargetAppId
        ?: TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget.id,
      logsDirectory = logsDirectory ?: derivedLogsDirectory(derivedAppDataDir),
      trailsDirectory = trailsDirectory ?: derivedTrailsDirectory(derivedAppDataDir),
      appDataDirectory = appDataDirectory ?: derivedAppDataDir.canonicalPath,
    )
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
   *
   * When a daemon is running and has installed the [DaemonSettingsBridge], the
   * write goes through the daemon's [xyz.block.trailblaze.ui.TrailblazeSettingsRepo]
   * instead — its `serverStateFlow` collector handles persistence. This avoids
   * the lost-update race where the daemon's auto-save would clobber a direct
   * file write with its stale in-memory copy.
   */
  fun writeConfig(config: SavedTrailblazeAppConfig) {
    DaemonSettingsBridge.settingsRepo?.let { repo ->
      repo.updateAppConfig { config }
      return
    }
    val file = getSettingsFile()
    file.parentFile?.mkdirs()
    file.writeText(json.encodeToString(SavedTrailblazeAppConfig.serializer(), config))
  }
  
  /**
   * Returns a default config if none exists.
   */
  fun defaultConfig(): SavedTrailblazeAppConfig {
    val appDataDir = effectiveAppDataDirectory()
    return SavedTrailblazeAppConfig(
      selectedTrailblazeDriverTypes = defaultDriverTypes(),
      selectedTargetAppId = TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget.id,
      logsDirectory = derivedLogsDirectory(appDataDir),
      trailsDirectory = derivedTrailsDirectory(appDataDir),
      appDataDirectory = appDataDir.canonicalPath,
    )
  }
  
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

  private fun defaultDriverTypes(): Map<TrailblazeDevicePlatform, TrailblazeDriverType> = mapOf(
    TrailblazeDevicePlatform.ANDROID to TrailblazeDriverType.DEFAULT_ANDROID,
    TrailblazeDevicePlatform.IOS to TrailblazeDriverType.IOS_HOST,
    TrailblazeDevicePlatform.WEB to TrailblazeDriverType.PLAYWRIGHT_NATIVE,
  )

  private fun effectiveAppDataDirectory(): File {
    return readConfigRaw()?.appDataDirectory
      ?.takeIf { it.isNotBlank() }
      ?.let(::File)
      ?: getSettingsFile().parentFile
  }

  private fun derivedLogsDirectory(appDataDir: File): String {
    val root = appDataDir.canonicalFile.parentFile ?: appDataDir.canonicalFile
    return File(root, "logs").canonicalPath
  }

  private fun derivedTrailsDirectory(appDataDir: File): String {
    val root = appDataDir.canonicalFile.parentFile ?: appDataDir.canonicalFile
    return File(root, "trails").canonicalPath
  }
  
  /**
   * Parse a per-platform driver override. Matches (case-insensitively) on either the enum
   * name (`ANDROID_ONDEVICE_ACCESSIBILITY`) or the short CLI name
   * ([TrailblazeDriverType.cliShortName], e.g. `accessibility`, `axe`). Returns null if the
   * value doesn't match any driver whose platform matches [platform] and whose short name
   * is non-null (i.e. is user-selectable from the CLI).
   */
  fun parseDriver(platform: TrailblazeDevicePlatform, driver: String): TrailblazeDriverType? {
    val normalized = driver.trim()
    return TrailblazeDriverType.selectableForPlatform(platform).firstOrNull {
      it.cliShortName.equals(normalized, ignoreCase = true) ||
        it.name.equals(normalized, ignoreCase = true)
    }
  }

  fun parseAndroidDriver(driver: String): TrailblazeDriverType? =
    parseDriver(TrailblazeDevicePlatform.ANDROID, driver)

  fun parseIosDriver(driver: String): TrailblazeDriverType? =
    parseDriver(TrailblazeDevicePlatform.IOS, driver)
  
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
