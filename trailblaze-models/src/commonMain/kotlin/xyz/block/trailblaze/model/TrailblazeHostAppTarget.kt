package xyz.block.trailblaze.model

import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.config.McpServerConfig
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.model.TrailblazeOnDeviceInstrumentationTarget.Companion.DEFAULT_ANDROID_ON_DEVICE
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import kotlin.reflect.KClass


abstract class TrailblazeHostAppTarget(
  /**
   * Used for artifact naming, CI builds and other persistent identifiers
   *
   * NOTE: Must be lowercase alphanumeric, no spaces or special characters
   */
  val id: String,

  /**
   * Human-readable name for display in the UI
   */
  val displayName: String,
) {

  init {
    require(isValidId(id)) { invalidIdMessage(id, displayName) }
  }

  abstract fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): Set<String>?

  protected abstract fun internalGetCustomToolsForDriver(driverType: TrailblazeDriverType): Set<KClass<out TrailblazeTool>>
  fun getCustomToolsForDriver(driverType: TrailblazeDriverType): Set<KClass<out TrailblazeTool>> =
    internalGetCustomToolsForDriver(driverType)

  /**
   * YAML-defined (`tools:` mode) tool names this target exposes for the given driver.
   *
   * Mirrors [getCustomToolsForDriver] but for tools whose behavior is composed in YAML
   * (no [KClass] backing). Default empty; YAML-backed targets populate it from their
   * toolsets and per-platform `tools:` lists so name-based toolset references
   * (e.g. `eraseText` in `core_interaction.yaml`) flow through the same way as
   * class-backed tools.
   */
  open fun getCustomYamlToolNamesForDriver(driverType: TrailblazeDriverType): Set<ToolName> = emptySet()

  /**
   * A named group of tools for discovery output. Allows targets to organize their
   * custom tools into logical groups (e.g., "onboarding", "checkout", "settings").
   *
   * [yamlToolNames] carries YAML-defined tool names (no [KClass] backing) so discovery
   * output can advertise them alongside [toolClasses]. Without this, name-only tools
   * referenced from toolset YAML (e.g. `eraseText`, `pressBack`) would silently drop
   * out of `toolbox` listings even though the executor accepts them.
   */
  data class ToolGroup(
    val id: String,
    val description: String,
    val toolClasses: Set<KClass<out TrailblazeTool>>,
    val yamlToolNames: Set<ToolName> = emptySet(),
  )

  /**
   * Override this to expose custom tools organized into logical groups for discovery.
   *
   * Default implementation wraps all custom tools in a single group named after the target.
   * Override in app-specific targets to provide meaningful grouping (e.g., separating
   * onboarding tools from checkout tools from settings tools).
   */
  open fun getCustomToolGroupsForDriver(driverType: TrailblazeDriverType): List<ToolGroup> {
    val tools = getCustomToolsForDriver(driverType)
    val yamlNames = getCustomYamlToolNamesForDriver(driverType)
    if (tools.isEmpty() && yamlNames.isEmpty()) return emptyList()
    return listOf(
      ToolGroup(
        id = id,
        description = "$displayName tools",
        toolClasses = tools,
        yamlToolNames = yamlNames,
      ),
    )
  }

  /**
   * Override this to exclude specific tools from the default tool set.
   * This is useful when you want to replace a default tool with a custom implementation.
   */
  open fun getExcludedToolsForDriver(driverType: TrailblazeDriverType): Set<KClass<out TrailblazeTool>> =
    emptySet()

  /**
   * YAML-defined (`tools:` mode) tool names this target wants to exclude for the given driver.
   *
   * Mirrors [getExcludedToolsForDriver] for tools that have no [KClass] backing. Without this,
   * a target YAML's `excluded_tools: [pressBack]` would silently drop on the floor at resolution
   * time — the parallel of the bug fixed for the inclusion side in `getCustomYamlToolNamesForDriver`.
   *
   * Default empty; YAML-backed targets populate from their per-platform `excluded_tools` lists.
   */
  open fun getExcludedYamlToolNamesForDriver(driverType: TrailblazeDriverType): Set<ToolName> = emptySet()

  /**
   * Toolset ids the target *declares* for the given driver — the positive list of toolset
   * names from `platforms.<key>.tool_sets:` in the target YAML, before any catalog
   * resolution. Drives pack-positive LLM tool resolution: callers pass the result to
   * [xyz.block.trailblaze.toolcalls.TrailblazeToolSetCatalog.resolveForDriver] instead of
   * dumping the entire driver-compatible catalog.
   *
   * Empty default for non-YAML targets and for targets that don't declare any toolsets on
   * a given driver. The implication of an empty list is "use only the catalog's
   * `always_enabled` toolsets" — that's the explicit, opt-in surface, not a kitchen sink.
   * Targets that need richer surfaces declare them explicitly.
   */
  open fun getDeclaredToolSetIdsForDriver(driverType: TrailblazeDriverType): List<String> = emptyList()

  /**
   * MCP server declarations for this target (Decision 038 / `mcp_servers:` in target YAML).
   *
   * Returns the raw [McpServerConfig] entries declared at the target root. The session-startup
   * wiring layer (in `:trailblaze-scripting-subprocess`) spawns each entry as a subprocess, runs
   * the MCP handshake, and registers advertised tools into the session's tool repo.
   *
   * Default is empty — Kotlin-code targets don't contribute MCP servers. YAML-backed targets
   * override to return [xyz.block.trailblaze.config.AppTargetYamlConfig.mcpServers].
   */
  open fun getMcpServers(): List<McpServerConfig> = emptyList()

  /**
   * Inline single-file scripted tools declared at the target root (`tools:` in target YAML).
   *
   * Host runners synthesize these into temporary MCP wrapper scripts and launch them through the
   * existing subprocess runtime. Default empty; YAML-backed targets override from config.
   */
  open fun getInlineScriptTools(): List<InlineScriptToolConfig> = emptyList()

  /**
   * Optional target-specific system-prompt template seeded into the LLM session. Default null;
   * YAML-backed targets return [xyz.block.trailblaze.config.AppTargetYamlConfig.systemPrompt],
   * Kotlin targets may override to load from a classpath resource.
   *
   * **Extension point.** This is an opt-in hook used by downstream rule frameworks (e.g.
   * Android on-device rule wrappers). Consumers using only the open-source surface don't need to
   * override — sessions without a target-specific prompt fall back to the framework default.
   */
  open fun getSystemPromptTemplate(): String? = null

  /**
   * When `true`, on-device app-id resolution falls back to the configured app id even when the
   * package isn't installed on the device. Use this for targets whose configured id is a stand-in
   * (e.g. the generic default target) rather than a real product package — the absence of the
   * package on the device is expected, not a failure.
   *
   * Real product targets MUST keep this `false` (the default) so a missing install fails fast at
   * rule construction with a clear "please install" error, instead of letting the test continue
   * and produce a less actionable failure when it tries to launch / force-stop a missing package.
   *
   * **Extension point.** This is consulted by downstream on-device rule frameworks during app-id
   * resolution. OSS-only consumers don't need to override — the default `false` is correct for
   * any target representing a real installed app.
   */
  open val allowsAppNotInstalled: Boolean = false

  fun getAllCustomToolClassesForSerialization(): Set<KClass<out TrailblazeTool>> =
    TrailblazeDriverType.entries.flatMap { trailblazeDriverType ->
      getCustomToolsForDriver(trailblazeDriverType)
    }.toSet()

  fun internalGetAndroidOnDeviceTarget(): TrailblazeOnDeviceInstrumentationTarget {
    return DEFAULT_ANDROID_ON_DEVICE
  }

  /**
   * We're provided with the original iOS Driver from Maestro
   * Then we are instantiating a downstream app-specific iOS driver, wrapped around the original
   *
   * @param originalIosDriver is actually of type "IOSDriver" and is provided by Maestro.
   *   NOTE: It is typed as [Any] because it's in KMP code and Maestro is JVM Only.
   * @return Return the original [originalIosDriver] or your custom "IOSDriver"
   */
  /**
   * Whether this app target provides a custom iOS driver via [getCustomIosDriverFactory].
   * When true, switching to/from this target requires releasing the persistent iOS device
   * connection so the driver gets recreated with the correct wrapper.
   */
  open val hasCustomIosDriver: Boolean = false

  open fun getCustomIosDriverFactory(trailblazeDeviceId: TrailblazeDeviceId, originalIosDriver: Any): Any =
    originalIosDriver

  fun getTrailblazeOnDeviceInstrumentationTarget(): TrailblazeOnDeviceInstrumentationTarget =
    internalGetAndroidOnDeviceTarget() ?: TrailblazeOnDeviceInstrumentationTarget.DEFAULT_ANDROID_ON_DEVICE

  /**
   * Returns comprehensive information about this app target as formatted text including:
   * - Driver types with their platforms and custom tool counts
   * - Installed app IDs for all platforms
   */
  fun getAppInfoText(supportedDrivers: Set<TrailblazeDriverType>): String = buildString {
    // Print installed app information
    appendLine("Apps Ids by Platform:")
    appendLine("-".repeat(40))

    TrailblazeDevicePlatform.entries.forEach { platform ->
      val appIds = getPossibleAppIdsForPlatform(platform)
      if (!appIds.isNullOrEmpty()) {
        appendLine("• ${platform.displayName}: ${appIds.joinToString(",")}")
      }
    }

    // Print Android on-device target information
    appendLine("\nAndroid On-Device Target:")
    appendLine("-".repeat(40))
    val androidTarget = getTrailblazeOnDeviceInstrumentationTarget()
    appendLine("• Test App ID: ${androidTarget.testAppId}")
    appendLine("• Test Class: ${androidTarget.fqTestName}")
  }

  data object DefaultTrailblazeHostAppTarget : TrailblazeHostAppTarget(
    id = "default",
    displayName = "Default",
  ) {
    override fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): Set<String>? = null

    override fun internalGetCustomToolsForDriver(driverType: TrailblazeDriverType): Set<KClass<out TrailblazeTool>> =
      setOf()
  }

  fun getAppIdIfInstalled(
    platform: TrailblazeDevicePlatform,
    installedAppIds: Set<String>
  ): String? {
    val installedAppId = getPossibleAppIdsForPlatform(platform)?.let { expectedAppIds ->
      expectedAppIds.firstOrNull { expectedAppId ->
        installedAppIds.contains(expectedAppId)
      }
    }
    return installedAppId
  }

  /**
   * Formats the version information for display in the UI.
   *
   * Override this in app-specific implementations to provide human-readable version strings.
   * For example, an app-specific target might format "12345678" as "1.23 (build 5678)".
   *
   * @param platform The platform (ANDROID or IOS)
   * @param versionInfo The raw version information from the device
   * @return A formatted string for display, or null to use the default formatting
   */
  open fun formatVersionInfo(platform: TrailblazeDevicePlatform, versionInfo: AppVersionInfo): String? = null

  /**
   * Returns the minimum required build number/version code for this app target on the given platform.
   *
   * If the installed app version is below this minimum, a warning should be displayed to the user.
   * This is useful for warning about breaking API changes that require newer app builds.
   *
   * @param platform The platform (ANDROID or IOS)
   * @return The minimum build number as a string, or null if no minimum is required
   */
  open fun getMinBuildVersion(platform: TrailblazeDevicePlatform): String? = null

  /**
   * Returns a warning message to display when the installed app version is below the minimum.
   *
   * @param platform The platform (ANDROID or IOS)
   * @param installedVersion The version info of the installed app
   * @param minVersion The minimum required version
   * @return A warning message, or null to use the default message
   */
  open fun getVersionWarningMessage(
    platform: TrailblazeDevicePlatform,
    installedVersion: AppVersionInfo,
    minVersion: String,
  ): String? = null

  /**
   * Checks if the installed version meets the minimum requirements.
   *
   * @param platform The platform (ANDROID or IOS)
   * @param versionInfo The version info of the installed app
   * @return true if the version is acceptable, false if it's below minimum
   */
  open fun isVersionAcceptable(platform: TrailblazeDevicePlatform, versionInfo: AppVersionInfo): Boolean {
    val minVersion = getMinBuildVersion(platform) ?: return true

    // For iOS, compare buildNumber if available, otherwise versionCode
    // For Android, compare versionCode
    val installedVersion = when (platform) {
      TrailblazeDevicePlatform.IOS -> versionInfo.buildNumber ?: versionInfo.versionCode
      TrailblazeDevicePlatform.ANDROID -> versionInfo.versionCode
      else -> return true
    }

    return try {
      installedVersion.toLong() >= minVersion.toLong()
    } catch (e: NumberFormatException) {
      // If versions aren't numeric, do string comparison
      installedVersion >= minVersion
    }
  }

  companion object {
    /**
     * Validation regex for target IDs. Target IDs are internal identifiers — artifact names
     * in CI, YAML filenames, CLI config keys. They don't need to round-trip as LLM tool names,
     * so hyphens and underscores are allowed alongside lowercase alphanumeric.
     *
     * Tool names that DO get registered with LLMs by exact string (see [ToolName] /
     * `@TrailblazeToolClass`) keep their own stricter constraints separately.
     */
    private val ID_PATTERN = Regex("^[a-z0-9_-]+$")

    /** Returns `true` if [id] is a well-formed target identifier. */
    fun isValidId(id: String): Boolean = id.matches(ID_PATTERN)

    /** The error message produced when an invalid ID is supplied; shared across call sites. */
    fun invalidIdMessage(id: String, displayName: String): String =
      "ID ($id) for $displayName must be lowercase alphanumeric, hyphens, or underscores only"
  }
}

fun Iterable<TrailblazeHostAppTarget>.findById(id: String): TrailblazeHostAppTarget? =
  firstOrNull { it.id.equals(id, ignoreCase = true) }
