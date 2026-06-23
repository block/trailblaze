package xyz.block.trailblaze.android

import android.content.res.AssetManager
import androidx.test.platform.app.InstrumentationRegistry
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.config.McpServerConfig
import xyz.block.trailblaze.config.ScriptedToolNameDiscoverer
import xyz.block.trailblaze.config.ScriptedToolRuntime
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.quickjs.tools.AndroidAssetBundleSource
import xyz.block.trailblaze.quickjs.tools.LaunchedQuickJsToolRuntime
import xyz.block.trailblaze.quickjs.tools.QuickJsToolAdvertisement
import xyz.block.trailblaze.quickjs.tools.QuickJsToolBundleLauncher
import xyz.block.trailblaze.scripting.InProcessScriptedToolLauncher
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.util.Console

/**
 * The single on-device launcher for pre-compiled QuickJS scripted-tool bundles. Registers a
 * session's scripted tools into [TrailblazeToolRepo] so they're dispatchable by name — including
 * the by-name `invokeFrameworkTool(...)` composition a Kotlin orchestrator does (e.g. an app's
 * launch orchestrator dispatching a TypeScript launch sub-step by name). Reading each bundle's JS
 * from the test APK assets via [AndroidAssetBundleSource] is the on-device counterpart of the
 * host's classpath-resource read: the device APK has no `bun`/esbuild, so the bundle must be
 * pre-compiled at build time and packaged as an asset.
 *
 * Two delivery routes, mirroring the host's [xyz.block.trailblaze.scripting.HostScriptedToolLauncher]:
 *  - **target-declared** (`target.tools:`) in-process tools, surfaced from the bundled
 *    `targets/<id>.yaml` via [TrailblazeHostAppTarget.getInlineScriptTools]. This is a single
 *    classpath-resource read, so it works on Android where the classloader cannot enumerate
 *    resource directories (which is why the descriptor-discovery route below comes up empty for a
 *    bundled Kotlin target).
 *  - **catalog/toolset-delivered** scripted tools, resolved through the shared
 *    [InProcessScriptedToolLauncher.resolveInProcessScriptedTools] (descriptor-discovery gated).
 *
 * Both routes are filtered to bundles that are actually packaged in this APK's assets, so a target
 * whose bundles aren't staged degrades to "tool unavailable" (the same as before this launcher
 * existed) rather than crashing session start on a missing asset. A multi-export bundle (one `.ts`
 * module exporting several tools) is loaded once — `QuickJsToolHost.listTools()` then registers
 * every tool it exports — so bundles are de-duplicated by asset path before launch.
 *
 * Shared by both [AndroidTrailblazeRule] (the OSS on-device rule) and downstream on-device rules so
 * the two paths can't drift on which scripted tools register on-device or where their bundles live.
 */
object OnDeviceScriptedToolBundleLauncher {

  suspend fun launchAll(
    toolRepo: TrailblazeToolRepo,
    target: TrailblazeHostAppTarget?,
    sessionId: SessionId,
    deviceInfo: TrailblazeDeviceInfo,
    assetManager: AssetManager = resolveDefaultAssetManager(),
  ): LaunchedQuickJsToolRuntime? {
    // Already-registered names are skipped on both routes: re-registering hard-fails
    // `addDynamicTools`'s collision guard, and target-declared tools win over toolset-delivered
    // ones on a name collision (the host-side precedence).
    val alreadyRegistered = toolRepo.getRegisteredDynamicTools().keys

    // Memoize asset-existence probes by bundle path: a multi-export `.ts` module backs many tool
    // names but a single bundle path, and the same path can surface on both the inline and catalog
    // routes — so probe (and log a miss for) each `.bundle.js` at most once per session start.
    val assetPackagedCache = HashMap<String, Boolean>()
    fun bundleAssetPackaged(assetPath: String): Boolean =
      assetPackagedCache.getOrPut(assetPath) {
        assetExists(assetManager, assetPath).also { exists ->
          if (!exists) {
            Console.log(
              "[ondevice-scripted] scripted-tool bundle not packaged at asset '$assetPath' — " +
                "skipping. Tools it backs won't be dispatchable on-device; stage the trailmap's " +
                "scripted-tool bundles into this APK's androidTest assets (see the " +
                "`trailblaze.trailmap-tool-bundles` build wiring).",
            )
          }
        }
      }

    // 1. Target-declared (`target.tools:`) in-process scripted tools.
    val inlineConfigs: List<InlineScriptToolConfig> =
      target?.getInlineScriptTools().orEmpty()
        .filter { ScriptedToolRuntime.resolve(it.runtime) == ScriptedToolRuntime.IN_PROCESS }
        .filter { ToolName(it.name) !in alreadyRegistered }

    // Group by bundle asset path (a multi-export module is one bundle backing many tool names) and
    // drop any whose `.bundle.js` isn't packaged in this APK — better an unavailable tool than a
    // crashed session.
    val inlineByAsset: Map<String, List<InlineScriptToolConfig>> =
      inlineConfigs
        .groupBy { ScriptedToolNameDiscoverer.bundleResourcePathForScript(it.script) }
        .filterKeys { assetPath -> bundleAssetPackaged(assetPath) }

    val inlineNames: Set<ToolName> = inlineByAsset.values.flatten().map { ToolName(it.name) }.toSet()
    val inlineOverrides: Map<ToolName, QuickJsToolAdvertisement> =
      inlineByAsset.values.flatten().associate {
        ToolName(it.name) to QuickJsToolAdvertisement.fromInlineScriptToolConfig(it)
      }

    // 2. Catalog/toolset-delivered scripted tools (descriptor-discovery gated, asset-filtered).
    val catalogResolved =
      InProcessScriptedToolLauncher.resolveInProcessScriptedTools(
        toolNames = toolRepo.allCatalogScriptedToolNames,
        skipNames = alreadyRegistered + inlineNames,
        logPrefix = "[ondevice-scripted]",
      ).filter { bundleAssetPackaged(it.bundleResourcePath) }

    val catalogOverrides: Map<ToolName, QuickJsToolAdvertisement> =
      catalogResolved.associate {
        it.name to QuickJsToolAdvertisement.fromInlineScriptToolConfig(it.config)
      }

    // One config per UNIQUE bundle path: the launcher loads each bundle once and registers every
    // tool that bundle's `listTools()` advertises.
    val bundlePaths = (inlineByAsset.keys + catalogResolved.map { it.bundleResourcePath }).distinct()
    if (bundlePaths.isEmpty()) return null

    return QuickJsToolBundleLauncher.launchAll(
      bundles = bundlePaths.map { McpServerConfig(script = it) },
      deviceInfo = deviceInfo,
      sessionId = sessionId,
      toolRepo = toolRepo,
      bundleSourceResolver = { entry ->
        AndroidAssetBundleSource(assetPath = entry.script!!, assetManager = assetManager)
      },
      advertisementOverrides = inlineOverrides + catalogOverrides,
    )
  }

  /** Probe an asset's presence without reading it fully — open + immediately close. */
  private fun assetExists(assetManager: AssetManager, assetPath: String): Boolean {
    val normalized = assetPath.removePrefix("./").trimStart('/')
    // Mirror AndroidAssetBundleSource's rejection of `..` segments: such a path can slip past a
    // naive open() probe on some devices and then throw when AndroidAssetBundleSource validates it
    // at launch (aborting the whole batch). Treat it as not-packaged so the launcher safely skips it.
    if (normalized.split('/').any { it == ".." }) return false
    return runCatching { assetManager.open(normalized).close() }.isSuccess
  }

  private fun resolveDefaultAssetManager(): AssetManager =
    InstrumentationRegistry.getInstrumentation().context.assets
}
