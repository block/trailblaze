package xyz.block.trailblaze.config.project

import java.io.File
import xyz.block.trailblaze.llm.config.ClasspathResourceDiscovery

/**
 * Where a [LoadedTrailblazePackManifest] came from, and how to read its sibling resources.
 *
 * A pack's `pack.yaml` may live on the filesystem (workspace-declared via `packs:` in
 * `trailblaze.yaml`) or be classpath-bundled (e.g. `trailblaze-models` ships `clock` /
 * `wikipedia` / `contacts`). Either way the pack manifest references sibling files by
 * relative path — `waypoints/<name>.waypoint.yaml`, `tools/<name>.tool.yaml`, etc. — and
 * those references must resolve consistently regardless of origin. This sealed type is
 * the abstraction the loader uses to read those siblings without caring which path the
 * pack took to get loaded.
 *
 * ## Containment guarantee
 *
 * [readSibling] enforces two layers of defense to ensure a pack manifest cannot escape
 * its own directory and read other resources on the classpath or filesystem:
 *
 *  1. **Textual rejection** of obviously-suspect path forms before any I/O — `..`
 *     segments, absolute paths, URL-encoded escape vectors (`%`), backslash-rooted
 *     paths, etc. Same rule for both source variants.
 *  2. **Canonical-path containment check** on the [Filesystem] variant — after
 *     resolving the relative path against the pack directory, the canonical form must
 *     still live under the canonical pack directory. This catches escapes the textual
 *     check can't see, e.g. a sibling symlink that resolves outside the pack tree, or
 *     filesystem normalization quirks (Windows short-name paths, case-folding).
 *
 * Pack manifests are commit-owned today and thus trusted, so both layers are
 * defense-in-depth — but the abstraction has to resist future use cases (remote pack
 * distribution, untrusted authors) without re-litigating the decision.
 *
 * ## Closed dispatch
 *
 * The read logic for each variant lives directly inside [readSibling]'s `when` block
 * rather than being an abstract method that subclasses override. The seal already
 * prevents external implementers; expressing the dispatch inline keeps the public API
 * surface to a single function and lets the containment check sit at one obvious site
 * for both variants.
 */
sealed class PackSource {
  /**
   * Reads a pack-relative file/resource as text, or returns null if it does not exist.
   *
   * @throws IllegalArgumentException if [relativePath] fails containment validation —
   *   contains `..` or `%` segments, is absolute, blank, or (for [Filesystem] sources)
   *   resolves to a canonical path outside [Filesystem.packDir]. See the containment
   *   guarantee in the class kdoc.
   */
  fun readSibling(relativePath: String): String? {
    requirePackRelativePath(relativePath)
    return when (this) {
      is Filesystem -> readFilesystemSibling(relativePath)
      is Classpath -> ClasspathResourceDiscovery.loadResource("$resourceDir/$relativePath")
    }
  }

  /** Human-readable identifier used in log messages and error reporting. */
  abstract fun describe(): String

  /** Filesystem-backed pack — `pack.yaml` lives on disk. Sibling reads use [File]. */
  data class Filesystem(val packDir: File) : PackSource() {
    override fun describe(): String = packDir.absolutePath
  }

  /**
   * Classpath-backed pack — the pack manifest lives at `<resourceDir>/pack.yaml` inside
   * a JAR or compiled-resources directory. Sibling reads go through
   * [ClasspathResourceDiscovery.loadResource] so the same call works for both `file:`
   * and `jar:` classpath entries.
   */
  data class Classpath(val resourceDir: String) : PackSource() {
    override fun describe(): String = "classpath:$resourceDir"
  }

  private fun Filesystem.readFilesystemSibling(relativePath: String): String? {
    val target = File(packDir, relativePath)
    val canonicalTarget = target.canonicalFile
    val canonicalPackDir = packDir.canonicalFile
    // Containment via NIO `Path.startsWith` — element-wise (not character-wise) so we don't
    // need to append `File.separator` and reason about prefix-overlap escapes ourselves
    // (`<packDir-evil>/x` would textually match `<packDir>` under naïve string-prefix
    // matching, but NIO compares path elements). NB: `Path.startsWith` returns false when
    // the two paths are equal, so the equality check below is load-bearing — without it,
    // a relativePath that resolves exactly to the pack directory itself would slip through
    // (the pack dir isn't a regular file, but the read attempt would give an unhelpful
    // error rather than the directed one here).
    val canonicalTargetPath = canonicalTarget.toPath()
    val canonicalPackDirPath = canonicalPackDir.toPath()
    require(canonicalTargetPath != canonicalPackDirPath) {
      "Pack-relative path must not resolve to the pack directory itself (got '$relativePath')"
    }
    require(canonicalTargetPath.startsWith(canonicalPackDirPath)) {
      "Pack-relative path resolved outside packDir: '$relativePath' -> $canonicalTarget " +
        "(packDir=${canonicalPackDir.path})"
    }
    return if (target.isFile) target.readText() else null
  }

  private companion object {
    // SISTER IMPLEMENTATION: `build-logic/src/main/kotlin/TrailblazeBundleTasks.kt`
    // (`TrailblazePackBundler.resolvePackRelativeToolFile`) deliberately re-implements
    // these same rules without depending on this module. Build-logic stays free of
    // trailblaze-models / trailblaze-common to keep the configuration-phase classpath light;
    // the duplication is the price. When editing this rule set, mirror the change in the
    // bundler (and vice versa) — drift would silently produce typed bindings for tool
    // paths the runtime then refuses to load.
    fun requirePackRelativePath(relativePath: String) {
      require(relativePath.isNotBlank()) {
        "Pack-relative path must not be blank"
      }
      require(!relativePath.startsWith("/") && !relativePath.startsWith("\\")) {
        "Pack-relative path must not start with '/' or '\\\\' (got '$relativePath')"
      }
      require(!File(relativePath).isAbsolute) {
        "Pack-relative path must not be absolute (got '$relativePath')"
      }
      // Reject URL-encoded escape vectors. Pack-relative refs are commit-owned strings
      // pointing at sibling files; legitimate filenames have no reason to contain `%`.
      // Banning them at the textual layer forecloses %2e%2e/ traversal even before the
      // canonical-path check on the filesystem variant.
      require(!relativePath.contains('%')) {
        "Pack-relative path must not contain '%' — URL encoding is not allowed (got '$relativePath')"
      }
      // Reject any path segment equal to ".." regardless of separator. Splitting on
      // both '/' and '\' covers Windows-style refs in the manifest.
      val segments = relativePath.split('/', '\\')
      require(segments.none { it == ".." }) {
        "Pack-relative path must not contain '..' segments (got '$relativePath')"
      }
    }
  }
}
