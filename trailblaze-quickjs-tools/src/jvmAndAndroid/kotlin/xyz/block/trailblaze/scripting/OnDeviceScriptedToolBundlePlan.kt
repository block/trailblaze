package xyz.block.trailblaze.scripting

import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.config.ScriptedToolNameDiscoverer
import xyz.block.trailblaze.config.ScriptedToolRuntime
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.quickjs.tools.QuickJsToolAdvertisement
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.util.Console

/**
 * Which pre-compiled QuickJS bundles an on-device session loads, and what each declared tool
 * advertises — the pure half of the on-device scripted-tool launch.
 *
 * Split out from the launcher so it can be resolved without an [android.content.res.AssetManager]:
 * the only device-specific input is "is this bundle packaged?", which arrives as a predicate. The
 * launch itself (QuickJS engines, registrations) stays in the Android launcher.
 */
data class OnDeviceScriptedToolBundlePlan(
  /** Distinct bundle resource/asset paths to load, one engine each. */
  val bundlePaths: List<String>,
  /** Advertisement per declared tool name — descriptor + `_meta` gate for each. */
  val advertisementOverrides: Map<ToolName, QuickJsToolAdvertisement>,
  /**
   * The tools this session declared. A bundle also registers its exported helpers, which are absent
   * here — that's what tells [xyz.block.trailblaze.quickjs.tools.QuickJsToolBundleLauncher] to
   * register them without advertising them to the LLM.
   *
   * Held separately from [advertisementOverrides] rather than derived from its keys: the two answer
   * different questions ("may the LLM see this?" vs "how is it described?"), and a declared tool
   * that describes itself through its bundle `spec` needs no override. Deriving one from the other
   * would drop such a tool from the array — the same disappearance this class exists to prevent.
   */
  val declaredToolNames: Set<ToolName>,
) {

  companion object {

    /**
     * Resolves the two delivery routes an on-device session pulls scripted tools from:
     *  - **target-declared** (`target.tools:` / `platforms.<p>.tools:`), read from the bundled
     *    `targets/<id>.yaml`.
     *  - **catalog/toolset-delivered**, resolved through [InProcessScriptedToolLauncher] from the
     *    repo's [TrailblazeToolRepo.allCatalogScriptedToolNames] — already scoped to the target's
     *    trailmap `tool_sets:` when the repo was built with them.
     *
     * Target-declared tools win on a name collision (they're passed as `skipNames` to the catalog
     * route). Both routes drop bundles [isPackaged] says this runtime doesn't carry — an
     * unavailable tool beats a session that dies on a missing bundle.
     */
    fun resolve(
      toolRepo: TrailblazeToolRepo,
      target: TrailblazeHostAppTarget?,
      alreadyRegistered: Set<ToolName>,
      isPackaged: (assetPath: String) -> Boolean,
      logPrefix: String = "[ondevice-scripted]",
    ): OnDeviceScriptedToolBundlePlan {
      val inlineConfigs: List<InlineScriptToolConfig> =
        target?.getInlineScriptTools().orEmpty()
          .filter { ScriptedToolRuntime.resolve(it.runtime) == ScriptedToolRuntime.IN_PROCESS }
          .filter { ToolName(it.name) !in alreadyRegistered }

      // Group by bundle path — a multi-export module is one bundle backing many tool names.
      val inlineByAsset: Map<String, List<InlineScriptToolConfig>> =
        inlineConfigs
          .groupBy { ScriptedToolNameDiscoverer.bundleResourcePathForScript(it.script) }
          .filterKeys { isPackaged(it) }

      val inlineNames: Set<ToolName> =
        inlineByAsset.values.flatten().mapTo(mutableSetOf()) { ToolName(it.name) }

      val catalogResolved = InProcessScriptedToolLauncher.resolveInProcessScriptedTools(
        toolNames = toolRepo.allCatalogScriptedToolNames,
        skipNames = alreadyRegistered + inlineNames,
        logPrefix = logPrefix,
      ).filter { isPackaged(it.bundleResourcePath) }

      val overrides = buildMap {
        inlineByAsset.values.flatten().forEach {
          put(ToolName(it.name), QuickJsToolAdvertisement.fromInlineScriptToolConfig(it))
        }
        catalogResolved.forEach {
          // `putIfAbsent`, not `put`: target-declared wins on a name collision. `skipNames` covers
          // this for packaged inline bundles, but an inline tool whose bundle isn't packaged (or
          // whose runtime isn't in-process) never reaches `inlineNames`, so the catalog entry would
          // otherwise silently take over its advertisement.
          putIfAbsent(it.name, QuickJsToolAdvertisement.fromInlineScriptToolConfig(it.config))
        }
      }
      // Distinct paths, not tool count — a multi-export module backs many tools from one bundle.
      val bundlePaths = (inlineByAsset.keys + catalogResolved.map { it.bundleResourcePath }).distinct()
      Console.log(
        "$logPrefix resolved ${inlineNames.size} target-declared + ${catalogResolved.size} " +
          "catalog scripted tool(s) across ${bundlePaths.size} bundle(s)",
      )
      return OnDeviceScriptedToolBundlePlan(
        bundlePaths = bundlePaths,
        advertisementOverrides = overrides,
        declaredToolNames = inlineNames + catalogResolved.map { it.name },
      )
    }
  }
}
