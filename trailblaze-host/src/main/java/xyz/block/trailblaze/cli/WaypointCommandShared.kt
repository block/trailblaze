package xyz.block.trailblaze.cli

import xyz.block.trailblaze.api.TargetTemplateContext
import xyz.block.trailblaze.api.waypoint.WaypointMatchResult
import xyz.block.trailblaze.cli.tune.WaypointSiblingCollisionGuard
import xyz.block.trailblaze.config.project.LoadedTrailblazeProjectConfig
import xyz.block.trailblaze.config.project.TrailblazeProjectConfig
import xyz.block.trailblaze.config.project.TrailblazeProjectConfigException
import xyz.block.trailblaze.config.project.TrailblazeProjectConfigLoader
import xyz.block.trailblaze.config.project.TrailblazeWorkspaceConfigResolver
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.waypoint.SessionLogScreenState
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

/**
 * Screen-state log filename suffixes the matcher consumes. Shared by every CLI command
 * that needs to walk a session set (`waypoint tune`, `waypoint propose`, future
 * automation lifts). Mirrors the producer set inside
 * [SessionLogScreenState.listScreenStateLogs] — keep both in lock-step or the
 * walk-and-load handshake silently loses log types.
 */
internal val SCREEN_STATE_LOG_SUFFIXES = listOf(
  "_AgentDriverLog.json",
  "_TrailblazeSnapshotLog.json",
  "_TrailblazeLlmRequestLog.json",
)

/**
 * Walks [root] for every screen-state log under any session directory and returns one
 * [WaypointSiblingCollisionGuard.SessionStep] per loadable step. Shared between
 * `waypoint tune` and `waypoint propose` (both treat the session set identically: one
 * matcher run per (waypoint × step), so the loader is single-source-of-truth).
 *
 * Sessions are discovered as the distinct parent directories of any screen-state log
 * matched by [SCREEN_STATE_LOG_SUFFIXES]. Per-step enumeration delegates to
 * [SessionLogScreenState.listScreenStateLogs] so each session's log set matches what
 * the matcher itself sees. Unloadable sessions / steps are logged via [Console.error]
 * and skipped — partial corpora are usable downstream, a single bad zip shouldn't take
 * the whole run with it.
 */
internal fun loadSessions(root: File): List<WaypointSiblingCollisionGuard.SessionStep> {
  val out = mutableListOf<WaypointSiblingCollisionGuard.SessionStep>()
  val sessionDirs = root.walkTopDown()
    .filter { it.isFile && SCREEN_STATE_LOG_SUFFIXES.any { sfx -> it.name.endsWith(sfx) } }
    .mapNotNull { it.parentFile }
    .distinct()
    .toList()
  if (sessionDirs.isEmpty()) return emptyList()
  for (sessionDir in sessionDirs) {
    val sessionId = sessionDir.name
    val logs = try {
      SessionLogScreenState.listScreenStateLogs(sessionDir)
    } catch (e: Exception) {
      Console.error("Skipping unreadable session ${sessionDir.absolutePath}: ${e.message}")
      continue
    }
    for (logFile in logs) {
      val stepId = logFile.relativeToOrSelf(root).path
      val screen = try {
        SessionLogScreenState.loadStep(logFile)
      } catch (e: Exception) {
        Console.error("Skipping unloadable step $stepId: ${e.message}")
        continue
      }
      out += WaypointSiblingCollisionGuard.SessionStep(sessionId, stepId, screen)
    }
  }
  return out
}

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

/**
 * Three-way outcome for [resolveTargetTemplateContext]. The pack manifest is the single
 * source of truth for a target's declared `app_ids:`; the CLI must either find them and
 * supply a template context to the matcher, or fail loudly with a clear message — there
 * is no override flag, because every legitimate use case ("validate this waypoint against
 * a captured log") already has the target named, and the manifest is what the runtime
 * itself reads at session start.
 */
internal sealed class TargetContextResolution {
  /** No `--target` supplied. The matcher gets null and templated selectors won't expand. */
  data object NoTarget : TargetContextResolution()

  /** `--target` resolved to a pack with declared appIds. */
  data class Resolved(val context: TargetTemplateContext) : TargetContextResolution()

  /**
   * `--target` was supplied but the pack couldn't be resolved or declared no `app_ids:`.
   * Callers should print [message] and exit non-zero — silently no-matching templated
   * selectors would just confuse the author.
   */
  data class Error(val message: String) : TargetContextResolution()
}

/**
 * Resolves the [TargetTemplateContext] for waypoint matching from `--target <id>` alone.
 *
 * The pack manifest's `target.platforms.<platform>.app_ids:` is the single source of truth.
 * Returns:
 *  - [TargetContextResolution.NoTarget] when no `--target` was supplied — the matcher
 *    receives a null context. Templated selectors won't expand; literal selectors work fine.
 *  - [TargetContextResolution.Resolved] when the pack was found and declares appIds.
 *  - [TargetContextResolution.Error] when `--target` named a pack that can't be loaded or
 *    declares no `app_ids:` for any platform. Callers exit non-zero with the message.
 *
 * No override flag — the pack-authoring side is the right place to fix a missing app id,
 * not a CLI argument that drifts from the runtime's actual session-start lookup.
 */
internal fun resolveTargetTemplateContext(
  targetId: String?,
  fromPath: java.nio.file.Path = CliCallerContext.callerCwd(),
): TargetContextResolution {
  if (targetId == null) return TargetContextResolution.NoTarget
  val resolved = try {
    loadResolvedConfig(fromPath)
  } catch (e: TrailblazeProjectConfigException) {
    return TargetContextResolution.Error(
      "--target $targetId: failed to load pack manifest: ${e.message}",
    )
  }
  val targetCfg = resolved?.targets?.firstOrNull { it.id == targetId }
    ?: return TargetContextResolution.Error(
      "--target $targetId: no such target in the resolved workspace + classpath packs. " +
        "Check the spelling, or that the pack is on the workspace's `packs:` list / framework classpath.",
    )
  val packAppIds = targetCfg.platforms?.values
    ?.flatMap { it.appIds.orEmpty() }
    ?.distinct()
    .orEmpty()
  if (packAppIds.isEmpty()) {
    return TargetContextResolution.Error(
      "--target $targetId: pack manifest declares no `app_ids:` for any platform. " +
        "Templated waypoint selectors (`{{target.appId}}`) can't be expanded without at " +
        "least one declared candidate. Add `app_ids:` under `target.platforms.<platform>:` " +
        "in the pack's manifest.",
    )
  }
  return TargetContextResolution.Resolved(
    TargetTemplateContext(appId = null, appIds = packAppIds),
  )
}

private fun loadResolvedConfig(fromPath: java.nio.file.Path) =
  TrailblazeWorkspaceConfigResolver.resolve(fromPath).configFile?.let { configFile ->
    TrailblazeProjectConfigLoader.loadResolvedRuntime(
      configFile = configFile,
      includeClasspathPacks = true,
    )
  } ?: TrailblazeProjectConfigLoader.resolveRuntime(
    loaded = LoadedTrailblazeProjectConfig(
      raw = TrailblazeProjectConfig(),
      sourceFile = File(".").absoluteFile,
    ),
    includeClasspathPacks = true,
  )

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
