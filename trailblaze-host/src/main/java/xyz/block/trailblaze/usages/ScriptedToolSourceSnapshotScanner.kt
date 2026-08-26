package xyz.block.trailblaze.usages

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import java.io.File
import xyz.block.trailblaze.config.project.TrailblazeProjectConfigLoader
import xyz.block.trailblaze.config.project.TrailmapScriptedToolFile

/**
 * Builds a [ToolSourceSnapshot] ([ToolKey] → implementing script + declaring descriptor) for every trailmap under one
 * `trailmaps/` directory — dir-parameterized so it can run against BOTH the working tree and a
 * materialized git-ref tree, with no process-global state (unlike the loader/catalog paths,
 * which resolve the active workspace through singletons).
 *
 * Tools are keyed per trailmap, matching the loader's uniqueness rule (names are unique WITHIN a
 * trailmap, not across them) — a global name key would silently drop a second trailmap's
 * same-named tool and miss every trail affected by an edit to it.
 *
 * Discovery mirrors the runtime loader's two passes over each trailmap's `tools/` subtree:
 *
 *  1. YAML descriptors (the loader's operational suffixes excluded): declared `name:` /
 *     `tools[].name`, with `script:` resolved against the descriptor's directory. A meta-only
 *     descriptor (no name, no tools — the analyzer-enriched shape) falls back to harvesting
 *     every `export const <name> = trailblaze.tool` binding from its script.
 *  2. Bare `.ts` files no descriptor covers, registered only when the file declares exactly
 *     one typed binding (0 = helper module, 2+ = needs a YAML descriptor) — the loader's rule.
 *
 * Divergence from the sisters, on purpose: nothing here THROWS on author errors (malformed
 * YAML, duplicate names). This scanner runs against historical refs that may predate a fix, and
 * an aborted comparison would report nothing; every anomaly becomes a warning instead, and the
 * caller's report carries fail-open semantics.
 *
 * The four other sister implementations (runtime loader, daemon bundler, `.d.ts` generator,
 * Gradle bundled-config generator) are enumerated on
 * `TrailblazeProjectConfigLoader.discoverTrailmapScriptedTools`. Keep the discovery rules in
 * lockstep with them; only the never-throw and dir-parameterized properties above are meant
 * to differ.
 *
 * SISTER-IMPL-TAG: trailmap-scripted-tool-discovery.
 */
object ScriptedToolSourceSnapshotScanner {

  private const val SCRIPTED_TOOLS_DIR = "tools"

  // The loader's list, referenced directly: an operational `.shortcut.yaml`/`.trailhead.yaml`/
  // `.waypoint.yaml` under `tools/` must not reach the descriptor decode path here either.
  private val OPERATIONAL_TOOL_YAML_SUFFIXES = TrailblazeProjectConfigLoader.OPERATIONAL_TOOL_YAML_SUFFIXES

  /** The loader's typed-binding shape: `export const <name> = trailblaze.tool<...>(...)`. */
  private val TYPED_TOOL_BINDING_PATTERN =
    Regex("""(?m)^\s*export\s+const\s+(\w+)\s*=\s*trailblaze\.tool\s*[<(]""")

  private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))

  fun snapshot(trailmapsDir: File): ToolSourceSnapshot {
    val warnings = mutableListOf<String>()
    val toolSources = linkedMapOf<ToolKey, ToolSource>()
    if (!trailmapsDir.isDirectory) return ToolSourceSnapshot(emptyMap(), warnings)

    for (trailmapDir in trailmapsDir.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }) {
      val toolsDir = File(trailmapDir, SCRIPTED_TOOLS_DIR)
      if (!toolsDir.isDirectory) continue
      val files = toolsDir.walkTopDown()
        .onEnter { it.name != ".trailblaze" } // generated subtree, not authored sources
        .filter { it.isFile }
        .sortedBy { it.path }
        .toList()

      // Pass 1: YAML descriptors.
      val descriptorCoveredScripts = mutableSetOf<File>()
      for (descriptorFile in files.filter { f ->
        f.name.endsWith(".yaml") && OPERATIONAL_TOOL_YAML_SUFFIXES.none { f.name.endsWith(it) }
      }) {
        val descriptor = runCatching {
          yaml.decodeFromString(TrailmapScriptedToolFile.serializer(), descriptorFile.readText())
        }.getOrElse { e ->
          warnings += "${descriptorFile.path}: descriptor did not decode (${e.message?.lineSequence()?.firstOrNull()})"
          continue
        }
        val script = File(descriptor.script).let {
          if (it.isAbsolute) it else File(descriptorFile.parentFile, descriptor.script).toPath().normalize().toFile()
        }
        descriptorCoveredScripts += script.absoluteFile
        if (!script.isFile) {
          warnings += "${descriptorFile.path}: script '${descriptor.script}' does not exist"
          continue
        }
        val names = when {
          descriptor.tools != null -> descriptor.tools.orEmpty().map { it.name }
          descriptor.name != null -> listOf(descriptor.name.orEmpty())
          else -> {
            // Meta-only descriptor: names live in the script's typed bindings.
            val harvested = typedBindingNames(script)
            if (harvested.isEmpty()) {
              warnings += "${descriptorFile.path}: meta-only descriptor whose script declares no " +
                "`export const <name> = trailblaze.tool` binding — its tools are invisible to this scan"
            }
            harvested
          }
        }
        for (name in names.filter { it.isNotBlank() }) {
          val key = ToolKey(trailmap = trailmapDir.name, name = name)
          val previous = toolSources.putIfAbsent(key, ToolSource(script = script, descriptor = descriptorFile))
          if (previous != null && previous.script != script) {
            warnings += "trailmap '${trailmapDir.name}': duplicate tool name '$name' " +
              "(${previous.script.path} and ${script.path}); first wins"
          }
        }
      }

      // Pass 2: bare `.ts` files no descriptor covers, single typed binding only.
      for (tsFile in files.filter { f ->
        f.name.endsWith(".ts") && !f.name.endsWith(".test.ts") && !f.name.endsWith(".d.ts") &&
          f.absoluteFile !in descriptorCoveredScripts
      }) {
        val names = typedBindingNames(tsFile)
        when {
          names.size == 1 -> {
            val name = names.single()
            val key = ToolKey(trailmap = trailmapDir.name, name = name)
            val previous = toolSources.putIfAbsent(key, ToolSource(script = tsFile))
            if (previous != null && previous.script != tsFile) {
              warnings += "trailmap '${trailmapDir.name}': duplicate tool name '$name' " +
                "(${previous.script.path} and ${tsFile.path}); first wins"
            }
          }
          names.size > 1 ->
            warnings += "${tsFile.path}: declares ${names.size} typed tool bindings but has no YAML " +
              "descriptor — the loader would not register it, so this scan doesn't either"
          // 0 bindings: a helper module, not a tool — correctly invisible.
        }
      }
    }
    return ToolSourceSnapshot(toolSources, warnings)
  }

  private fun typedBindingNames(script: File): List<String> =
    runCatching { TYPED_TOOL_BINDING_PATTERN.findAll(script.readText()).map { it.groupValues[1] }.toList() }
      .getOrDefault(emptyList())
}
