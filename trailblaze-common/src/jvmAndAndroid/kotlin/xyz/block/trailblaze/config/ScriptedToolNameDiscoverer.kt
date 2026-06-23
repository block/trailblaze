package xyz.block.trailblaze.config

import xyz.block.trailblaze.config.project.TrailmapScriptedToolFile
import xyz.block.trailblaze.llm.config.ConfigResourceSource
import xyz.block.trailblaze.llm.config.TrailblazeConfigPaths
import xyz.block.trailblaze.llm.config.platformConfigResourceSource
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.util.Console

/**
 * Discovers the NAMES of scripted (`.ts` / `.js`) tools declared by per-trailmap descriptor
 * YAMLs, so a toolset can reference a scripted tool by bare name the same way it references a
 * Kotlin class-backed or YAML-defined tool.
 *
 * ## Background
 *
 * Scripted tools live under `trails/config/trailmaps/<id>/tools/` next to their descriptor YAML
 * (a [TrailmapScriptedToolFile] — a plain `.yaml`, NOT one of the `*.tool.yaml` /
 * `*.shortcut.yaml` / `*.trailhead.yaml` operational suffixes that [ToolYamlLoader] owns).
 * Historically these descriptors were "deliberately excluded" from the global name registry and
 * reached the runtime only through a target's `target.tools:` list — see the note in
 * `ToolYamlLoader.discoverTrailmapBundledToolContents`. As a result, a scripted name listed in a
 * toolset YAML was silently dropped by [ToolNameResolver.partitionLenient] ("Unknown tool name").
 *
 * This discoverer lifts the descriptors' *names* into [ToolNameResolver] at startup so a toolset
 * YAML can list a scripted tool and have it resolve + advertise like any other tool. The actual
 * bundling and dispatch of the `.ts` still flows through the existing per-session scripted-tool
 * machinery (host bun/QuickJS bundling, on-device QuickJS bundle) — this class only contributes
 * *names* to the resolution layer.
 *
 * ## Static-name boundary
 *
 * Only descriptors that declare an *explicit* name are discoverable: single-tool descriptors with
 * a top-level `name:`, and multi-tool descriptors with `tools[].name`. Meta-only / partial
 * descriptors that recover their name from the sibling `.ts` via the analyzer
 * ([TrailmapScriptedToolFile.requiresEnrichment]) are skipped here — their name isn't knowable
 * without running the analyzer, which doesn't run at framework startup.
 *
 * This is a deliberate contract: **a scripted tool that wants to be delivered by a toolset must be
 * statically nameable**, because the toolset references it by name before any analyzer runs. Such a
 * tool can still be delivered per-target via `target.tools:` (that path runs the analyzer). First-party
 * framework scripted tools (the ones that replace Kotlin tools in the bundled `trailblaze` trailmap)
 * always author an explicit `name:`, so they satisfy this contract.
 */
object ScriptedToolNameDiscoverer {

  /**
   * Discovered descriptor paired with the trailmap-relative path that located it
   * (e.g. `trailblaze/tools/frameworkToolCanary.yaml`). The caller uses [relPath] to
   * reconstruct the on-disk location when filesystem-backed script resolution is needed
   * (host bundler, on-device pre-compile).
   */
  data class DiscoveredDescriptor(
    val relPath: String,
    val descriptor: TrailmapScriptedToolFile,
  )

  /**
   * Discovers every statically-nameable scripted tool name under
   * `trails/config/trailmaps/<id>/tools/`. Lenient: a descriptor that fails to decode, or that
   * requires analyzer enrichment to name, is skipped (with a warning for the latter) rather than
   * aborting discovery of the rest — same per-file error containment as [ToolYamlLoader].
   */
  fun discoverAllNames(
    resourceSource: ConfigResourceSource = platformConfigResourceSource(),
  ): Set<ToolName> = discoverDescriptors(resourceSource)
    .flatMap { (relPath, descriptor) -> namesFrom(relPath, descriptor) }
    .toSet()

  /**
   * Returns a name-keyed index of every statically-nameable scripted tool descriptor,
   * so callers that need the full [TrailmapScriptedToolFile] (e.g. the host bundler
   * resolving toolset-delivered scripted tools to [InlineScriptToolConfig]) can look up
   * by the same [ToolName] the toolset catalog advertises.
   *
   * Multi-tool descriptors fan out to one entry per `tools[].name`; every entry shares
   * the same [DiscoveredDescriptor.relPath] so the caller can resolve relative `script:`
   * paths back to the descriptor's filesystem parent.
   */
  fun discoverDescriptorsByName(
    resourceSource: ConfigResourceSource = platformConfigResourceSource(),
  ): Map<ToolName, DiscoveredDescriptor> {
    val result = mutableMapOf<ToolName, DiscoveredDescriptor>()
    discoverDescriptors(resourceSource).forEach { (relPath, descriptor) ->
      val discovered = DiscoveredDescriptor(relPath, descriptor)
      for (name in namesFrom(relPath, descriptor)) {
        // Tool names are a flat global namespace (see [ToolNameResolver]). Two descriptors
        // claiming the same name would make resolution order-dependent on resource iteration
        // (the later one silently winning), so fail fast with an actionable diagnostic naming
        // both descriptors. The same-relPath guard lets a single multi-tool descriptor be
        // re-seen without tripping (it can only declare a given name once anyway).
        val existing = result[name]
        require(existing == null || existing.relPath == relPath) {
          "Scripted tool name collision: '${name.toolName}' is declared by both " +
            "'${existing!!.relPath}' and '$relPath'. Tool names share a flat global namespace; " +
            "rename one descriptor so each scripted tool name resolves to exactly one backing."
        }
        result[name] = discovered
      }
    }
    return result
  }

  /**
   * Walks the trailmap tree and decodes every scripted-tool descriptor under a `tools/`
   * directory. Returns relPath -> decoded descriptor. Files carrying the operational
   * `*.tool.yaml` / `*.shortcut.yaml` / `*.trailhead.yaml` suffixes (owned by [ToolYamlLoader])
   * are excluded by suffix; any remaining plain `.yaml` that fails to decode as a
   * [TrailmapScriptedToolFile] (e.g. it has no `script:` field) is skipped.
   */
  private fun discoverDescriptors(
    resourceSource: ConfigResourceSource,
  ): Map<String, TrailmapScriptedToolFile> {
    val matches = try {
      resourceSource.discoverAndLoadRecursive(
        directoryPath = TrailblazeConfigPaths.TRAILMAPS_DIR,
        suffix = ".yaml",
      )
    } catch (e: Exception) {
      Console.log(
        "ScriptedToolNameDiscoverer: WARNING: failed to scan " +
          "${TrailblazeConfigPaths.TRAILMAPS_DIR} (${e::class.simpleName}: ${e.message}). " +
          "Scripted-tool name discovery will return empty for this pass.",
      )
      return emptyMap()
    }
    val out = mutableMapOf<String, TrailmapScriptedToolFile>()
    matches.forEach { (relPath, content) ->
      // Layout: `<trailmap-id>/tools/[<subdir>/...]<name>.yaml`. The resource-source contract
      // strips the leading `trails/config/trailmaps/` prefix, so relPath starts at `<id>/...`.
      val segments = relPath.split('/')
      if (segments.size < 3 || segments[1] != "tools") return@forEach
      // The three operational suffixes are owned by ToolYamlLoader (class-backed / YAML-defined
      // tools + shortcut/trailhead edges). Scripted descriptors use a plain `.yaml`.
      if (isOperationalToolSuffix(relPath)) return@forEach
      val descriptor = try {
        TrailblazeConfigYaml.instance.decodeFromString(
          TrailmapScriptedToolFile.serializer(),
          content,
        )
      } catch (_: Exception) {
        // A plain `.yaml` under `tools/` that isn't a scripted descriptor (or is malformed)
        // is skipped leniently — TrailmapScriptedToolFile requires a `script:` field, so a
        // non-scripted YAML won't decode here.
        return@forEach
      }
      out[relPath] = descriptor
    }
    return out
  }

  private fun isOperationalToolSuffix(relPath: String): Boolean =
    relPath.endsWith(".tool.yaml") ||
      relPath.endsWith(".shortcut.yaml") ||
      relPath.endsWith(".trailhead.yaml")

  /**
   * Resolves the classpath/asset path of the committed `.bundle.js` for a discovered scripted-tool
   * descriptor — the pre-compiled QuickJS bundle that both the host (classpath resource) and the
   * on-device (APK asset) loaders read. Shared so the two callers can't drift on the naming rule.
   *
   * Built with `/` separators from the trailmap-relative [DiscoveredDescriptor.relPath] (which the
   * resource-source contract already normalizes to `/`) and the descriptor's `script:` base name,
   * so the result is identical across platforms — no `java.io.File` separator surprises on Windows.
   */
  fun bundleResourcePath(discovered: DiscoveredDescriptor): String {
    val script = discovered.descriptor.script
    require(script.isNotBlank()) {
      "Scripted-tool descriptor at '${discovered.relPath}' has a blank `script:` field — " +
        "cannot resolve its pre-compiled .bundle.js path."
    }
    // Strip any directory prefix (`./`, `a/b/`) and the extension; tolerate either separator.
    val base = script.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
    val parent = discovered.relPath.substringBeforeLast('/', missingDelimiterValue = "")
    val rel = if (parent.isEmpty()) "$base.bundle.js" else "$parent/$base.bundle.js"
    return "${TrailblazeConfigPaths.TRAILMAPS_DIR}/$rel"
  }

  /**
   * Resolves the committed `.bundle.js` resource/asset path directly from a scripted tool's
   * repo-root-relative `script:` path — the [bundleResourcePath] counterpart for callers that hold
   * an [xyz.block.trailblaze.config.InlineScriptToolConfig] (a target's `target.tools:` entry)
   * rather than a [DiscoveredDescriptor].
   *
   * Target-declared inline tools are surfaced from the bundled `targets/<id>.yaml` (a single
   * classpath resource read, [TrailblazeHostAppTarget.getInlineScriptTools]) rather than the
   * directory-recursive descriptor discovery [bundleResourcePath] feeds off. On Android the
   * classloader can't enumerate resource directories, so the on-device launcher resolves those
   * inline tools' bundles through this path instead — keyed off the `script:` already in the config.
   *
   * Maps e.g. `<module>/src/commonMain/resources/trails/config/trailmaps/<trailmap>/tools/X.ts`
   * (or any layout prefix) to `trails/config/trailmaps/<trailmap>/tools/X.bundle.js`, matching the
   * `${TRAILMAPS_DIR}/<trailmap>/tools/<base>.bundle.js` shape [bundleResourcePath] produces so the
   * build-time bundler can stage to one place both loaders agree on.
   */
  fun bundleResourcePathForScript(script: String): String {
    require(script.isNotBlank()) {
      "Cannot resolve a .bundle.js path for a blank `script:`."
    }
    val normalized = script.replace('\\', '/')
    val marker = "${TrailblazeConfigPaths.TRAILMAPS_DIR}/"
    // Everything from the trailmaps root onward (`<trailmap>/tools/<base>.ext`); fall back to just
    // the file name when the script path is authored without the standard trailmaps prefix.
    val rel = normalized.substringAfter(marker, missingDelimiterValue = normalized.substringAfterLast('/'))
    val base = rel.substringBeforeLast('.')
    return "${TrailblazeConfigPaths.TRAILMAPS_DIR}/$base.bundle.js"
  }

  /**
   * Extracts the statically-declared name(s) from a descriptor, or empty if the descriptor needs
   * analyzer enrichment to be named (logged once so an author who expected toolset delivery sees
   * why their tool didn't resolve).
   */
  private fun namesFrom(relPath: String, descriptor: TrailmapScriptedToolFile): List<ToolName> {
    // Multi-tool descriptor: every entry declares its own name. Guard non-empty so an
    // accidental `tools: []` falls through to the no-static-name diagnostic below rather than
    // silently contributing zero names with no signal.
    descriptor.tools?.takeIf { it.isNotEmpty() }?.let { entries -> return entries.map { ToolName(it.name) } }
    // Single-tool descriptor with an explicit name.
    descriptor.name?.let { return listOf(ToolName(it)) }
    // Meta-only / partial single-tool descriptor: the name is analyzer-derived from the `.ts`.
    Console.log(
      "ScriptedToolNameDiscoverer: skipping '$relPath' — descriptor declares no static name " +
        "(it is analyzer-derived). A scripted tool referenced by a toolset must declare an " +
        "explicit `name:` (single-tool) or `tools[].name` (multi-tool); analyzer-named tools " +
        "can only be delivered per-target via `target.tools:`.",
    )
    return emptyList()
  }
}
