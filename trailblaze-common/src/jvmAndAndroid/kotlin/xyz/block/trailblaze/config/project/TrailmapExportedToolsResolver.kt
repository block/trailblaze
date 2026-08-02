package xyz.block.trailblaze.config.project

import xyz.block.trailblaze.config.AppTargetYamlConfig
import xyz.block.trailblaze.config.InlineScriptToolConfig

/**
 * Folds the scripted tools a target's `dependencies:` closure publishes via `exports:` into the
 * consumer target's resolved `tools:` list — the single runtime delivery surface
 * ([xyz.block.trailblaze.model.TrailblazeHostAppTarget.getInlineScriptTools], sourced from
 * [AppTargetYamlConfig.tools]).
 *
 * ## Why this exists (the asymmetry it closes)
 *
 * Dependency `exports:` already flow into a consumer's **typed** surface
 * (`PerTrailmapClientDtsEmitter.collectTrailmapTypedScriptedTools`), so a recorded step calling a
 * dep-exported tool type-checks. But the **runtime** target-tool resolution
 * ([TrailblazeProjectConfigLoader]) only ever collected a trailmap's OWN `target.tools:` /
 * `platforms.<p>.tools:`, never the closure's exports. The net effect was a tool that compiled but
 * would not register for the consumer's session — dispatch failed as "unknown tool". This resolver
 * mirrors the typed-side closure-walk on the runtime-tools side so the two surfaces agree.
 *
 * ## Semantics (identical to the typed emitter)
 *
 * - **Own declarations win** on a name collision with an inherited tool (consumer-override).
 * - **`exports:` is the surface gate.** A dep contributes only the scripted tools named in its
 *   `exports:` list; a dep with no `exports:` contributes nothing.
 * - **Transitive.** The dependency closure is walked breadth-first (own → direct deps → their deps).
 * - **Fail loud on authoring mistakes.** An `exports:` name that no scripted tool ships, or the same
 *   name exported by two different deps in one closure, throws [TrailblazeProjectConfigException].
 *
 * ## Parity contract with build-logic
 *
 * The Gradle build-time generator `TrailblazeBundledConfigTasks.kt` bakes `targets/<id>.yaml` for
 * classpath-bundled targets, and that generated YAML — not this resolver — is what those targets
 * load at runtime. build-logic intentionally avoids depending on `:trailblaze-common`, so it carries
 * a sibling implementation of this same exports-fold. The two MUST stay in lockstep; a change to the
 * semantics above must be mirrored there.
 */
internal object TrailmapExportedToolsResolver {

  /**
   * Returns [ownTarget] with its `tools:` list extended by the scripted tools exported across
   * [ownDependencies] (transitively) through [trailmapsById]. Own tools win on name collision.
   */
  fun resolveTargetTools(
    ownTarget: AppTargetYamlConfig,
    ownTrailmapId: String,
    ownDependencies: List<String>,
    trailmapsById: Map<String, ResolvedTrailmap>,
  ): AppTargetYamlConfig {
    if (ownDependencies.isEmpty()) return ownTarget

    val byName = linkedMapOf<String, InlineScriptToolConfig>()
    val sourceByName = mutableMapOf<String, String>()
    ownTarget.tools.orEmpty().forEach { tool ->
      byName.putIfAbsent(tool.name, tool)
      sourceByName.putIfAbsent(tool.name, ownTrailmapId)
    }

    val visited = mutableSetOf(ownTrailmapId)
    val frontier = ArrayDeque(ownDependencies)
    while (frontier.isNotEmpty()) {
      val depId = frontier.removeFirst()
      if (!visited.add(depId)) continue
      val dep = trailmapsById[depId] ?: continue
      val depExports = dep.manifest.exports?.toSet().orEmpty()
      if (depExports.isNotEmpty()) {
        val depToolsByName = dep.target?.tools.orEmpty().associateBy { it.name }
        val unresolvedExports = depExports - depToolsByName.keys
        if (unresolvedExports.isNotEmpty()) {
          throw TrailblazeProjectConfigException(
            "Trailmap '${dep.manifest.id}' declares `exports: ${unresolvedExports.sorted()}` but no " +
              "scripted tool with that name is authored under its `target.tools:`. Either remove the " +
              "unresolved name(s) from `exports:` or add the matching tool. (Detected while resolving " +
              "runtime tools for consumer trailmap '$ownTrailmapId'.)",
          )
        }
        depExports.forEach { exportName ->
          val tool = depToolsByName.getValue(exportName)
          val existingSource = sourceByName[tool.name]
          if (existingSource == null) {
            byName[tool.name] = tool
            sourceByName[tool.name] = dep.manifest.id
          } else if (existingSource != ownTrailmapId && existingSource != dep.manifest.id) {
            throw TrailblazeProjectConfigException(
              "Scripted tool name '${tool.name}' is exported by both trailmap '$existingSource' and " +
                "trailmap '${dep.manifest.id}', both in the dependency closure of trailmap " +
                "'$ownTrailmapId'. Tool names must be unique across a consumer's exported-dependency " +
                "closure. Rename one of the tools, or remove the colliding name from one of the deps' " +
                "`exports:`.",
            )
          }
        }
      }
      dep.manifest.dependencies.forEach { frontier.add(it) }
    }

    if (byName.size == ownTarget.tools.orEmpty().size) return ownTarget
    return ownTarget.copy(tools = byName.values.toList())
  }
}
