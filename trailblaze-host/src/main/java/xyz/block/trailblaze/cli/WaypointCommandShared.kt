package xyz.block.trailblaze.cli

import xyz.block.trailblaze.api.waypoint.WaypointMatchResult
import xyz.block.trailblaze.config.project.TrailblazeWorkspaceConfigResolver
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

/**
 * Resolves the effective waypoint root directory for `waypoint <subcmd>` invocations.
 *
 * Precedence:
 *  1. **`--root <path>`** — explicit override, used as-is, no warnings.
 *  2. **`--target <id>`** — convention shortcut. Resolves to
 *     `<workspace.configDir>/packs/<id>/waypoints/` (the canonical workspace pack location
 *     per the 2026-04-27 pack-manifest devlog). Warns if no such directory exists — packs
 *     bundled only on the framework classpath (e.g. the OSS `clock`/`contacts` packs, or
 *     packs that ship from a downstream Kotlin module's resources) are not found this way;
 *     users authoring against those should pass `--root` explicitly. Note this does NOT
 *     auto-create the target — bootstrapping a new pack is intentionally a documented
 *     manual process, not a CLI command.
 *  3. **Neither given** — silently falls back to [DEFAULT_WAYPOINT_ROOT]. Commands that
 *     pull in classpath-bundled packs via [WaypointDiscovery] will still find their
 *     waypoints; commands that don't (or that produce no results) should call
 *     [maybeWarnNoTarget] AFTER discovery to surface the `--target` hint only when it
 *     would actually have helped.
 *
 * Returns the resolved [File]. Only a real user error (invalid `--target`) is logged
 * during resolution; the no-flags case is silent because the warning would fire on
 * every successful `waypoint list` (where classpath packs supply 100+ results), which
 * would be a daily-flow regression.
 */
internal fun resolveWaypointRoot(
  rootOverride: File?,
  targetId: String?,
  fromPath: java.nio.file.Path = CliCallerContext.callerCwd(),
): File {
  if (rootOverride != null) return rootOverride
  if (targetId != null) {
    val workspace = TrailblazeWorkspaceConfigResolver.resolve(fromPath)
    val configDir = workspace.configDir
    val candidate = configDir?.let { File(it, "packs/$targetId/waypoints") }
    if (candidate != null && candidate.isDirectory) return candidate
    val expected = candidate?.absolutePath ?: "<no workspace anchor>/packs/$targetId/waypoints"
    Console.error(
      "Warning: --target $targetId did not resolve to a workspace pack at $expected. " +
        "If the pack is bundled on the classpath (framework or downstream modules), pass " +
        "--root <pack-source-dir> instead. Falling back to default --root $DEFAULT_WAYPOINT_ROOT.",
    )
    return File(DEFAULT_WAYPOINT_ROOT)
  }
  return File(DEFAULT_WAYPOINT_ROOT)
}

/**
 * Emits the "no --target specified" hint, but only when [resultIsEmpty] AND the user
 * passed neither flag — i.e. the empty result might actually be because they didn't
 * scope to the right pack.
 *
 * Don't fire from within [resolveWaypointRoot] unconditionally: `waypoint list` /
 * `locate` / `graph` / `validate` all merge classpath-bundled packs in regardless of
 * `--root`, so the "no flags" case typically returns 100+ waypoints and the warning
 * would be misleading noise. Calling this after discovery means the hint only appears
 * when it's actually actionable.
 */
internal fun maybeWarnNoTarget(
  rootOverride: File?,
  targetId: String?,
  resultIsEmpty: Boolean,
) {
  if (!resultIsEmpty) return
  if (rootOverride != null || targetId != null) return
  Console.error(
    "Hint: no --target or --root specified. Pass --target <pack-id> " +
      "(resolves to <workspace>/packs/<id>/waypoints/) or --root <path> for an " +
      "explicit override if you expected to find waypoints here.",
  )
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
