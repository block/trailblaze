package xyz.block.trailblaze.android.test

import androidx.test.platform.app.InstrumentationRegistry
import kotlin.reflect.KClass
import xyz.block.trailblaze.config.AppTargetYamlConfig
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.config.TrailblazeConfigYaml
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.toolcalls.TrailblazeTool

/**
 * A host-app target parsed from a target yaml the test APK bundles as an asset — how an in-process
 * module turns the committed `targets/<id>.yaml` it stages into the target its trails run under.
 *
 * It exists to feed exactly two consumers:
 *  - `OnDeviceScriptedToolBundleLauncher`, which reads [getInlineScriptTools] — the target's
 *    `tools:` list — to register the trailmap's in-process scripted tools by name at session
 *    start, from the QuickJS bundles the test APK packages (the build's `trailmap { }` block);
 *  - the scripted tools' own `ctx.target` envelope, whose `appIds` come from
 *    [getPossibleAppIdsForPlatform].
 *
 * Deliberately NOT a `YamlBackedHostAppTarget`: that class also honors the manifest's per-platform
 * `tool_sets:` scoping, and trailmap toolsets commonly declare `drivers: [android]`, which the
 * catalog's shorthand deliberately does NOT extend to ANDROID_TEST — so honoring the scope here
 * would narrow the session repo below the framework primitives scripted launch chains compose
 * (`android_sendBroadcast` and friends). This target declares no toolsets, which the scope
 * resolver reads as "unconfigured" and keeps the whole ANDROID_TEST-compatible catalog — the same
 * surface the driver had before targets were wired at all.
 */
class AssetBackedHostAppTarget private constructor(
  private val config: AppTargetYamlConfig,
) : TrailblazeHostAppTarget(
  id = config.id,
  displayName = config.displayName,
) {

  override fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): List<String>? {
    val platformConfig = config.platforms?.entries
      ?.firstOrNull { it.key.equals(platform.name, ignoreCase = true) }
      ?.value
      ?: return null
    // Base-class contract: null = platform unsupported, emptyList = supported with no declared id.
    return platformConfig.appIds ?: emptyList()
  }

  override fun internalGetCustomToolsForDriver(
    driverType: TrailblazeDriverType,
  ): Set<KClass<out TrailblazeTool>> = emptySet()

  override fun getInlineScriptTools(): List<InlineScriptToolConfig> = config.tools.orEmpty()

  /**
   * Read straight off the config asset, which is the point: the asset is inside the APK, so the
   * APK's signature covers this answer. Nothing at run time can flip it.
   */
  override val allowsRuntimeToolSource: Boolean = config.allowRuntimeToolSource

  companion object {
    /**
     * Parses the target yaml at [assetPath] from the TEST APK's assets (the instrumentation
     * context, not the app's — the config is the test module's to stage, never the app's to carry).
     */
    fun fromAsset(assetPath: String): AssetBackedHostAppTarget {
      val yaml = InstrumentationRegistry.getInstrumentation().context.assets
        .open(assetPath)
        .use { it.readBytes().decodeToString() }
      return AssetBackedHostAppTarget(
        TrailblazeConfigYaml.instance.decodeFromString(AppTargetYamlConfig.serializer(), yaml),
      )
    }
  }
}
