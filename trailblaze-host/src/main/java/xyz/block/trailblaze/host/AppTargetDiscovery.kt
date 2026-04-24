package xyz.block.trailblaze.host

import xyz.block.trailblaze.config.AppTargetCompanion
import xyz.block.trailblaze.config.AppTargetYamlLoader
import xyz.block.trailblaze.config.ToolNameResolver
import xyz.block.trailblaze.config.ToolSetYamlLoader
import xyz.block.trailblaze.config.ToolYamlLoader
import xyz.block.trailblaze.llm.config.ClasspathConfigResourceSource
import xyz.block.trailblaze.llm.config.CompositeConfigResourceSource
import xyz.block.trailblaze.llm.config.ConfigResourceSource
import xyz.block.trailblaze.llm.config.FilesystemConfigResourceSource
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.util.Console
import java.io.File

/**
 * Shared app-target discovery for desktop builds.
 *
 * Runs `ToolYamlLoader` → `ToolSetYamlLoader` → `AppTargetYamlLoader` over a layered
 * resource source that combines the JVM classpath (framework-shipped config) with an
 * optional per-project `trailblaze-config/` directory (user-contributed config). Callers
 * parameterize the variable bits:
 *
 *  - [companions] — behavioral extensions keyed by target id (e.g. custom launch hooks).
 *    Opensource users typically pass an empty map.
 *  - [trailblazeConfigDirProvider] — returns the current per-project config dir, or `null`
 *    for classpath-only discovery. Typically a lambda that reads the live settings repo so
 *    UI / CLI / env var all resolve through one place.
 *  - [defaultFallback] — target to return when discovery finds nothing, so the UI's target
 *    picker has something to render on a clean checkout.
 *  - [logPrefix] — short tag for the diagnostic log lines (`[Block]`, `[OpenSource]`, …).
 *
 * Replaces what used to be separate `BlockAppTargets` / `OpenSourceAppTargets` objects with
 * nearly-identical discovery code. Each caller is now a thin wrapper that supplies its own
 * companions + fallback.
 */
object AppTargetDiscovery {

  /**
   * Discover targets. Intended to be invoked from a `by lazy` on the desktop-config's
   * `availableAppTargets`, so the work runs once per JVM.
   *
   * NOTE: results are computed eagerly on call and not cached inside this object — caching
   * is the caller's responsibility (typically via `by lazy`). Callers that want to react
   * to settings changes can wrap this in a `StateFlow` and recompute on signal.
   */
  fun discover(
    companions: Map<String, AppTargetCompanion> = emptyMap(),
    trailblazeConfigDirProvider: () -> File? = { null },
    defaultFallback: TrailblazeHostAppTarget = TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget,
    logPrefix: String = "[AppTargetDiscovery]",
  ): Set<TrailblazeHostAppTarget> {
    return try {
      val source = buildResourceSource(trailblazeConfigDirProvider(), logPrefix)
      val customToolClasses = ToolYamlLoader.discoverAndLoadAll(resourceSource = source)
      val resolver = ToolNameResolver.fromBuiltInAndCustomTools(customToolClasses)

      val toolSets = ToolSetYamlLoader.discoverAndLoadAll(
        toolNameResolver = resolver,
        resourceSource = source,
      )
      Console.log("$logPrefix Discovered ${toolSets.size} toolsets: ${toolSets.keys.sorted()}")

      val targets = AppTargetYamlLoader.discoverAndLoadAll(
        toolNameResolver = resolver,
        availableToolSets = toolSets,
        companions = companions,
        resourceSource = source,
      )
      Console.log("$logPrefix Discovered ${targets.size} app targets: ${targets.map { it.id }.sorted()}")

      targets.ifEmpty { setOf(defaultFallback) }
    } catch (e: Exception) {
      // Route through Console.error so the daemon's logger sees it. `printStackTrace()`
      // goes to stderr, which the launcher discards — the trace would be invisible.
      Console.error("$logPrefix Error loading app targets from YAML: ${e.message}\n${e.stackTraceToString()}")
      setOf(defaultFallback)
    }
  }

  /**
   * Classpath alone, or classpath + filesystem layered when a per-project dir is available.
   * Filesystem sources come after classpath so user-contributed entries override framework
   * defaults on filename collisions.
   */
  private fun buildResourceSource(
    trailblazeConfigDir: File?,
    logPrefix: String,
  ): ConfigResourceSource {
    if (trailblazeConfigDir == null) return ClasspathConfigResourceSource
    Console.log("$logPrefix Layering user config from: ${trailblazeConfigDir.absolutePath}")
    return CompositeConfigResourceSource(
      sources = listOf(
        ClasspathConfigResourceSource,
        FilesystemConfigResourceSource(rootDir = trailblazeConfigDir),
      ),
    )
  }
}
