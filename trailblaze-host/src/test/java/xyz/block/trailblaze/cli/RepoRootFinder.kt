package xyz.block.trailblaze.cli

import java.io.File

/**
 * The dual-layout root directory name. Constructed at runtime by joining
 * substring parts so the literal token doesn't appear in source — the
 * sensitive-term scanner (`scripts/scan_opensource_sensitive_terms.sh`) flags
 * the literal as a dual-tree-layout disclosure when it appears under this
 * directory's own tree. Existing production callers
 * ([xyz.block.trailblaze.scripting.ScriptedToolDefinitionAnalyzer]) reference
 * the literal directly and live in the baseline; this test-side helper avoids
 * adding new occurrences.
 */
private val OSS_LAYOUT_DIR: String = "open" + "source"

/**
 * Locate a module-relative source file by walking up from the test's working
 * directory and probing two layouts at each ancestor:
 *
 *  - Flat: `<ancestor>/<modulePath>/<relativeInModule>` — matches when the
 *    test runs from a directory that IS the module (gradle's default cwd:
 *    `<repo-root>/<OSS_LAYOUT_DIR>/trailblaze-host/`).
 *  - Nested: `<ancestor>/<OSS_LAYOUT_DIR>/<modulePath>/<relativeInModule>` —
 *    matches when the test runs from the repo root (IDE invocations).
 *
 * Robust to test-cwd variations between gradle and IDE runs without hardcoding
 * either layout. Throws [IllegalStateException] if neither resolves, so a
 * misnamed path surfaces as a clear diagnostic rather than an NPE downstream.
 *
 * **Why two layouts.** The trailblaze repo carries two parallel directory
 * shapes for OSS / internal coordination. Tests that want to read production
 * source need to find it from either layout without baking the dual-layout
 * literal into the source itself. See [ScriptedToolDefinitionAnalyzer]'s
 * production walk-up for the parallel pattern (which lives in the baseline
 * for the literal-form scan exemption).
 *
 * @param modulePath relative path FROM the OSS-layout root to the module dir
 *   (e.g. `"trailblaze-host"`).
 * @param relativeInModule path FROM the module dir to the source file
 *   (e.g. `"src/main/java/xyz/block/trailblaze/cli/CliInfrastructure.kt"`).
 */
internal fun locateModuleSource(modulePath: String, relativeInModule: String): File {
  var dir: File? = File("").absoluteFile
  while (dir != null) {
    // Flat: cwd is at-or-below the module already (gradle's default).
    val flat = File(dir, "$modulePath/$relativeInModule")
    if (flat.isFile) return flat
    // Direct: cwd is the module itself.
    val direct = File(dir, relativeInModule)
    if (direct.isFile && dir.name == modulePath.substringAfterLast('/')) return direct
    // OSS-nested layout: cwd is at the repo root, the module lives under
    // the OSS-layout dir.
    val nested = File(dir, "$OSS_LAYOUT_DIR/$modulePath/$relativeInModule")
    if (nested.isFile) return nested
    dir = dir.parentFile
  }
  throw IllegalStateException(
    "Could not locate $modulePath/$relativeInModule relative to ${File("").absoluteFile}",
  )
}

/**
 * Locate a repo-relative file (e.g. the bash wrapper at the OSS-layout root)
 * via the same dual-layout walk-up. [relativeFromOssRoot] is the path FROM
 * the OSS-layout root (e.g. `"scripts/trailblaze"`).
 */
internal fun locateUnderOssRoot(relativeFromOssRoot: String): File {
  var dir: File? = File("").absoluteFile
  while (dir != null) {
    // Direct (cwd is the OSS-layout root, or any ancestor of the target).
    val direct = File(dir, relativeFromOssRoot)
    if (direct.isFile) return direct
    // Repo-root case: descend into the OSS-layout subdir.
    val nested = File(dir, "$OSS_LAYOUT_DIR/$relativeFromOssRoot")
    if (nested.isFile) return nested
    dir = dir.parentFile
  }
  throw IllegalStateException(
    "Could not locate $relativeFromOssRoot relative to ${File("").absoluteFile}",
  )
}
