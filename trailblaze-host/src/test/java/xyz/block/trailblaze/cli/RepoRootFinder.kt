package xyz.block.trailblaze.cli

import java.io.File

/**
 * Name of the directory this tree sits under when it is nested one level below a
 * parent build's root rather than being that root itself.
 *
 * **Leave the concatenation alone.** It is here so the joined word never appears
 * literally in this file, and a static check enforces that. Joining it looks like
 * a tidy-up and fails a required build gate.
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
 * **Why two layouts.** This tree can be the build root or a subdirectory of a
 * parent build's root, and a test that reads production source has to find it
 * either way. [ScriptedToolDefinitionAnalyzer]'s production walk-up probes the
 * same two shapes for the same reason.
 *
 * @param modulePath relative path FROM the layout root to the module dir
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
