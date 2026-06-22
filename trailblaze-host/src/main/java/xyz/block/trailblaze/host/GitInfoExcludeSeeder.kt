package xyz.block.trailblaze.host

import java.nio.file.Files
import java.nio.file.Path
import xyz.block.trailblaze.util.Console

/**
 * Seeds the local `.git/info/exclude` — once, on first generation — with the patterns that
 * match every file `trailblaze check` generates, so a developer never has to wonder which
 * of those files to commit.
 *
 * **Why `.git/info/exclude` and not a committed `.gitignore`.** The per-trailmap
 * `.gitignore` ([PerTrailmapTsconfigEmitter]) already covers the two `tools/` artifacts and
 * IS committed — it travels with a vendored/published trailmap. But it can't cover the
 * *workspace-level* artifacts (`trails/.trailblaze/`, `trails/config/dist/`): those live
 * above the trailmap, so only a workspace- or repo-level rule can hide them. In Block's own
 * repos the repo-root `.gitignore` does that; an external user's own repo has no such rule,
 * so `check` leaves `.trailblaze/` and `dist/` showing as untracked. Seeding the repo's
 * local `info/exclude` closes that gap everywhere, with nothing for the user to commit.
 *
 * **First-generation only.** A sentinel file under `.trailblaze/` records that we've seeded;
 * once it exists we never touch `info/exclude` again — so if a developer later edits or
 * deletes our block, we don't fight them by re-adding it. (Nuking the whole `.trailblaze/`
 * cache resets the sentinel, which re-seeds on the next run — that's fresh local state, the
 * same posture as re-extracting the SDK bundle.)
 *
 * **Best-effort.** Every failure path is swallowed with an info-level log. Seeding a local
 * git convenience must never break `trailblaze check`, and a workspace that isn't inside a
 * git repo simply gets nothing (and no sentinel, so a later `git init` is still covered).
 *
 * Handles both a plain `.git` directory and a `.git` *file* (linked worktree / submodule),
 * where the shared `info/exclude` lives in the common dir the worktree gitdir's `commondir`
 * pointer references — i.e. the same path `git rev-parse --git-path info/exclude` resolves.
 */
object GitInfoExcludeSeeder {

  /**
   * Seed `info/exclude` for the git repo containing [workspaceTrailsRoot] (the workspace's
   * `trails/` directory). No-op when already seeded, when the block is already present, or
   * when there's no enclosing git repo.
   */
  fun seedOnce(workspaceTrailsRoot: Path) {
    try {
      val sentinel = workspaceTrailsRoot
        .resolve(WorkspaceTypeScriptSetup.GENERATED_DIR_NAME)
        .resolve(SENTINEL_FILENAME)
      if (Files.exists(sentinel)) return

      val excludeFile = resolveGitInfoExclude(workspaceTrailsRoot) ?: return

      val existing = if (Files.isRegularFile(excludeFile)) Files.readString(excludeFile) else ""
      val alreadyPresent = existing.lineSequence().any { it == MANAGED_HEADER }
      if (!alreadyPresent) {
        Files.createDirectories(excludeFile.parent)
        val updated = buildString {
          append(existing)
          if (existing.isNotEmpty()) {
            if (!existing.endsWith("\n")) append('\n')
            append('\n')
          }
          append(MANAGED_BLOCK)
        }
        Files.writeString(excludeFile, updated)
        Console.info("[GitInfoExcludeSeeder] Seeded Trailblaze artifact patterns into $excludeFile")
      }

      // Record that seeding happened (or that our block was already present) so we never
      // re-touch info/exclude — honoring "if the user removes it, that's on them."
      Files.createDirectories(sentinel.parent)
      Files.writeString(sentinel, SENTINEL_CONTENT)
    } catch (e: Exception) {
      Console.info(
        "[GitInfoExcludeSeeder] Skipped (non-fatal): ${e.message ?: e.javaClass.simpleName}",
      )
    }
  }

  /**
   * Resolve the absolute path to the enclosing repo's `.git/info/exclude`, or null when
   * [start] is not inside a git working tree.
   */
  internal fun resolveGitInfoExclude(start: Path): Path? {
    val gitEntry = findGitEntry(start) ?: return null
    val commonDir = when {
      Files.isDirectory(gitEntry) -> resolveCommonDir(gitEntry)
      Files.isRegularFile(gitEntry) -> {
        val gitDir = parseGitdirFile(gitEntry) ?: return null
        resolveCommonDir(gitDir)
      }
      else -> return null
    }
    return commonDir.resolve("info").resolve("exclude")
  }

  /** Walk up from [start] (inclusive) for the nearest `.git` directory or file. */
  private fun findGitEntry(start: Path): Path? {
    var dir: Path? = start.toAbsolutePath().normalize()
    while (dir != null) {
      val candidate = dir.resolve(".git")
      if (Files.exists(candidate)) return candidate
      dir = dir.parent
    }
    return null
  }

  /** Parse `gitdir: <path>` from a `.git` file, resolved relative to the file's parent. */
  private fun parseGitdirFile(gitFile: Path): Path? {
    val line = Files.readAllLines(gitFile).firstOrNull { it.startsWith(GITDIR_PREFIX) } ?: return null
    val raw = line.removePrefix(GITDIR_PREFIX).trim()
    if (raw.isEmpty()) return null
    val p = Path.of(raw)
    return (if (p.isAbsolute()) p else gitFile.toAbsolutePath().parent.resolve(p)).normalize()
  }

  /**
   * Map a git dir to its *common* dir. A linked worktree's gitdir carries a `commondir`
   * file pointing (often relatively) at the shared dir that owns `info/exclude`; a normal
   * repo has no `commondir` and is its own common dir.
   */
  private fun resolveCommonDir(gitDir: Path): Path {
    val commonDirFile = gitDir.resolve("commondir")
    if (!Files.isRegularFile(commonDirFile)) return gitDir.normalize()
    val raw = Files.readAllLines(commonDirFile).firstOrNull()?.trim().orEmpty()
    if (raw.isEmpty()) return gitDir.normalize()
    val p = Path.of(raw)
    return (if (p.isAbsolute()) p else gitDir.resolve(p)).normalize()
  }

  /** Marker file under `.trailblaze/` that gates the one-time seeding. */
  internal const val SENTINEL_FILENAME = ".git-info-exclude-seeded"

  private const val GITDIR_PREFIX = "gitdir:"

  /** First line of our managed block — also the idempotency marker. */
  internal const val MANAGED_HEADER =
    "# Trailblaze generated artifacts (seeded once by `trailblaze check`)."

  /**
   * Patterns that match every file `trailblaze check` generates. Globs (not anchored
   * paths) so they cover every workspace + trailmap in the repo and survive a workspace
   * being moved. Mirrors the artifact paths written by [PerTrailmapTsconfigEmitter] and
   * [WorkspaceTypeScriptSetup]; the per-trailmap `.gitignore` itself is deliberately NOT
   * listed — that file is meant to stay visible and committed.
   */
  internal val MANAGED_PATTERNS = listOf(
    "**/trails/config/trailmaps/*/tools/tsconfig.json",
    "**/trails/config/trailmaps/*/tools/trailblaze-client.d.ts",
    "**/trails/.trailblaze/",
    "**/trails/config/dist/",
  )

  private val MANAGED_BLOCK: String = buildString {
    append(MANAGED_HEADER).append('\n')
    append("# Local-only; edit or delete freely — Trailblaze won't re-add these.").append('\n')
    MANAGED_PATTERNS.forEach { append(it).append('\n') }
  }

  private const val SENTINEL_CONTENT =
    "Trailblaze seeded .git/info/exclude with generated-artifact patterns.\n" +
      "Delete this file to let `trailblaze check` re-seed on its next run.\n"
}
