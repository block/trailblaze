package xyz.block.trailblaze.config.project

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import xyz.block.trailblaze.llm.config.TrailblazeConfigPaths
import xyz.block.trailblaze.util.Console

/**
 * Result of resolving a Trailblaze workspace from a starting path.
 *
 * A workspace is the `trails/` directory that owns `trails/config/trailblaze.yaml`. When a
 * config file is found via walk-up, the workspace is [Configured] and downstream loaders
 * anchor against that `trails/` directory. When no file is found, the workspace is
 * [Scratch] and callers fall back to framework defaults (or legacy per-project settings)
 * with [dir] as the working anchor.
 *
 * This is the single primitive every entry point uses to pick a workspace — see
 * [findWorkspaceRoot] for the walk-up rule. Four entry points feed a `Path` into
 * [findWorkspaceRoot] and consume the sealed result: CLI no-arg, CLI with trail-file arg,
 * desktop app explicit pick, and MCP explicit param.
 */
sealed class WorkspaceRoot {
  abstract val dir: Path

  /**
   * A `trails/config/trailblaze.yaml` was found via walk-up from the start path. [dir] is
   * the owning `trails/` directory. Both paths are canonicalized via `toRealPath()` so
   * callers can compare them across symlinked clones.
   */
  data class Configured(override val dir: Path, val configFile: Path) : WorkspaceRoot()

  /**
   * No `trailblaze.yaml` was found above the start path. [dir] is the canonicalized start
   * directory; callers should treat it as a scratch workspace using framework defaults.
   */
  data class Scratch(override val dir: Path) : WorkspaceRoot()
}

/**
 * Walks up from [fromPath] looking for [TrailblazeConfigPaths.WORKSPACE_CONFIG_FILE].
 *
 * Resolution rules (mirrors the plan's "Workspace discovery" table):
 * - If [fromPath] points at a file, the search starts from its parent directory; if it
 *   points at a directory (or doesn't exist yet), it's used as the start directory.
 * - Starting from the resolved directory, each ancestor is checked for a regular file at
 *   `trails/config/trailblaze.yaml`. The first match wins → [WorkspaceRoot.Configured].
 * - Walk-up stops at the filesystem root; a missed walk returns [WorkspaceRoot.Scratch]
 *   anchored at the start directory. No `$HOME` fallback, no `git rev-parse`.
 *
 * Paths are canonicalized via [Path.toRealPath] so symlinked clones (e.g. `~/code/myapp`
 * symlinked to `~/Development/work/myapp`) resolve to the same [WorkspaceRoot.dir].
 * Open Question 2 in the plan flags Windows behaviour for the Phase 6 workspace-state
 * keying use — Phase 2 uses `toRealPath()` and leaves Windows verification to the GUI
 * pass where a user-facing path is actually surfaced.
 */
fun findWorkspaceRoot(fromPath: Path): WorkspaceRoot {
  val startDir = resolveStartDir(fromPath)
  var current: Path? = startDir
  while (current != null) {
    val workspaceDir = current.resolve(TrailblazeConfigPaths.WORKSPACE_TRAILS_DIR)
    val candidate = workspaceDir
      .resolve(TrailblazeConfigPaths.WORKSPACE_CONFIG_SUBDIR)
      .resolve(TrailblazeProjectConfigLoader.CONFIG_FILENAME)
    if (Files.isRegularFile(candidate)) {
      return WorkspaceRoot.Configured(
        dir = workspaceDir.toRealPathOrNormalized(),
        configFile = candidate.toRealPathOrNormalized(),
      )
    }
    current = current.parent
  }
  return WorkspaceRoot.Scratch(dir = startDir.toRealPathOrNormalized())
}

/**
 * Returns [fromPath] if it's an existing directory, its parent if it's a file, and
 * otherwise falls back to treating [fromPath] as a directory (it may not exist yet —
 * walk-up still works since `.parent` traversal is purely lexical when files aren't
 * present).
 */
private fun resolveStartDir(fromPath: Path): Path {
  val absolute = fromPath.toAbsolutePath()
  return when {
    Files.isDirectory(absolute) -> absolute
    Files.isRegularFile(absolute) -> absolute.parent ?: absolute
    // Path doesn't exist; assume the caller meant a directory (e.g. a scratch dir they're
    // about to create). Walk-up falls back to lexical parents, which is still meaningful.
    else -> absolute
  }
}

/**
 * Canonicalizes a path via [Path.toRealPath], with a graceful fallback for:
 *  - Non-existent paths (e.g. [WorkspaceRoot.Scratch] anchored at a path the user hasn't
 *    created yet) — walks up until an ancestor exists, canonicalizes that, and re-appends
 *    the non-existent tail so symlinked parents still collapse the way they would for an
 *    existing path. Example on macOS: `/var/folders/…/missing-leaf` canonicalizes to
 *    `/private/var/folders/…/missing-leaf` because `/var → /private/var` is a symlink.
 *  - Security-manager denials (JVM with `SecurityManager` installed blocking filesystem
 *    access) — falls back to `toAbsolutePath().normalize()` rather than propagating a
 *    [SecurityException] out of workspace discovery.
 */
private fun Path.toRealPathOrNormalized(): Path {
  val normalized = toAbsolutePath().normalize()
  return try {
    normalized.toRealPath()
  } catch (e: IOException) {
    // Log non-existent / permission-denied cases so the user has a chance to learn why
    // workspace discovery silently fell through. SecurityException is expected in sandboxed
    // VMs (defensive-only catch below) and not logged to avoid noise.
    Console.log("Workspace path canonicalization fell back for '$normalized': ${e.message}")
    canonicalizeExistingAncestor(normalized)
  } catch (_: SecurityException) {
    // Defensive: a SecurityManager denying `toRealPath()` would also deny the rest of the
    // filesystem ops used here. Falling back to the normalized form keeps discovery alive
    // rather than propagating an exception out of the sealed-type-returning primitive.
    normalized
  }
}

private fun canonicalizeExistingAncestor(normalized: Path): Path {
  var existing: Path? = normalized.parent
  while (existing != null && !existsSafely(existing)) {
    existing = existing.parent
  }
  if (existing == null) return normalized
  return try {
    val realExisting = existing.toRealPath()
    val tail = normalized.subpath(existing.nameCount, normalized.nameCount)
    realExisting.resolve(tail)
  } catch (_: IOException) {
    // Defensive: the ancestor existed a moment ago (we checked via Files.exists) but
    // toRealPath / subpath can still race against concurrent filesystem mutation or
    // be denied by a restrictive filesystem. Fall back rather than fail.
    normalized
  } catch (_: SecurityException) {
    normalized
  }
}

private fun existsSafely(path: Path): Boolean = try {
  Files.exists(path)
} catch (_: SecurityException) {
  // Defensive: SecurityManager denying Files.exists means we can't know — treat as
  // "doesn't exist" so the ancestor walk continues past this directory.
  false
}
