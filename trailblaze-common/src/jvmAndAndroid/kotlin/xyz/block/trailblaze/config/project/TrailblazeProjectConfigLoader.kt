package xyz.block.trailblaze.config.project

import java.io.File
import java.io.IOException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import xyz.block.trailblaze.config.AppTargetYamlConfig
import xyz.block.trailblaze.config.TrailblazeConfigYaml
import xyz.block.trailblaze.config.ToolSetYamlConfig
import xyz.block.trailblaze.config.ToolYamlConfig
import xyz.block.trailblaze.llm.config.BuiltInProviderConfig
import xyz.block.trailblaze.llm.config.TrailblazeConfigPaths
import xyz.block.trailblaze.util.Console

/**
 * Loads and resolves a `trailblaze.yaml` file into a [TrailblazeProjectConfig].
 *
 * Phase 1 scope: parse the schema and resolve `ref:` pointers to external YAML files.
 * Nothing in the runtime code path invokes this loader yet — later phases wire the loader
 * into target / toolset / trail discovery and replace the existing `trailblaze-config/`
 * scans. A file found at load time triggers a DEBUG log so the transition is observable
 * in logs even before behavior changes.
 *
 * ## Ref path resolution
 *
 * Refs inside `trailblaze.yaml` are anchor-relative — i.e., resolved against the
 * directory containing `trailblaze.yaml`. This matches the plan's path-resolution rules:
 * inside the workspace root file, "caller-relative" and "anchor-relative" collapse to
 * the same directory.
 *
 * - `targets/my-app.yaml`       → `<workspace>/targets/my-app.yaml`
 * - `/targets/my-app.yaml`      → `<workspace>/targets/my-app.yaml` (leading `/` stripped)
 * - `C:\absolute\path.yaml`     → used as-is on Windows
 *
 * Unix-style OS-absolute paths (e.g. `/Users/...`) are not an escape hatch from
 * `trailblaze.yaml` — they resolve as anchor-relative. This matches the plan's
 * "platform-appropriate absolute form" note.
 */
object TrailblazeProjectConfigLoader {

  /** The single config filename at a workspace root. Proxied from [TrailblazeConfigPaths]. */
  const val CONFIG_FILENAME = TrailblazeConfigPaths.CONFIG_FILENAME

  /**
   * Shared YAML instance. Reuses [TrailblazeConfigYaml.instance] so this loader stays
   * consistent with the other config loaders if parsing flags are adjusted centrally.
   */
  internal val yaml = TrailblazeConfigYaml.instance

  /**
   * Reads `trailblaze.yaml` at [configFile], decodes it, and returns a
   * [LoadedTrailblazeProjectConfig] bundle that preserves both the raw parse result
   * (refs intact) and the source file for downstream path resolution.
   *
   * Returns null when the file does not exist. Throws [TrailblazeProjectConfigException]
   * on parse failure or schema violations.
   */
  fun load(configFile: File): LoadedTrailblazeProjectConfig? {
    if (!configFile.exists()) return null
    val raw = parseFile(configFile)
    Console.log("Loaded trailblaze.yaml from ${configFile.absolutePath}")
    return LoadedTrailblazeProjectConfig(raw = raw, sourceFile = configFile)
  }

  /**
   * Resolves every [TargetEntry.Ref], [ToolsetEntry.Ref], [ToolEntry.Ref], and
   * [ProviderEntry.Ref] against [loaded]'s source directory and returns a new config with
   * all entries promoted to their `Inline` form.
   *
   * Throws [TrailblazeProjectConfigException] if a referenced file is missing or fails
   * to parse.
   */
  fun resolveRefs(loaded: LoadedTrailblazeProjectConfig): TrailblazeProjectConfig {
    val anchor = loaded.sourceFile.parentFile ?: File(".")
    val raw = loaded.raw
    return TrailblazeProjectConfig(
      defaults = raw.defaults,
      targets = raw.targets.map { entry ->
        when (entry) {
          is TargetEntry.Inline -> entry
          is TargetEntry.Ref -> TargetEntry.Inline(
            loadRef(entry.path, anchor, AppTargetYamlConfig.serializer(), "target"),
          )
        }
      },
      toolsets = raw.toolsets.map { entry ->
        when (entry) {
          is ToolsetEntry.Inline -> entry
          is ToolsetEntry.Ref -> ToolsetEntry.Inline(
            loadRef(entry.path, anchor, ToolSetYamlConfig.serializer(), "toolset"),
          )
        }
      },
      tools = raw.tools.map { entry ->
        when (entry) {
          is ToolEntry.Inline -> entry
          is ToolEntry.Ref -> ToolEntry.Inline(
            loadRef(entry.path, anchor, ToolYamlConfig.serializer(), "tool"),
          )
        }
      },
      providers = raw.providers.map { entry ->
        when (entry) {
          is ProviderEntry.Inline -> entry
          is ProviderEntry.Ref -> ProviderEntry.Inline(
            loadRef(entry.path, anchor, BuiltInProviderConfig.serializer(), "provider"),
          )
        }
      },
      llm = raw.llm,
    )
  }

  /** Convenience: [load] + [resolveRefs] in one call. */
  fun loadResolved(configFile: File): TrailblazeProjectConfig? =
    load(configFile)?.let(::resolveRefs)

  private fun parseFile(file: File): TrailblazeProjectConfig {
    return try {
      val content = file.readText()
      if (content.isBlank()) return TrailblazeProjectConfig()
      yaml.decodeFromString(TrailblazeProjectConfig.serializer(), content)
    } catch (e: IOException) {
      throw TrailblazeProjectConfigException(
        "Failed to read ${file.absolutePath}: ${e.message}",
        e,
      )
    } catch (e: SerializationException) {
      throw TrailblazeProjectConfigException(
        "Failed to parse ${file.absolutePath}: ${e.message}",
        e,
      )
    } catch (e: IllegalArgumentException) {
      throw TrailblazeProjectConfigException(
        "Invalid ${file.absolutePath}: ${e.message}",
        e,
      )
    }
  }

  private fun <T> loadRef(
    refPath: String,
    anchor: File,
    serializer: KSerializer<T>,
    entryLabel: String,
  ): T {
    val file = resolveRefFile(refPath, anchor)
    if (!file.exists()) {
      throw TrailblazeProjectConfigException(
        "Referenced $entryLabel file not found: $refPath (resolved to ${file.absolutePath})",
      )
    }
    return try {
      val content = file.readText()
      yaml.decodeFromString(serializer, content)
    } catch (e: IOException) {
      throw TrailblazeProjectConfigException(
        "Failed to read $entryLabel ref '$refPath' at ${file.absolutePath}: ${e.message}",
        e,
      )
    } catch (e: SerializationException) {
      throw TrailblazeProjectConfigException(
        "Failed to parse $entryLabel ref '$refPath' at ${file.absolutePath}: ${e.message}",
        e,
      )
    } catch (e: IllegalArgumentException) {
      throw TrailblazeProjectConfigException(
        "Invalid $entryLabel ref '$refPath' at ${file.absolutePath}: ${e.message}",
        e,
      )
    }
  }

  /**
   * Resolves a ref path against [anchor] per the Phase 1 rules above.
   *
   * Leading-`/` handling runs before [File.isAbsolute] because on Unix
   * `File("/foo").isAbsolute` is true and would otherwise swallow anchor-relative
   * paths. On Windows `/foo` is not absolute, but the same stripping is harmless.
   *
   * Parent-traversal refs (`../shared/targets.yaml`) are intentionally allowed —
   * they're legitimate in monorepo layouts where multiple workspaces share a common
   * targets/toolsets directory. `trailblaze.yaml` is commit-owned and trusted, so a
   * containment check would block valid uses without preventing any real threat.
   * This design decision should be revisited only if Trailblaze ever accepts config
   * from an untrusted source.
   */
  internal fun resolveRefFile(refPath: String, anchor: File): File {
    if (refPath.startsWith("/")) {
      return File(anchor, refPath.removePrefix("/"))
    }
    val asFile = File(refPath)
    if (asFile.isAbsolute) return asFile
    return File(anchor, refPath)
  }
}

/**
 * Pairs a decoded [TrailblazeProjectConfig] with the file it came from. The source file
 * is the anchor for resolving any remaining `ref:` pointers, as well as (in later
 * phases) the workspace root for trail discovery.
 */
data class LoadedTrailblazeProjectConfig(
  val raw: TrailblazeProjectConfig,
  val sourceFile: File,
)

/** Thrown by [TrailblazeProjectConfigLoader] on parse failure or missing refs. */
class TrailblazeProjectConfigException(message: String, cause: Throwable? = null) :
  RuntimeException(message, cause)
