package xyz.block.trailblaze.model

import xyz.block.trailblaze.config.InlineScriptToolConfig
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

  /**
   * Declared app ids for [platform]. Returns null when the platform isn't supported by this
   * target, an empty list when it's supported but no ids are configured, and a populated list
   * otherwise.
   *
   * **Ordering is contractual.** Callers like `BaseIosTrailblazeTest.ensureTargetAppIsStopped`
   * rely on `firstOrNull()` returning the primary id. Implementations must preserve the
   * declaration order they got from their source (YAML list, hand-rolled `listOf(...)`, etc.).
   * The previous return type was `Set<String>?`, which preserved order in practice via
   * `LinkedHashSet` but didn't make the guarantee contractual; switched to `List<String>?` so
   * the contract matches the call-site assumptions.
   */
  abstract fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): List<String>?

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
   * Scripted (`.ts` / `.js`) tool names this target exposes for the given driver.
   *
   * Mirrors [getCustomYamlToolNamesForDriver] (inclusion) and [getExcludedScriptedToolNamesForDriver]
   * (exclusion) for tools delivered by a toolset's `tools:` (e.g. `openUrl` via `core_interaction`)
   * or listed directly in a target's `platforms.<p>.tools:`. These are advertised to the LLM like
   * any other tool but dispatched through the per-session scripted-tool runtime. Without this, a
   * scripted name in `platforms.<p>.tools:` resolves to neither a class nor a YAML name and is
   * silently dropped (logged as an unknown tool) — the scripted-partition parallel of the bug fixed
   * for class-backed tools ([getCustomToolsForDriver]) and YAML tools
   * ([getCustomYamlToolNamesForDriver]).
   *
   * Default empty; YAML-backed targets populate it from their toolsets and per-platform `tools:`
   * lists.
   */
  open fun getCustomScriptedToolNamesForDriver(driverType: TrailblazeDriverType): Set<ToolName> = emptySet()

  /**
   * A named group of tools for discovery output. Allows targets to organize their
   * custom tools into logical groups (e.g., "onboarding", "checkout", "settings").
   *
   * [yamlToolNames] carries YAML-defined tool names (no [KClass] backing) so discovery
   * output can advertise them alongside [toolClasses]. Without this, name-only tools
   * referenced from toolset YAML (e.g. `eraseText`, `pressBack`) would silently drop
   * out of `toolbox` listings even though the executor accepts them.
   *
   * [scriptedToolNames] carries scripted (`.ts` / `.js`) tool names — the third backing — for the
   * same reason: a target whose custom tools are scripted (e.g. `openUrl` via a toolset, or a name
   * listed in `platforms.<p>.tools:`) would otherwise be missing from grouped discovery output even
   * though the resolver ([getCustomScriptedToolNamesForDriver]) and the agent surface advertise it.
   * Completes the three-way parity in the discovery-grouping path.
   */
  data class ToolGroup(
    val id: String,
    val description: String,
    val toolClasses: Set<KClass<out TrailblazeTool>>,
    val yamlToolNames: Set<ToolName> = emptySet(),
    val scriptedToolNames: Set<ToolName> = emptySet(),
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
    val scriptedNames = getCustomScriptedToolNamesForDriver(driverType)
    if (tools.isEmpty() && yamlNames.isEmpty() && scriptedNames.isEmpty()) return emptyList()
    return listOf(
      ToolGroup(
        id = id,
        description = "$displayName tools",
        toolClasses = tools,
        yamlToolNames = yamlNames,
        scriptedToolNames = scriptedNames,
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
   * Scripted (`.ts` / `.js`) tool names this target wants to exclude for the given driver.
   *
   * Mirrors [getExcludedYamlToolNamesForDriver] for tools delivered by a toolset's `tools:`
   * (e.g. `openUrl` via `core_interaction`) or the target's own `target.tools:` — tools advertised
   * to the LLM but dispatched through the per-session scripted-tool runtime. Without this, a target
   * YAML's `excluded_tools: [openUrl]` resolves to neither a class nor a YAML name and is silently
   * dropped (logged as an unknown tool) — the scripted-partition parallel of the bug fixed for
   * class-backed tools ([getExcludedToolsForDriver]) and YAML tools ([getExcludedYamlToolNamesForDriver]).
   *
   * Default empty; YAML-backed targets populate from their per-platform `excluded_tools` lists.
   */
  open fun getExcludedScriptedToolNamesForDriver(driverType: TrailblazeDriverType): Set<ToolName> = emptySet()

  /**
   * Toolset ids the target *declares* for the given driver — the positive list of toolset
   * names from `platforms.<key>.tool_sets:` in the target YAML, before any catalog
   * resolution. Drives trailmap-positive LLM tool resolution: callers pass the result to
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
    // Empty list (not null) honors the base-class contract: every platform is supported,
    // just with no specific app id declared. Returning null was misread by the UI's
    // platform-support gate as "platform not supported" and disabled every device row.
    override fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): List<String> = emptyList()

    override fun internalGetCustomToolsForDriver(driverType: TrailblazeDriverType): Set<KClass<out TrailblazeTool>> =
      setOf()

    // Default is the generic stand-in: no specific app id declared, any installed app is fine.
    override val allowsAppNotInstalled: Boolean = true
  }

  /**
   * Whether a device on [platform] is eligible for selection with this target. Single source
   * of truth for the Run Configuration dialog's per-row gate; before this lived as a four-site
   * inline predicate that drifted easily when the rule evolved.
   *
   * A target accepts a device when:
   *   - the target supports [platform] — `getPossibleAppIdsForPlatform(platform)` is non-null
   *     per the documented contract (null = unsupported, empty list = supported with no id);
   *     AND
   *   - either the target opts into [allowsAppNotInstalled] (the generic Default stand-in) OR
   *     one of the declared app ids is actually present on the device (signalled by a non-null
   *     [installedAppId] resolved from [getAppIdIfInstalled]).
   *
   * The platform-support gate is what stops a web-only target from enabling Android/iOS
   * device rows just because `allowsAppNotInstalled` is true.
   */
  fun acceptsDeviceForPlatform(
    platform: TrailblazeDevicePlatform,
    installedAppId: String?,
  ): Boolean {
    val supported = getPossibleAppIdsForPlatform(platform) != null
    return supported && (allowsAppNotInstalled || installedAppId != null)
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
   * Strict variant of [getAppIdIfInstalled]: returns the first declared app id for [platform]
   * that's present in [installedAppIds]. When none match, behavior depends on
   * [allowsAppNotInstalled]:
   *
   * - `false` (the default; real product targets) — throws [IllegalStateException] naming the
   *   target id, the platform, the declared candidates, and the device's installed-apps set, so
   *   the oncaller can either install the right build or update the target's declared list.
   * - `true` (stand-in targets like `default`) — returns the first declared id as a fallback so
   *   downstream launch tools can still proceed; missing-install is expected for these targets.
   *
   * Also throws [IllegalStateException] when the target declares no app ids for the platform.
   *
   * **What this consolidates.** Two sites previously rolled the same "intersect declared
   * candidates, throw with a descriptive message on miss" pattern around different probes:
   * `MobileDeviceUtils.findInstalledAppIdForTarget` (host iOS / generic, JVM `simctl listapps`)
   * and downstream on-device Android rules using `AdbCommandUtil.listInstalledApps()`. The
   * platform-specific probe stays at each call site (different runtimes have different ways to
   * enumerate installed packages); the priority picking + miss-handling is shared here so the
   * two paths can't silently diverge on which declared id wins when multiple are installed.
   *
   * Callers that want a soft-fail (`null` on miss) should keep using [getAppIdIfInstalled].
   */
  fun requireInstalledAppIdForDevice(
    platform: TrailblazeDevicePlatform,
    installedAppIds: Set<String>,
  ): String {
    val declared = getPossibleAppIdsForPlatform(platform)
    if (declared.isNullOrEmpty()) {
      error("Target '$id' ($displayName) has no $platform app ids configured.")
    }
    // Delegate to the canonical non-throwing primitive so the priority/tie-break semantics
    // can't drift between the two helpers — the entire point of this consolidation is one
    // source of truth for "which declared id wins."
    val installedAppId = getAppIdIfInstalled(platform, installedAppIds)
    if (installedAppId != null) return installedAppId
    if (allowsAppNotInstalled) return declared.first()
    error(
      "Could not find $displayName (id=$id). Target declares $platform app ids $declared but " +
        "none are installed on the device. Currently installed: $installedAppIds. Either " +
        "install one of the declared ids on the device, or add the actually-installed id to " +
        "the target's declared list.",
    )
  }

  /**
   * Returns the primary app id for [platform] — the first entry in [getPossibleAppIdsForPlatform].
   * Throws [IllegalStateException] when the target declares no app ids for the platform; the
   * error message names the target id, the platform, the YAML path the oncaller should check,
   * and the specific YAML field — all four are load-bearing diagnostic pieces, so each is asserted
   * independently in `ResolvedTargetTest`.
   *
   * Consolidates the `firstOrNull() ?: error(...)` pattern that otherwise gets reimplemented at
   * every call site (`ResolvedTarget.appId`, the various Block-side `Ios*AppUtils` static helpers,
   * and any future per-target wrapper). Sites that need the full id list still call
   * [getPossibleAppIdsForPlatform] directly; this is the strict-mode "first-or-fail" accessor.
   */
  fun requireFirstAppIdForPlatform(platform: TrailblazeDevicePlatform): String =
    getPossibleAppIdsForPlatform(platform)?.firstOrNull()
      ?: error(
        "Target '$id' declares no app ids for $platform — check " +
          "`platforms.${platform.name.lowercase()}.app_ids` in trails/config/targets/$id.yaml " +
          "(or the corresponding Kotlin getPossibleAppIdsForPlatform override).",
      )

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
     * so hyphens and underscores are allowed alongside alphanumeric, and uppercase letters are
     * permitted to support lowerCamelCase multi-word trailmap ids (e.g. `playwrightSample`,
     * `googleCalendar`, `androidSettings`) — the canonical id shape locked in by the
     * 2026-05-27 trailmap-scoped tool naming devlog.
     *
     * Tool names that DO get registered with LLMs by exact string (see [ToolName] /
     * `@TrailblazeToolClass`) keep their own stricter constraints separately.
     */
    private val ID_PATTERN = Regex("^[a-zA-Z0-9_-]+$")

    /** Returns `true` if [id] is a well-formed target identifier. */
    fun isValidId(id: String): Boolean = id.matches(ID_PATTERN)

    /** The error message produced when an invalid ID is supplied; shared across call sites. */
    fun invalidIdMessage(id: String, displayName: String): String =
      "ID ($id) for $displayName must be alphanumeric, hyphens, or underscores only"
  }
}

fun Iterable<TrailblazeHostAppTarget>.findById(id: String): TrailblazeHostAppTarget? =
  firstOrNull { it.id.equals(id, ignoreCase = true) }
