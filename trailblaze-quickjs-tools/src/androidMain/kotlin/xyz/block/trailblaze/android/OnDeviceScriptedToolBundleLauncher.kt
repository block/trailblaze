package xyz.block.trailblaze.android

import android.content.res.AssetManager
import androidx.test.platform.app.InstrumentationRegistry
import xyz.block.trailblaze.config.McpServerConfig
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.quickjs.tools.AndroidAssetBundleSource
import xyz.block.trailblaze.quickjs.tools.BundleSource
import xyz.block.trailblaze.quickjs.tools.LaunchedQuickJsToolRuntime
import xyz.block.trailblaze.quickjs.tools.QuickJsEngineExtension
import xyz.block.trailblaze.quickjs.tools.QuickJsToolBundleLauncher
import xyz.block.trailblaze.scripting.OnDeviceScriptedToolBundlePlan
import xyz.block.trailblaze.scripting.RuntimeToolSource
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.util.Console
import java.io.File

/**
 * The single on-device launcher for pre-compiled QuickJS scripted-tool bundles. Registers a
 * session's scripted tools into [TrailblazeToolRepo] so they're dispatchable by name — including
 * the by-name `invokeFrameworkTool(...)` composition a Kotlin orchestrator does (e.g. an app's
 * launch orchestrator dispatching a TypeScript launch sub-step by name). Reading each bundle's JS
 * from the test APK assets via [AndroidAssetBundleSource] is the on-device counterpart of the
 * host's classpath-resource read: the device APK has no `bun`/esbuild, so the bundle must be
 * pre-compiled at build time and packaged as an asset.
 *
 * Two delivery routes, mirroring the host's `HostScriptedToolLauncher`:
 *  - **target-declared** (`target.tools:`) in-process tools, surfaced from the bundled
 *    `targets/<id>.yaml` via [TrailblazeHostAppTarget.getInlineScriptTools]. This is a single
 *    classpath-resource read, so it works on Android where the classloader cannot enumerate
 *    resource directories (which is why the descriptor-discovery route below comes up empty for a
 *    bundled Kotlin target).
 *  - **catalog/toolset-delivered** scripted tools, resolved through the shared
 *    [xyz.block.trailblaze.scripting.InProcessScriptedToolLauncher] (descriptor-discovery gated).
 *
 * Both routes are planned by [OnDeviceScriptedToolBundlePlan], which this launcher supplies an
 * asset-existence probe to. Bundles that aren't packaged in this APK's assets are dropped, so a target
 * whose bundles aren't staged degrades to "tool unavailable" (the same as before this launcher
 * existed) rather than crashing session start on a missing asset. A multi-export bundle (one `.ts`
 * module exporting several tools) is loaded once — `QuickJsToolHost.listTools()` then registers
 * every tool it exports — so bundles are de-duplicated by asset path before launch.
 *
 * Lives in `:trailblaze-quickjs-tools` (androidMain) rather than `:trailblaze-android` so BOTH
 * on-device drivers share it: `AndroidTrailblazeRule` (the OSS on-device rule, with its downstream
 * subclasses) and `AndroidTestTrailblazeRule` (the in-process ANDROID_TEST driver, which must not
 * drag the Maestro-backed `:trailblaze-android` module into an app's instrumentation classpath).
 * One launcher is what keeps the drivers from drifting on which scripted tools register on-device
 * or where their bundles live.
 */
object OnDeviceScriptedToolBundleLauncher {

  suspend fun launchAll(
    toolRepo: TrailblazeToolRepo,
    target: TrailblazeHostAppTarget?,
    sessionId: SessionId,
    deviceInfo: TrailblazeDeviceInfo,
    /**
     * Engine extension installed into each launched bundle's QuickJS engine. Every production
     * caller passes the OkHttp-backed `fetch` extension (`OkHttpFetchExtension`) so scripted
     * tools see the same standard `fetch` surface on-device as host-dispatched. A parameter
     * rather than a hardcoded construction so this module doesn't depend on
     * `:trailblaze-scripting-fetch` — the implementation lives with the callers.
     */
    engineExtension: QuickJsEngineExtension?,
    assetManager: AssetManager = resolveDefaultAssetManager(),
  ): LaunchedQuickJsToolRuntime? {
    // Already-registered names are skipped on both routes: re-registering hard-fails
    // `addDynamicTools`'s collision guard, and target-declared tools win over toolset-delivered
    // ones on a name collision (the host-side precedence).
    val alreadyRegistered = toolRepo.getRegisteredDynamicTools().keys

    // Assets, or a directory a host pushed bundles into? Decided once per session start, from the
    // signed target config and whether this process is instrumented at all. The reason is logged
    // either way — "which tools did this run actually load" is otherwise unanswerable from a report.
    val runtimeSource = RuntimeToolSource.resolve(
      underInstrumentation = isUnderInstrumentation(),
      targetOptedIn = target?.allowsRuntimeToolSource == true,
    )
    when (runtimeSource) {
      is RuntimeToolSource.Decision.Honored -> Console.log(
        "[ondevice-scripted] runtime tool source ENABLED at '${runtimeSource.directory}' — a bundle " +
          "found there is loaded instead of this APK's asset copy.",
      )
      is RuntimeToolSource.Decision.Ignored -> Console.log(
        "[ondevice-scripted] runtime tool source off (${runtimeSource.reason.explanation}); " +
          "scripted-tool bundles come from this APK's assets.",
      )
    }
    val runtimeDirectory = (runtimeSource as? RuntimeToolSource.Decision.Honored)?.directory

    /** The pushed file backing [bundlePath], when the runtime source is on and it is readable. */
    fun runtimeBundleFile(bundlePath: String): File? {
      val directory = runtimeDirectory ?: return null
      val path = RuntimeToolSource.filePathFor(directory, bundlePath) ?: return null
      return File(path).takeIf { it.isFile && it.canRead() }
    }

    // Memoize availability probes by bundle path: a multi-export `.ts` module backs many tool
    // names but a single bundle path, and the same path can surface on both the inline and catalog
    // routes — so probe (and log a miss for) each `.bundle.js` at most once per session start.
    val bundleAvailableCache = HashMap<String, Boolean>()
    fun bundleAvailable(assetPath: String): Boolean =
      bundleAvailableCache.getOrPut(assetPath) {
        val pushed = runtimeBundleFile(assetPath)
        if (pushed != null) {
          Console.log("[ondevice-scripted] scripted-tool bundle '$assetPath' resolved to pushed file ${pushed.path}")
          return@getOrPut true
        }
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

    // Both delivery routes, resolved by the shared planner so this path and the JVM-side
    // on-device surface guard agree on which bundles load and what each declared tool advertises.
    val plan = OnDeviceScriptedToolBundlePlan.resolve(
      toolRepo = toolRepo,
      target = target,
      alreadyRegistered = alreadyRegistered,
      isPackaged = ::bundleAvailable,
      logPrefix = "[ondevice-scripted]",
    )

    // One config per UNIQUE bundle path: the launcher loads each bundle once and registers every
    // tool that bundle's `listTools()` advertises.
    if (plan.bundlePaths.isEmpty()) return null

    return QuickJsToolBundleLauncher.launchAll(
      bundles = plan.bundlePaths.map { McpServerConfig(script = it) },
      deviceInfo = deviceInfo,
      sessionId = sessionId,
      toolRepo = toolRepo,
      bundleSourceResolver = { entry ->
        val assetPath = entry.script!!
        runtimeBundleFile(assetPath)
          ?.let { BundleSource.FromFile(it.path) }
          ?: AndroidAssetBundleSource(assetPath = assetPath, assetManager = assetManager)
      },
      advertisementOverrides = plan.advertisementOverrides,
      declaredToolNames = plan.declaredToolNames,
      engineExtension = engineExtension,
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

  /**
   * Whether this process is running a test, as opposed to being an ordinary launch of an app that
   * happens to ship these classes.
   *
   * [InstrumentationRegistry.getInstrumentation] throws when nothing registered one, which is
   * exactly the production case, so this is the property itself rather than a proxy for it.
   */
  private fun isUnderInstrumentation(): Boolean =
    runCatching { InstrumentationRegistry.getInstrumentation() }.getOrNull() != null
}
