package xyz.block.trailblaze.cli

import xyz.block.trailblaze.api.waypoint.WaypointMatchResult
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.waypoint.WaypointLoader
import java.io.File

/**
 * Default `--root` for `waypoint list/locate/validate` subcommands.
 *
 * Resolved relative to the JVM's current working directory, **not** to any repo or
 * install layout. This is intentional: the same launcher binary is used both from a
 * cloned repo (where you might run `cd opensource && ./trailblaze waypoint list`) and
 * from a homebrew-installed `trailblaze` invoked anywhere a user has organized their
 * own trails. A cwd-relative default works in both cases as long as the user's
 * convention is "trails live next to where I run from"; if it isn't, they pass
 * `--root <path>` explicitly.
 */
internal const val DEFAULT_WAYPOINT_ROOT = "./trails"

/** Surfaces any per-file parse failures from [WaypointLoader.loadAllResilient]. */
internal fun reportLoadFailures(result: WaypointLoader.LoadResult) {
  if (result.failures.isEmpty()) return
  Console.error("WARNING: ${result.failures.size} waypoint file(s) failed to parse:")
  for (failure in result.failures) {
    Console.error("  ${failure.file}: ${failure.cause.message}")
  }
}

/**
 * Validates that [file] (a positional or `--file`-supplied log path) exists and is a
 * regular file. Returns the file on success, or null after writing a `Console.error`
 * message — callers should return [picocli.CommandLine.ExitCode.USAGE] in that case.
 */
internal fun validateLogFile(file: File, label: String = "Log file"): File? {
  if (!file.exists()) {
    Console.error("$label does not exist: ${file.absolutePath}")
    return null
  }
  if (!file.isFile) {
    Console.error("$label is not a regular file: ${file.absolutePath}")
    return null
  }
  return file
}

/**
 * Validates that [dir] (a `--session`-supplied directory) exists and is a directory.
 * Returns the directory on success, or null after writing a `Console.error` message.
 */
internal fun validateSessionDir(dir: File): File? {
  if (!dir.exists()) {
    Console.error("--session does not exist: ${dir.absolutePath}")
    return null
  }
  if (!dir.isDirectory) {
    Console.error("--session is not a directory: ${dir.absolutePath}")
    return null
  }
  return dir
}

/** Renders a [WaypointMatchResult] into a multi-line, human-friendly string. */
internal fun formatResult(r: WaypointMatchResult): String = buildString {
  if (r.skipped != null) {
    appendLine("SKIPPED: ${r.skipped}")
    return@buildString
  }
  appendLine(if (r.matched) "MATCH" else "NO MATCH")
  if (r.matchedRequired.isNotEmpty()) {
    appendLine("  matched required (${r.matchedRequired.size}):")
    for (m in r.matchedRequired) {
      val d = m.entry.description ?: m.entry.selector.description()
      appendLine("    ✓ $d  (matches=${m.matchCount}, minCount=${m.entry.minCount})")
    }
  }
  if (r.missingRequired.isNotEmpty()) {
    appendLine("  missing required (${r.missingRequired.size}):")
    for (m in r.missingRequired) {
      val d = m.entry.description ?: m.entry.selector.description()
      appendLine("    ✗ $d  (matches=${m.matchCount}, minCount=${m.entry.minCount})")
    }
  }
  if (r.presentForbidden.isNotEmpty()) {
    appendLine("  forbidden present (${r.presentForbidden.size}):")
    for (m in r.presentForbidden) {
      val d = m.entry.description ?: m.entry.selector.description()
      appendLine("    ✗ $d  (matches=${m.matchCount})")
    }
  }
}
