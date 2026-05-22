package xyz.block.trailblaze.host

import java.nio.file.Path
import xyz.block.trailblaze.bundle.WorkspaceClientDtsGenerator
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.config.project.PackSource
import xyz.block.trailblaze.config.project.ResolvedPack
import xyz.block.trailblaze.toolcalls.ToolSetCatalogEntry
import xyz.block.trailblaze.toolcalls.TrailblazeToolSetCatalog
import xyz.block.trailblaze.toolcalls.toScriptedToolDescriptor
import xyz.block.trailblaze.util.Console

/**
 * Per-pack typed-bindings emitter — phase B of the pack-typing redesign. Replaces the
 * previous workspace-level [PerTargetClientDtsEmitter] (one `client.<target-id>.d.ts`
 * per target under `<workspace>/config/tools/.trailblaze/`) with one
 * `<packDir>/tools/.trailblaze/client.d.ts` per filesystem-backed pack.
 *
 * ## Three-layer pack-typing model
 *
 * Trailblaze packs compose around three distinct concerns, each with its own
 * resolution rule (see `PackRuntimeRegistryResolver`'s kdoc for the full breakdown):
 *
 *   | Concern | Composition rule |
 *   |---|---|
 *   | **Runtime registry** (what CAN dispatch) | Transitive union of every pack-in-closure's `platforms.*.tool_sets:` |
 *   | **Agent toolbox** (what the LLM sees) | Closest-wins on the target's `tool_sets:` + library `exports:` |
 *   | **Typed surface per pack** (what `.ts` author can call) | Pack's OWN declarations (non-transitive) + transitive `exports:` from deps |
 *
 * This emitter materializes the **typed-surface** layer. Each pack's emitted
 * `client.d.ts` covers exactly what its `.ts` authors can reach via `client.callTool` /
 * `client.tools.<name>`:
 *
 *   1. **Kotlin tools** resolved from THIS pack's OWN `platforms.<p>.tool_sets:`
 *      declarations (top-level `platforms:` for library packs;
 *      `target.platforms:` for target packs). Resolution runs through the same
 *      [TrailblazeToolSetCatalog.resolve] primitive the daemon uses for the
 *      runtime registry — single source of truth, no parallel definition.
 *   2. **Pack-local scripted tools** declared on the pack's own `target.tools:` list.
 *   3. **Transitively-inherited scripted tools** from deps' `exports:` field — for each
 *      dep in the pack's closure, scripted tools whose name appears in that dep's
 *      `exports:` list flow into the consumer's typed surface.
 *
 * Classpath-backed packs are skipped — they live inside JARs and can't accept written
 * `client.d.ts` files. Their consumers still get typed bindings through the workspace
 * packs that depend on them (transitive exports).
 *
 * Failure handling is the caller's responsibility — this helper just emits or throws.
 * The CLI's `trailblaze compile` lets exceptions propagate; the daemon-init bootstrap
 * downgrades to a warning so codegen failure doesn't take the daemon down with it.
 */
object PerPackClientDtsEmitter {

  /**
   * Emit one `client.d.ts` per filesystem-backed pack in [resolvedPacks]. Returns the
   * absolute paths of every file written.
   *
   * Empty pack list, or a pool consisting entirely of classpath-backed packs, returns
   * an empty list (no-op).
   *
   * [catalog] defaults to the classpath-scanned [TrailblazeToolSetCatalog.defaultEntries]
   * — production callers should rely on the default. Test seam: tests pass a synthetic
   * catalog so they can pin tool-class-level filter behavior (e.g.
   * `surfaceToScriptedTools = false` exclusion) without having to add real tools to the
   * default catalog or rebuild it from disk.
   */
  fun emit(
    resolvedPacks: List<ResolvedPack>,
    catalog: List<ToolSetCatalogEntry> = TrailblazeToolSetCatalog.defaultEntries(),
  ): List<Path> {
    if (resolvedPacks.isEmpty()) return emptyList()
    val packsById = resolvedPacks.associateBy { it.manifest.id }
    val generator = WorkspaceClientDtsGenerator()

    return resolvedPacks.mapNotNull { pack ->
      val packDir = (pack.source as? PackSource.Filesystem)?.packDir ?: return@mapNotNull null

      val toolDescriptors = resolveKotlinToolDescriptorsForPack(pack, catalog)
      val scriptedTools = collectPackTypedScriptedTools(pack, packsById)

      generator.generateForPackFromResolved(
        packDir = packDir.toPath(),
        toolDescriptors = toolDescriptors,
        scriptedTools = scriptedTools,
      )
    }
  }

  /**
   * Walks [pack]'s OWN per-platform `tool_sets:` declarations (top-level [platforms] on
   * library packs; [target].platforms on target packs) and resolves each toolset name
   * through [TrailblazeToolSetCatalog.resolve]. Returns one [ToolDescriptor] per class-backed
   * tool reachable from the union whose `@TrailblazeToolClass(surfaceToScriptedTools = true)`
   * — the scripted-tool surface gate is independent of the LLM agent toolbox gate (see
   * [TrailblazeToolClass.surfaceToLlm] vs [TrailblazeToolClass.surfaceToScriptedTools]).
   *
   * Pack-local — never reads dependencies'. The runtime registry (transitive union) is a
   * separate layer; the typed surface deliberately reflects ONLY what an author wrote into
   * THIS pack's manifest plus the explicit `exports:` channel from deps.
   *
   * YAML-defined-only tools (toolset entries without a backing KClass — e.g. `pressBack` in
   * the `navigation` toolset) are NOT included today. Matches the parity with the previous
   * per-target emitter; YAML-only inclusion is a future expansion.
   */
  private fun resolveKotlinToolDescriptorsForPack(
    pack: ResolvedPack,
    catalog: List<xyz.block.trailblaze.toolcalls.ToolSetCatalogEntry>,
  ): List<ai.koog.agents.core.tools.ToolDescriptor> {
    val ownToolSetNames = collectOwnToolSetNames(pack)
    if (ownToolSetNames.isEmpty()) return emptyList()
    val resolved = TrailblazeToolSetCatalog.resolve(
      requestedIds = ownToolSetNames.toList(),
      catalog = catalog,
    )
    return resolved.toolClasses.mapNotNull { it.toScriptedToolDescriptor() }
  }

  /**
   * Union of every pack-local `tool_sets:` list across every platform the pack declares.
   * Reads the AUTHORED shape ([pack.manifest]), not the post-resolution
   * `AppTargetYamlConfig.platforms` (which would carry inherited defaults flattened in).
   * Library packs declare under top-level `platforms:`; target packs under `target.platforms:`.
   */
  private fun collectOwnToolSetNames(pack: ResolvedPack): Set<String> {
    val targetPlatforms = pack.manifest.target?.platforms ?: emptyMap()
    val libraryPlatforms = pack.manifest.platforms ?: emptyMap()
    val ids = mutableSetOf<String>()
    targetPlatforms.values.forEach { platform ->
      platform.toolSets?.forEach { ids += it }
    }
    libraryPlatforms.values.forEach { platform ->
      platform.toolSets?.forEach { ids += it }
    }
    return ids
  }

  /**
   * Collect scripted tools that should appear in [pack]'s typed surface: the pack's own
   * `target.tools:` list, plus scripted tools transitively inherited from deps' `exports:`.
   *
   * The dep walk visits every pack reachable via `dependencies:` (excluding [pack]
   * itself). For each visited dep:
   *
   *   - If `exports:` is declared on the dep manifest, filter the dep's scripted-tool list
   *     to entries whose `name:` appears in `exports:` — those are the dep's public tools.
   *   - If `exports:` is omitted/null on the dep manifest, the dep contributes nothing to
   *     consumers' typed surface (Phase B contract: missing `exports:` means no public tools;
   *     pack scripted tools are internal helpers until explicitly listed).
   *
   * Deduplication by tool name keeps a single typed entry per name even if both the pack
   * and a dep declare it; the pack's own declaration wins (first-write semantics).
   *
   * **Phase B limitation — `exports:` flows scripted tools only.** A dep's `exports:` only
   * surfaces tools authored under its `target.tools:` (scripted `.ts` + `.yaml` pairs).
   * Library packs (no `target:` block) get [ResolvedPack.target] = null and therefore
   * cannot export scripted tools through this path today — they have nowhere to put them.
   * Composed `.tool.yaml` exports from a library pack's own `tools/` directory are
   * separately tracked and surface to consumers via the runtime registry, but they do NOT
   * land in the typed surface yet (they'd require a `ToolYamlConfig` → `ToolEntry` bridge
   * that handles composed-tool input schemas, deferred to Phase C/E along with the
   * `target.tools:` paths → names flip).
   *
   * Until then, a library pack's `exports:` list is effectively *advisory* for the typed
   * surface — it filters the runtime registry's view of "what consumers can see," but no
   * typed entries flow through to consumers' `client.d.ts`. Authoring teams should keep
   * library exports class-backed (via `tool_sets:` consumed by target packs) until the
   * later phases land.
   */
  private fun collectPackTypedScriptedTools(
    pack: ResolvedPack,
    packsById: Map<String, ResolvedPack>,
  ): List<InlineScriptToolConfig> {
    val byName = linkedMapOf<String, InlineScriptToolConfig>()
    // Track which pack contributed each name so we can detect cross-dep collisions
    // (two deps publishing the same tool name with potentially different schemas).
    val sourceByName = mutableMapOf<String, String>()

    // Pack-local scripted tools first — pack's own declarations win on name collision
    // with any inherited tool. This is the deliberate consumer-override pattern (a pack
    // can replace an inherited tool of the same name with its own definition). It is NOT
    // a cross-dep collision, so no diagnostic is emitted here.
    pack.target?.tools.orEmpty().forEach { tool ->
      byName.putIfAbsent(tool.name, tool)
      sourceByName.putIfAbsent(tool.name, pack.manifest.id)
    }

    // Walk the dep closure (excluding self) and gather exported scripted tools.
    val visited = mutableSetOf(pack.manifest.id)
    val frontier = ArrayDeque<String>()
    pack.manifest.dependencies.forEach { frontier.add(it) }
    while (frontier.isNotEmpty()) {
      val depId = frontier.removeFirst()
      if (!visited.add(depId)) continue
      val dep = packsById[depId] ?: run {
        // Loader's strict dep-graph validation should have rejected the closure before
        // we got here. Surface a diagnostic if it ever fires so a missing typed-surface
        // entry is grep-able instead of an invisible gap.
        Console.error(
          "PerPackClientDtsEmitter: pack '${pack.manifest.id}' references missing dep " +
            "'$depId' during typed-surface walk — loader should have caught this earlier.",
        )
        continue
      }
      val depExports = dep.manifest.exports?.toSet().orEmpty()
      if (depExports.isNotEmpty()) {
        val depToolsByName = dep.target?.tools.orEmpty().associateBy { it.name }
        // Validate every name listed in `exports:` actually corresponds to a scripted
        // tool the dep ships. A typo in `exports: [createMrchant]` would otherwise
        // silently drop the tool from consumers' typed surface — a maddening
        // debug-by-staring exercise. Hard-error so the dep author sees the typo the
        // first time the consumer pack compiles. This is the same fail-loud posture
        // as `TrailblazePackBundler`'s missing-tool-file check.
        val unresolvedExports = depExports - depToolsByName.keys
        if (unresolvedExports.isNotEmpty()) {
          throw IllegalStateException(
            "Pack '${dep.manifest.id}' declares `exports: ${unresolvedExports.sorted()}` " +
              "but no scripted tool with that name is authored under its `target.tools:`. " +
              "Either remove the unresolved name(s) from `exports:` or add the matching " +
              "tool YAML to the pack. (Detected during typed-surface codegen for consumer " +
              "pack '${pack.manifest.id}'.)",
          )
        }
        depExports.forEach { exportName ->
          val tool = depToolsByName.getValue(exportName)
          val existingSource = sourceByName[tool.name]
          if (existingSource == null) {
            byName[tool.name] = tool
            sourceByName[tool.name] = dep.manifest.id
          } else if (existingSource != pack.manifest.id && existingSource != dep.manifest.id) {
            // Two DIFFERENT deps both export the same name → ambiguous typed surface.
            // Pack-local-overrides-dep is intentional and skipped here (existingSource
            // == pack.manifest.id). Same-dep-revisit via diamond paths is suppressed by
            // the `visited` set. Anything that lands here is a genuine cross-dep clash
            // — fail loudly, matching `TrailblazePackBundler.collectScriptedToolEntries
            // ForClosure`'s cross-pack collision rule so authors don't ship a
            // declaration-merging mess that compiles cleanly but resolves to whichever
            // schema TypeScript happens to pick.
            throw IllegalStateException(
              "Scripted tool name '${tool.name}' is exported by both pack " +
                "'$existingSource' and pack '${dep.manifest.id}', both of which are in " +
                "the dependency closure of pack '${pack.manifest.id}'. Tool names must " +
                "be unique across a consumer's exported-dependency closure so the " +
                "generated typed surface has a single shape per name. Rename one of the " +
                "tools, or remove the colliding name from one of the deps' `exports:`.",
            )
          }
          // Else: existingSource == pack.manifest.id (pack-local override) — silent skip,
          // pack-local wins on its own scripted-tool declarations.
        }
      }
      dep.manifest.dependencies.forEach { frontier.add(it) }
    }

    return byName.values.toList()
  }
}
