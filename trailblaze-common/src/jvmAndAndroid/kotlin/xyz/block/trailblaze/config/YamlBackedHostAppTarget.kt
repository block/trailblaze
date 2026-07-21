package xyz.block.trailblaze.config

import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.model.AppVersionInfo
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.util.Console
import kotlin.reflect.KClass

/**
 * A [TrailblazeHostAppTarget] backed by a parsed `.app.yaml` configuration
 * with an optional [AppTargetCompanion] for behavioral logic.
 *
 * Tool names are resolved lazily on first access to avoid boot-order issues.
 */
class YamlBackedHostAppTarget(
  val config: AppTargetYamlConfig,
  private val toolNameResolver: ToolNameResolver,
  private val availableToolSets: Map<String, ResolvedToolSet> = emptyMap(),
  private val companion: AppTargetCompanion? = null,
) : TrailblazeHostAppTarget(
  id = config.id,
  displayName = config.displayName,
) {

  // --- Platform config lookup ---

  /** Finds the [PlatformConfig] for [platform], trying exact lowercase match then case-insensitive. */
  private fun findPlatformConfig(platform: TrailblazeDevicePlatform): PlatformConfig? {
    val platformConfigs = config.platforms ?: return null
    return platformConfigs[platform.name.lowercase()]
      ?: platformConfigs.entries.firstOrNull { it.key.equals(platform.name, ignoreCase = true) }?.value
  }

  // --- App IDs ---

  override fun getPossibleAppIdsForPlatform(
    platform: TrailblazeDevicePlatform,
  ): List<String>? {
    // Honor the base-class contract: null = platform not supported by this target,
    // emptyList = platform supported but no specific id declared. The previous version
    // returned null in both cases, which the UI then couldn't tell apart — that broke
    // the `acceptsDeviceForPlatform` gate for a web-only target on an iOS device row
    // (PR #3118 follow-up).
    val platformConfig = findPlatformConfig(platform) ?: return null
    return platformConfig.appIds ?: emptyList()
  }

  // A target that declares no app_ids on any platform it does support is a
  // platform-tools-only stand-in (mirrors DefaultTrailblazeHostAppTarget): any installed
  // app is fine on the platforms it covers. Real product targets declare at least one
  // app id and stay strict. Per-platform support is enforced separately in
  // `acceptsDeviceForPlatform`, so a web-only target with this flag stays disabled on
  // Android/iOS rows.
  override val allowsAppNotInstalled: Boolean =
    config.platforms?.values?.all { it.appIds.isNullOrEmpty() } ?: true

  // --- Custom tools per driver type ---

  /**
   * Per-driver classification of resolved tool names into class-backed, YAML-defined, and
   * scripted (`.ts` / `.js`) buckets. Used for both `tools:` (inclusion) and `excluded_tools:`
   * (exclusion) — both sides need the same split so callers can route each kind through its own
   * API.
   *
   * [scriptedNames] is populated on both sides: each classifies a `platforms.<p>.tools:` /
   * `excluded_tools:` entry that resolves to a scripted tool into this bucket (via
   * `toolNameResolver.resolveScriptedNameOrNull`), plus a toolset's `resolvedScriptedToolNames`
   * on the inclusion path. Inclusion-side scripted tools *also* arrive through `target.tools:`
   * (`getInlineScriptTools()`), which `getAgentToolboxForDriver` unions in separately rather than
   * via this map. It defaults empty so either side can omit it when there are no scripted entries.
   */
  private data class ResolvedToolsByDriver(
    val classes: Map<TrailblazeDriverType, Set<KClass<out TrailblazeTool>>>,
    val yamlNames: Map<TrailblazeDriverType, Set<ToolName>>,
    val scriptedNames: Map<TrailblazeDriverType, Set<ToolName>> = emptyMap(),
  )

  /**
   * For each driver type, the resolved set of custom tools (both class-backed and
   * YAML-defined). Computed lazily from the YAML's platform sections + companion contributions.
   */
  private val resolvedCustomToolsByDriver: ResolvedToolsByDriver by lazy {
    val classes = mutableMapOf<TrailblazeDriverType, MutableSet<KClass<out TrailblazeTool>>>()
    val yamlNames = mutableMapOf<TrailblazeDriverType, MutableSet<ToolName>>()
    val scriptedNames = mutableMapOf<TrailblazeDriverType, MutableSet<ToolName>>()

    config.platforms?.forEach { (platformKey, platformConfig) ->
      val driverTypes = platformConfig.resolveDriverTypes(platformKey)

      // Resolve toolsets — all three backings flow through.
      platformConfig.toolSets?.forEach { toolSetId ->
        val toolSet = availableToolSets[toolSetId]
        if (toolSet == null) {
          Console.log("Warning: App target '$id' platform '$platformKey' references unknown toolset '$toolSetId'")
          return@forEach
        }
        for (dt in driverTypes) {
          if (!toolSet.isCompatibleWith(dt)) continue
          classes.getOrPut(dt) { mutableSetOf() }.addAll(toolSet.resolvedToolClasses)
          yamlNames.getOrPut(dt) { mutableSetOf() }.addAll(toolSet.resolvedYamlToolNames)
          scriptedNames.getOrPut(dt) { mutableSetOf() }.addAll(toolSet.resolvedScriptedToolNames)
        }
      }

      // Resolve individual tools — look up by bare name; route to the right bucket.
      // `resolveYamlNameOrNull` / `resolveScriptedNameOrNull` return a typed `ToolName?`, so
      // classification hands back the already-wrapped value and we avoid a re-wrap here
      // (Decision 038's wrap-at-the-boundary pattern). Before the scripted branch, a scripted
      // (`.ts` / `.js`) tool name listed in `tools:` (e.g. `openUrl`) resolved to neither a class
      // nor a YAML name and hit the `else` below — silently dropped as "unknown tool" — the
      // inclusion-side parallel of the exclusion bug fixed in `resolvedExcludedToolsByDriver`.
      platformConfig.tools?.forEach { toolName ->
        val toolClass = toolNameResolver.resolveOrNull(toolName)
        val yamlName = toolNameResolver.resolveYamlNameOrNull(toolName)
        val scriptedName = toolNameResolver.resolveScriptedNameOrNull(toolName)
        when {
          toolClass != null -> for (dt in driverTypes) {
            classes.getOrPut(dt) { mutableSetOf() }.add(toolClass)
          }
          yamlName != null -> for (dt in driverTypes) {
            yamlNames.getOrPut(dt) { mutableSetOf() }.add(yamlName)
          }
          scriptedName != null -> for (dt in driverTypes) {
            scriptedNames.getOrPut(dt) { mutableSetOf() }.add(scriptedName)
          }
          else -> Console.log(
            "Warning: App target '$id' platform '$platformKey' references unknown tool '$toolName'",
          )
        }
      }
    }

    ResolvedToolsByDriver(classes = classes, yamlNames = yamlNames, scriptedNames = scriptedNames)
  }

  override fun internalGetCustomToolsForDriver(
    driverType: TrailblazeDriverType,
  ): Set<KClass<out TrailblazeTool>> {
    return resolvedCustomToolsByDriver.classes[driverType] ?: emptySet()
  }

  override fun getCustomYamlToolNamesForDriver(
    driverType: TrailblazeDriverType,
  ): Set<ToolName> {
    return resolvedCustomToolsByDriver.yamlNames[driverType] ?: emptySet()
  }

  override fun getCustomScriptedToolNamesForDriver(
    driverType: TrailblazeDriverType,
  ): Set<ToolName> {
    return resolvedCustomToolsByDriver.scriptedNames[driverType] ?: emptySet()
  }

  // --- Excluded tools ---

  /**
   * For each driver type, the resolved set of *excluded* tools — split into class-backed,
   * YAML-defined, and scripted (`.ts` / `.js`) entries. Mirrors [resolvedCustomToolsByDriver]'s
   * classification so a target's `excluded_tools: [pressBack]` (YAML) or `excluded_tools: [openUrl]`
   * (scripted, toolset-delivered) flows through the right exclusion API instead of being swallowed
   * by the resolver as an unknown tool. Before scripted was a recognized bucket here, an
   * `excluded_tools: [openUrl]` entry hit the `else` branch below and was logged as "unknown tool"
   * then dropped, so the exclusion never reached the LLM-surface compositors.
   */
  private val resolvedExcludedToolsByDriver: ResolvedToolsByDriver by lazy {
    val classes = mutableMapOf<TrailblazeDriverType, MutableSet<KClass<out TrailblazeTool>>>()
    val yamlNames = mutableMapOf<TrailblazeDriverType, MutableSet<ToolName>>()
    val scriptedNames = mutableMapOf<TrailblazeDriverType, MutableSet<ToolName>>()

    config.platforms?.forEach { (platformKey, platformConfig) ->
      val excludedNames = platformConfig.excludedTools ?: return@forEach
      val driverTypes = platformConfig.resolveDriverTypes(platformKey)
      excludedNames.forEach { name ->
        val toolClass = toolNameResolver.resolveOrNull(name)
        val yamlName = toolNameResolver.resolveYamlNameOrNull(name)
        val scriptedName = toolNameResolver.resolveScriptedNameOrNull(name)
        when {
          toolClass != null -> for (dt in driverTypes) {
            classes.getOrPut(dt) { mutableSetOf() }.add(toolClass)
          }
          yamlName != null -> for (dt in driverTypes) {
            yamlNames.getOrPut(dt) { mutableSetOf() }.add(yamlName)
          }
          scriptedName != null -> for (dt in driverTypes) {
            scriptedNames.getOrPut(dt) { mutableSetOf() }.add(scriptedName)
          }
          else -> Console.log(
            "Warning: App target '$id' platform '$platformKey' excluded_tools references unknown tool '$name'",
          )
        }
      }
    }

    ResolvedToolsByDriver(classes = classes, yamlNames = yamlNames, scriptedNames = scriptedNames)
  }

  override fun getExcludedToolsForDriver(
    driverType: TrailblazeDriverType,
  ): Set<KClass<out TrailblazeTool>> =
    resolvedExcludedToolsByDriver.classes[driverType] ?: emptySet()

  override fun getExcludedYamlToolNamesForDriver(
    driverType: TrailblazeDriverType,
  ): Set<ToolName> =
    resolvedExcludedToolsByDriver.yamlNames[driverType] ?: emptySet()

  override fun getExcludedScriptedToolNamesForDriver(
    driverType: TrailblazeDriverType,
  ): Set<ToolName> =
    resolvedExcludedToolsByDriver.scriptedNames[driverType] ?: emptySet()

  override fun getDeclaredToolSetIdsForDriver(driverType: TrailblazeDriverType): List<String> {
    val ids = mutableListOf<String>()
    config.platforms?.forEach { (platformKey, platformConfig) ->
      if (driverType in platformConfig.resolveDriverTypes(platformKey)) {
        platformConfig.toolSets?.let { ids.addAll(it) }
      }
    }
    return ids.distinct()
  }

  override fun getInlineScriptTools(): List<InlineScriptToolConfig> = config.tools ?: emptyList()

  override fun getSystemPromptTemplate(): String? = config.systemPrompt

  override fun getElectronAppConfig(): xyz.block.trailblaze.yaml.ElectronAppConfig? = config.electron

  // --- Version info ---

  override fun getMinBuildVersion(platform: TrailblazeDevicePlatform): String? =
    findPlatformConfig(platform)?.minBuildVersion

  // --- Companion delegation ---

  override val hasCustomIosDriver: Boolean
    get() = config.hasCustomIosDriver

  override fun getCustomIosDriverFactory(
    trailblazeDeviceId: TrailblazeDeviceId,
    originalIosDriver: Any,
  ): Any = companion?.getCustomIosDriverFactory(trailblazeDeviceId, originalIosDriver)
    ?: super.getCustomIosDriverFactory(trailblazeDeviceId, originalIosDriver)

  override fun formatVersionInfo(
    platform: TrailblazeDevicePlatform,
    versionInfo: AppVersionInfo,
  ): String? = companion?.formatVersionInfo(platform, versionInfo)

  override fun getVersionWarningMessage(
    platform: TrailblazeDevicePlatform,
    installedVersion: AppVersionInfo,
    minVersion: String,
  ): String? = companion?.getVersionWarningMessage(platform, installedVersion, minVersion)

  override fun isVersionAcceptable(
    platform: TrailblazeDevicePlatform,
    versionInfo: AppVersionInfo,
  ): Boolean = companion?.isVersionAcceptable(platform, versionInfo)
    ?: super.isVersionAcceptable(platform, versionInfo)

  override fun toString(): String = "YamlBackedHostAppTarget(id=$id, displayName=$displayName)"
}
