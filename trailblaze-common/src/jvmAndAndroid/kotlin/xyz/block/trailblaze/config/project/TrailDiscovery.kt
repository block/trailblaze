package xyz.block.trailblaze.config.project

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import xyz.block.trailblaze.recordings.TrailRecordings
import xyz.block.trailblaze.util.Console

/**
 * Trail discovery service.
 *
 * Walks a workspace tree and returns every file Trailblaze considers a "trail":
 *  - any file ending in `.trail.yaml` (platform-specific recordings)
 *  - any file named exactly `blaze.yaml` (NL-only definitions)
 *  - any file named exactly `trailblaze.yaml` **except** the workspace-anchor config
 *    (legacy NL definitions in older workspaces — see [TrailRecordings] for the full
 *    set of names).
 *
 * The anchor rule: a `trailblaze.yaml` that sits at the *workspace root* (as
 * determined by [findWorkspaceRoot] walking up from the discovery root) is the
 * workspace config file and is never surfaced as a trail. A `trailblaze.yaml` at any
 * other location — including the discovery root when the walk-up determines the
 * caller passed a workspace subdir — is a legacy NL trail and is included, matching
 * the pre-Phase-3 behavior of [TrailRecordings.isTrailFile].
 *
 * ## Two call shapes
 *
 *  - [findFirstTrail] — walks the tree and returns the first match that satisfies the
 *    caller's predicate, terminating the walk immediately on match. Use for "find
 *    the trail with this name" lookups (MCP name resolution, CLI executor fallback)
 *    so an early match in a 10k-trail workspace stays cheap. Skips the per-walk
 *    summary log since this is a hot path.
 *  - [discoverTrails] — eagerly materializes every discovered trail as a [Path] list
 *    sorted by absolute path. Use this for "show me all trails" callers (UI browser,
 *    CLI batch run) that need deterministic ordering.
 *  - [discoverTrailFiles] — JVM convenience returning [List]`<File>` since every
 *    caller here unpacks to [File] immediately.
 *
 * ## Excludes
 *
 * [DEFAULT_EXCLUDED_DIRS] prunes directories whose contents are always ephemeral or
 * unrelated to trails (`build/`, `.gradle/`, `.git/`, `node_modules/`, `.trailblaze/`).
 * Matched by exact name at any depth. `.trailblaze/` is pruned globally because it is
 * Trailblaze's own state convention — users with a legitimate feature folder named
 * `.trailblaze/` are rare enough that Phase 3 accepts the tradeoff; Phase 8 will add
 * `.gitignore`-aware exclusion when the sample apps migrate.
 *
 * ## Symlinks
 *
 * The walk uses [Files.walkFileTree]'s default options, which **do not** follow
 * symlinks. Two consequences callers should be aware of:
 *
 *  - In-tree symlinks (a symlink inside the scan root pointing elsewhere) are reported
 *    as symbolic links rather than traversed. File-symlinks are rejected by the
 *    visitor's `attrs.isRegularFile` guard; directory-symlinks are not descended into.
 *    This is what defends against path-traversal — a `./flows/link.trail.yaml → /etc`
 *    never appears in results.
 *  - The scan root itself must be a real directory, not a symlink to one. Passing a
 *    symlinked directory produces an empty walk because the default options also refuse
 *    to descend through the starting symlink. Callers needing symlinked-root support
 *    should canonicalize via `toRealPath()` before invoking.
 *
 * `File.walkTopDown` (the pre-Phase-3 API) did follow symlinks, so users who symlinked
 * a shared trails directory into their workspace should be aware those links no longer
 * resolve. The tradeoff is intentional for Phase 3: following symlinks without cycle
 * detection is a DoS footgun, and the monorepo's sample apps + `uitests-*` modules do
 * not depend on symlinks. Revisit in Phase 8 alongside `.gitignore`.
 */
object TrailDiscovery {

  /**
   * Directory names that are always pruned during walk. Matched by exact directory name,
   * at any depth (so `project/foo/build/whatever` and `build/whatever` are both pruned).
   *
   * Keep the list tight and plan-authorized — adding "helpful" excludes silently hides
   * user trails. Phase 8 may upgrade this to a `.gitignore`-aware implementation.
   */
  val DEFAULT_EXCLUDED_DIRS: Set<String> = setOf(
    "build",
    ".gradle",
    ".git",
    "node_modules",
    ".trailblaze",
  )

  /**
   * Walks [root] and returns the first discovered trail file for which [predicate]
   * returns true, terminating the walk immediately on match.
   *
   * The predicate is evaluated on every trail-shaped file the walk encounters, in
   * filesystem-visit order (not sorted). Callers that need deterministic ordering
   * across runs — e.g. when multiple files could match and the choice is user-visible —
   * should use [discoverTrails] instead and pick the first match from the sorted list.
   *
   * Returns null when [root] is missing, not a directory, or when no trail matches.
   * Never throws, including under a restrictive [SecurityManager].
   */
  fun findFirstTrail(
    root: Path,
    extraExcludedDirs: Set<String> = emptySet(),
    predicate: (Path) -> Boolean,
  ): Path? {
    if (!isDirectorySafely(root)) return null
    // Walk the caller's original `root` (not the canonicalized form) so emitted paths
    // sit in the same path-frame as the arguments — callers that do `file.relativeTo(
    // callerRoot)` get a clean relative path instead of a walk through the symlink
    // collapse (e.g. `../../../private/var/...` on macOS).
    val anchor = resolveWorkspaceAnchor(root)
    val excludedDirs = DEFAULT_EXCLUDED_DIRS + extraExcludedDirs
    var match: Path? = null
    runDiscoveryWalk(root, anchor, excludedDirs, logSummary = false) { file ->
      if (predicate(file)) {
        match = file
        TraversalAction.Terminate
      } else {
        TraversalAction.Continue
      }
    }
    return match
  }

  /**
   * Recursively discovers trail files under [root]. Returns an empty list when [root]
   * is missing or not a directory — discovery is a read-only survey and must never
   * throw on a user who hasn't created a workspace yet, including under a restrictive
   * [SecurityManager].
   *
   * Results are sorted by absolute path (as a string) so downstream consumers that
   * key on path order (CLI batch runs, UI indexing) behave deterministically across
   * platforms. Use [findFirstTrail] if you only need the first match.
   */
  fun discoverTrails(
    root: Path,
    extraExcludedDirs: Set<String> = emptySet(),
  ): List<Path> {
    if (!isDirectorySafely(root)) return emptyList()
    // Walk the caller's original `root` so emitted paths stay in the caller's frame
    // (see the `findFirstTrail` rationale). Anchor resolution happens against the
    // canonicalized form inside `resolveWorkspaceAnchor`.
    val anchor = resolveWorkspaceAnchor(root)
    val excludedDirs = DEFAULT_EXCLUDED_DIRS + extraExcludedDirs
    val collected = mutableListOf<Path>()
    runDiscoveryWalk(root, anchor, excludedDirs, logSummary = true) { file ->
      collected.add(file)
      TraversalAction.Continue
    }
    return collected.sortedBy { it.toAbsolutePath().toString() }
  }

  /**
   * JVM convenience returning [List]`<File>` — every current caller immediately maps
   * back to [File] for `readText` / `canonicalPath` / `relativeTo` interop. The path
   * ordering is preserved from [discoverTrails].
   */
  fun discoverTrailFiles(
    root: Path,
    extraExcludedDirs: Set<String> = emptySet(),
  ): List<File> = discoverTrails(root, extraExcludedDirs).map { it.toFile() }

  /**
   * Returns true when [fileName] names a trail artifact Phase 3 considers discoverable.
   * Delegates to [TrailRecordings.isTrailFile] so the set of accepted names stays in
   * sync with recording-layer code (e.g. the UI scanner's variant grouping).
   *
   * This predicate does **not** know about the workspace-anchor rule — a
   * `trailblaze.yaml` that happens to be the workspace config is excluded inside the
   * walk, not by this filename-only predicate. Callers invoking this directly get a
   * filename-only check.
   */
  fun isTrailFile(fileName: String): Boolean = TrailRecordings.isTrailFile(fileName)

  /**
   * Signal returned by the per-file callback to the shared walk implementation.
   * [discoverTrails] always returns [Continue]; [findFirstTrail] returns [Terminate]
   * once its predicate matches.
   */
  private enum class TraversalAction { Continue, Terminate }

  /**
   * Shared walk implementation used by [findFirstTrail] and [discoverTrails]. Emits
   * every trail file under [walkRoot] that passes [isTrailFile] and the
   * workspace-anchor rule (a `trailblaze.yaml` matching [anchorFile] is excluded) via
   * [onTrail]. The walk terminates when [onTrail] returns [TraversalAction.Terminate].
   *
   * Failures are handled defensively: permission-denied entries increment a counter
   * and are reported once at end-of-walk; an [IOException] from the walk itself, plus
   * [SecurityException] and other [RuntimeException]s at the top level, log via
   * [Console.error] and return whatever was collected before the failure rather than
   * propagating out.
   *
   * When [logSummary] is true (i.e. [discoverTrails] and friends), a single-line
   * `found N[, skipped M]` message goes out at completion. [findFirstTrail] passes
   * false because a short-circuit's "found 1" line in a 10k-trail workspace is
   * misleading — the count reflects matches up to termination, not tree contents.
   */
  private fun runDiscoveryWalk(
    walkRoot: Path,
    anchorFile: Path?,
    excludedDirs: Set<String>,
    logSummary: Boolean,
    onTrail: (Path) -> TraversalAction,
  ) {
    var emitted = 0
    var skipped = 0
    try {
      Files.walkFileTree(
        walkRoot,
        object : SimpleFileVisitor<Path>() {
          // `preVisitDirectory` is called with the scan root as its very first entry;
          // flipping a sentinel lets us skip the canonical-compare syscall on every
          // subsequent directory (which can never be the root anyway).
          private var seenRoot = false

          override fun preVisitDirectory(
            dir: Path,
            attrs: BasicFileAttributes,
          ): FileVisitResult {
            if (!seenRoot) {
              seenRoot = true
              return FileVisitResult.CONTINUE
            }
            val name = dir.fileName?.toString() ?: return FileVisitResult.CONTINUE
            return if (name in excludedDirs) FileVisitResult.SKIP_SUBTREE
            else FileVisitResult.CONTINUE
          }

          override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            if (!attrs.isRegularFile) return FileVisitResult.CONTINUE
            val name = file.fileName?.toString() ?: return FileVisitResult.CONTINUE
            if (!isTrailFile(name) || isAnchor(file, name)) {
              return FileVisitResult.CONTINUE
            }
            emitted++
            return when (onTrail(file)) {
              TraversalAction.Continue -> FileVisitResult.CONTINUE
              TraversalAction.Terminate -> FileVisitResult.TERMINATE
            }
          }

          override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
            // Unreadable files/dirs (permissions, broken symlinks, race with delete)
            // must not abort discovery — the caller shouldn't lose every trail because
            // one dir was revoked mid-walk. Coalesce into a counter so we emit at most
            // one log per walk instead of one per unreadable entry.
            skipped++
            return FileVisitResult.CONTINUE
          }

          /**
           * Fast path: reject the obvious non-matches without a canonicalization
           * syscall. Only when the filename actually matches `trailblaze.yaml` do we
           * compare parent paths (and even then, object equality covers the common
           * case before the canonical fallback).
           */
          private fun isAnchor(file: Path, name: String): Boolean {
            if (anchorFile == null) return false
            if (name != TrailblazeProjectConfigLoader.CONFIG_FILENAME) return false
            val parent = file.parent ?: return false
            if (parent == anchorFile.parent && file.fileName == anchorFile.fileName) {
              return true
            }
            return file.toRealPathOrSelf() == anchorFile
          }
        },
      )
    } catch (e: IOException) {
      Console.error("Trail discovery aborted under $walkRoot: ${e.message}")
    } catch (e: SecurityException) {
      // A SecurityManager denying walkFileTree at the top level is the same
      // "best-effort" class as an IOException for our purposes — log the abort
      // at error level (always visible) and return what we gathered.
      Console.error("Trail discovery denied under $walkRoot: ${e.message}")
    } catch (e: RuntimeException) {
      // Defensive: JDK implementations occasionally wrap filesystem failures in
      // unchecked exceptions (UncheckedIOException, FileSystemException subclasses
      // that extend RuntimeException on some platforms). Same degradation path.
      Console.error("Trail discovery failed under $walkRoot: ${e.message}")
    }
    if (skipped > 0) {
      Console.error("Trail discovery under $walkRoot: skipped $skipped inaccessible paths")
    }
    if (logSummary) {
      Console.log("Trail discovery under $walkRoot: found $emitted")
    }
  }

  /**
   * Resolves the true workspace-anchor file (the `trailblaze.yaml` that marks the
   * workspace this discovery run belongs to) or null when the discovery root is in a
   * scratch workspace. Delegates to [findWorkspaceRoot]'s walk-up so semantics match
   * Phase 2's single primitive — callers scanning a subdir of a workspace don't get
   * their own subdir's `trailblaze.yaml` treated as the anchor.
   */
  private fun resolveWorkspaceAnchor(walkRoot: Path): Path? =
    when (val ws = findWorkspaceRoot(walkRoot)) {
      is WorkspaceRoot.Configured -> ws.configFile
      is WorkspaceRoot.Scratch -> null
    }

  /**
   * SecurityManager-safe wrapper around [Files.isDirectory]. The un-wrapped call can
   * throw [SecurityException] under a restrictive policy, which would defeat the
   * "never throws" contract on [findFirstTrail] / [discoverTrails].
   */
  private fun isDirectorySafely(path: Path): Boolean = try {
    Files.isDirectory(path)
  } catch (_: SecurityException) {
    false
  }

  /**
   * Best-effort canonicalization — matches the fallback behavior in [findWorkspaceRoot]
   * so equality comparisons stay consistent across callers even when `toRealPath`
   * fails (missing path, SecurityManager denial).
   *
   * This intentionally does **not** share code with [findWorkspaceRoot]'s
   * `toRealPathOrNormalized` helper: that one has to handle user-supplied paths that
   * may not exist yet (walks up to the closest existing ancestor and re-appends the
   * missing tail so a macOS `/var → /private/var` symlink still collapses through a
   * not-yet-created leaf). TrailDiscovery only reaches this helper for paths that
   * already exist — [isDirectorySafely] / [Files.walkFileTree] both confirm existence
   * first — so the simpler fallback is correct here. Merging the two would force the
   * discovery hot path to pay for a lookup it never needs.
   */
  private fun Path.toRealPathOrSelf(): Path = try {
    toRealPath()
  } catch (_: IOException) {
    toAbsolutePath().normalize()
  } catch (_: SecurityException) {
    toAbsolutePath().normalize()
  }
}
