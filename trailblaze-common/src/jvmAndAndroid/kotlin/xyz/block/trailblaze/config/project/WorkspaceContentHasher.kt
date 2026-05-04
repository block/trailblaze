package xyz.block.trailblaze.config.project

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.toLowerHex

/**
 * SHA-256 of every relevant file under a `trails/config/` workspace, used by the daemon's
 * status endpoint to surface "your workspace files have been edited since the daemon
 * started" drift to the CLI.
 *
 * Distinct from [WorkspaceCompileBootstrap]'s `.bundle.hash`, which only covers
 * `<id>/pack.yaml` files because that hash decides whether the compile-cache (`dist/`)
 * needs invalidation. Tool YAMLs, scripted JS/TS, and trailblaze.yaml itself can change
 * the daemon's runtime view without invalidating the bundle, so they're missed by the
 * bundle hash but caught here.
 *
 * Captured exactly once per daemon JVM lifetime by the bootstrap (see
 * [WorkspaceCompileBootstrap.bootstrap]) and held in [lastCapturedHash]. The CLI
 * recomputes the same hash for the cwd-resolved workspace at every invocation and
 * compares — divergence means the daemon is serving from a snapshot the on-disk files
 * have moved past.
 *
 * ## What's hashed
 *
 * Walks `<configDir>/` recursively. For each file, hashes its forward-slash relative
 * path then its raw bytes. Excludes:
 *  - Directories named `dist` (daemon's own compile output) or `node_modules`
 *    (NPM artifact tree, can be huge and irrelevant).
 *  - Any file or directory whose name starts with `.` (catches `.bundle.hash`,
 *    `.session.hash`, `.git`, `.trailblaze` IDE stubs, etc.).
 *  - Symlinks: not followed, to avoid loops if a user has a symlink pointing back into
 *    the workspace.
 *
 * Files are visited in deterministic order (Kotlin's `walkTopDown` plus a sort on
 * relative path) so the hash is stable across machines / JDK versions.
 *
 * ## Memory profile
 *
 * Files are streamed in fixed-size chunks rather than loaded whole into memory, so a
 * workspace with a multi-MB binary under `tools/` (a recording, a lockfile, a screenshot)
 * doesn't allocate a proportional buffer per CLI invocation. The walk runs on the hot
 * path of every CLI command via `warnIfWorkspaceMismatch` so this matters.
 *
 * ## Version coupling
 *
 * The framework version is mixed into the hash so a CLI/daemon upgrade always invalidates
 * the comparison — different framework versions can interpret the same files differently,
 * so even a content-identical workspace should warn after an upgrade. (In practice the
 * existing version-mismatch check restarts the daemon before the drift check runs, so
 * this is belt-and-suspenders.)
 *
 * ## Concurrency / lifecycle
 *
 * [lastCapturedHash] is module-level mutable state on a singleton object. The contract is
 * **one daemon JVM, one bootstrap call, one captured value**. Specifically:
 *  - Production: the daemon JVM calls [captureForDaemon] exactly once during
 *    [WorkspaceCompileBootstrap.bootstrap] before any session traffic is served. The
 *    `@Volatile` ensures HTTP-handler threads see the value.
 *  - Tests: a JUnit test class that boots two daemons in the same JVM (rare) would
 *    overwrite the prior value. Tests that depend on a specific captured hash should
 *    either pin a single JVM scope or call [compute] directly without going through
 *    the captured-singleton path.
 *  - Future shared classloaders / multi-tenant hosting: would need a constructor-injected
 *    factory keyed by session/workspace. Not in scope today; flagged here so the next
 *    person reaching for "two daemons in one process" sees the constraint.
 */
object WorkspaceContentHasher {

  /**
   * The hash captured at this JVM's daemon startup, or `null` if the daemon hasn't
   * called [captureForDaemon] yet (no workspace, scratch mode, or non-daemon process
   * such as a one-shot CLI). See the class kdoc's "Concurrency / lifecycle" section
   * for the single-daemon-per-classloader assumption.
   */
  @Volatile
  var lastCapturedHash: String? = null
    private set

  /**
   * Capture [compute]'s result and stash it in [lastCapturedHash]. Called once per
   * daemon JVM by [WorkspaceCompileBootstrap.bootstrap] after the workspace's
   * configDir is resolved, before any session traffic is served. Logs the resulting
   * hash, file count, and elapsed time so users can attribute startup time to this
   * walk if it's slow on a large workspace.
   */
  fun captureForDaemon(configDir: File, version: String) {
    val started = System.currentTimeMillis()
    val result = computeWithStats(configDir, version)
    lastCapturedHash = result.hash
    Console.log(
      "Workspace hash captured: ${result.hash.take(12)}… " +
        "(${result.fileCount} files, ${System.currentTimeMillis() - started}ms)",
    )
  }

  /**
   * Hex SHA-256 over [version] plus every non-excluded file's relative path + content
   * under [configDir]. Returns a stable digest regardless of OS path separator or
   * filesystem walk order. See the class kdoc for what's excluded.
   *
   * File contents are streamed through the digest in [STREAM_CHUNK_BYTES]-sized chunks
   * rather than read in one shot, so a workspace with a large binary under `tools/`
   * doesn't allocate a proportional buffer per CLI invocation.
   *
   * Unreadable files (permissions, transient I/O errors) are skipped rather than
   * propagated — drift detection is best-effort and shouldn't take the daemon down.
   */
  fun compute(configDir: File, version: String): String =
    computeWithStats(configDir, version).hash

  private fun computeWithStats(configDir: File, version: String): HashResult {
    val md = MessageDigest.getInstance("SHA-256")
    md.update(version.toByteArray(Charsets.UTF_8))
    md.update(SEPARATOR)

    if (!configDir.isDirectory) return HashResult(md.digest().toLowerHex(), fileCount = 0)

    val files = configDir.walkTopDown()
      .onEnter { dir ->
        // Don't descend into excluded dirs. `onEnter` returning false prunes the
        // entire subtree, which is critical for `node_modules` (could be GBs).
        // The configDir itself passes through because its parent owns the name check.
        if (dir == configDir) return@onEnter true
        val name = dir.name
        !name.startsWith(".") && name != "dist" && name != "node_modules"
      }
      .filter { file ->
        file.isFile &&
          !file.name.startsWith(".") &&
          // walkTopDown() follows symlinks by default; skip them to prevent loops
          // and keep hashes deterministic across clones with different symlink layouts.
          !java.nio.file.Files.isSymbolicLink(file.toPath())
      }
      .map { file ->
        val rel = file.relativeTo(configDir).path.replace(File.separatorChar, '/')
        rel to file
      }
      .sortedBy { it.first }
      .toList()

    var hashedCount = 0
    val buffer = ByteArray(STREAM_CHUNK_BYTES)
    for ((rel, file) in files) {
      md.update(rel.toByteArray(Charsets.UTF_8))
      md.update(SEPARATOR)
      val streamed = try {
        file.inputStream().use { stream ->
          while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            md.update(buffer, 0, read)
          }
        }
        true
      } catch (_: IOException) {
        // Best-effort: skip the file content but keep the path-component already mixed
        // in — the next-time hash for the same file will diverge once it becomes
        // readable, which is the right signal.
        false
      }
      md.update(SEPARATOR)
      if (streamed) hashedCount++
    }
    return HashResult(md.digest().toLowerHex(), fileCount = hashedCount)
  }

  private data class HashResult(val hash: String, val fileCount: Int)

  private val SEPARATOR = byteArrayOf(0)

  /**
   * Read buffer size for streaming file content into the digest. 8 KB matches the JVM's
   * default `BufferedInputStream` size and is a common sweet spot — small enough to keep
   * memory bounded for huge files, large enough to amortize syscall overhead.
   */
  private const val STREAM_CHUNK_BYTES = 8 * 1024
}
