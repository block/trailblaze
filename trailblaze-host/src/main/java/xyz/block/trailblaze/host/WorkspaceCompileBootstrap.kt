package xyz.block.trailblaze.host

import xyz.block.trailblaze.TrailblazeVersion
import xyz.block.trailblaze.compile.TrailblazeCompiler
import xyz.block.trailblaze.config.project.TrailblazeWorkspaceConfigResolver
import xyz.block.trailblaze.llm.config.TrailblazeConfigPaths
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.toLowerHex
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.file.Paths
import java.security.MessageDigest

/**
 * Lazy daemon-init rebundle of workspace pack manifests.
 *
 * The framework JAR ships its bundled `targets/<id>.yaml` pre-compiled at build time
 * via the `:trailblaze-models` build-logic generator, so a vanilla checkout has working
 * targets before `trailblaze compile` is ever run. Workspace-authored packs under
 * `trails/config/packs/<id>/pack.yaml` are NOT covered by that build-time pass, though,
 * so until this bootstrap ran the user had to manually invoke `trailblaze compile` after
 * each manifest edit. This is the same pain `cargo run` saves Rust users from.
 *
 * On daemon startup, [bootstrap] computes a hash of the workspace pack manifests plus
 * the running framework version and compares it against the hash stored from the last
 * successful compile. If they match, the existing `dist/targets/` is fresh and we skip;
 * if they don't (or the hash file is absent), [TrailblazeCompiler.compile] runs in-process
 * before [AppTargetDiscovery.discover] is called, so the discovery flow sees the freshly
 * materialized targets without any further wiring — the discovery layer already reads the
 * workspace's filesystem `ConfigResourceSource`.
 *
 * Compile errors abort daemon startup. Same UX bar as `cargo run` against unresolvable
 * deps: refuse to start, print the resolver errors, let the user fix the manifest.
 *
 * Out of scope here:
 * - Watching the workspace for live edits (full IDE-style hot reload).
 * - Sharing the bundle cache across workspaces — each workspace has its own `dist/`.
 *
 * Tracked: issue #2556.
 */
object WorkspaceCompileBootstrap {

  /**
   * Filename storing the hex SHA-256 of the last successful compile inputs.
   * Lives under `<config>/dist/` so it's colocated with the compile output and
   * gets cleaned up alongside it when a user blows away `dist/`.
   */
  internal const val HASH_FILENAME = ".bundle.hash"

  /**
   * Filename of the inter-process lock that serializes concurrent daemon-init
   * compiles in the same workspace. Grabbed via [FileLock] so the OS handles
   * cross-process coordination — required because two `trailblaze` invocations
   * starting at the same time would otherwise both compile and race on the
   * `dist/targets/` writes.
   */
  internal const val LOCK_FILENAME = ".bundle.lock"

  /**
   * Outcome of a [bootstrap] call. Returned for callers that want to log or assert on
   * the result; failure cases throw [WorkspaceCompileException] rather than being modeled
   * as a result variant, because the daemon's response to a compile error is to abort
   * startup, not to keep going with stale outputs.
   */
  sealed interface BootstrapResult {
    /** No workspace was discovered above CWD — nothing to compile. */
    data object NoWorkspace : BootstrapResult

    /**
     * Workspace exists but has no `packs/` directory or no pack manifests
     * inside it — nothing to compile. Distinguished from [NoWorkspace] only for
     * test legibility; downstream code treats both identically.
     */
    data object NoWorkspacePacks : BootstrapResult

    /** Stored hash matched and `dist/targets/` exists; compile was skipped. */
    data object UpToDate : BootstrapResult

    /** Compile ran and emitted [emitted] target file(s). */
    data class Recompiled(val emitted: Int) : BootstrapResult
  }

  /**
   * Thrown when [TrailblazeCompiler.compile] returns errors. The daemon converts this
   * into a fatal startup failure with the error list printed to stderr — the user fixes
   * their manifest and retries, same as `cargo run` against an unresolvable dep graph.
   */
  class WorkspaceCompileException(
    val errors: List<String>,
  ) : RuntimeException(buildErrorMessage(errors))

  /**
   * Discovers the workspace from CWD and rebundles workspace packs if their hash has
   * changed since the last successful compile. Safe to call from any process that's
   * about to initialize the daemon — the cost is one SHA-256 walk over `pack.yaml` files
   * when the bundle is fresh.
   *
   * @throws WorkspaceCompileException if the compile fails. Daemon startup should let
   *   this propagate so the user sees the resolver errors instead of a daemon that
   *   silently runs against stale (or missing) targets.
   */
  fun bootstrap(): BootstrapResult {
    val resolved = TrailblazeWorkspaceConfigResolver.resolve(Paths.get(""))
    val configDir = resolved.configDir ?: return BootstrapResult.NoWorkspace
    // Capture the load-time content hash before the compile dance below so it reflects
    // exactly the on-disk state the daemon is about to load — any edit that lands after
    // this line will diverge from the captured hash and trip the drift warning. Done at
    // every bootstrap call (NoWorkspacePacks/UpToDate/Recompiled all reach this) so a
    // packs-less workspace still gets drift coverage for its tool/toolset/provider YAMLs.
    xyz.block.trailblaze.config.project.WorkspaceContentHasher
      .captureForDaemon(configDir, TrailblazeVersion.version)
    return bootstrap(configDir = configDir, version = TrailblazeVersion.version)
  }

  /**
   * Convenience wrapper for daemon-startup entry points. Identical to [bootstrap], but
   * a [WorkspaceCompileException] prints to stderr and calls `exitProcess(1)` instead
   * of propagating — appropriate when there is no useful caller-level recovery path
   * and the alternative is an uncaught-exception stack trace that duplicates the
   * resolver error message we already printed cleanly.
   */
  fun bootstrapOrExit(): BootstrapResult = try {
    bootstrap()
  } catch (e: WorkspaceCompileException) {
    Console.error(e.message ?: "Workspace pack compilation failed.")
    kotlin.system.exitProcess(1)
  }

  /**
   * Visible for testing. Lets tests pass an explicit `trails/config/` directory and
   * version string so the hash-skip logic can be exercised without touching CWD or the
   * baked-in [TrailblazeVersion].
   */
  internal fun bootstrap(configDir: File, version: String): BootstrapResult {
    val packsDir = File(configDir, TrailblazeConfigPaths.PACKS_SUBDIR)
    if (!packsDir.isDirectory) return BootstrapResult.NoWorkspacePacks

    val packManifests = listPackManifests(packsDir)
    if (packManifests.isEmpty()) return BootstrapResult.NoWorkspacePacks

    val outputDir = File(configDir, TrailblazeConfigPaths.WORKSPACE_DIST_TARGETS_SUBPATH)
    val distDir = File(configDir, TrailblazeConfigPaths.WORKSPACE_DIST_SUBDIR)
    val hashFile = File(distDir, HASH_FILENAME)

    val expectedHash = computeWorkspaceHash(packManifests, version)
    val expectedTargetIds = packManifests.map { it.id }

    // Cross-process serialization. Two daemons starting at once in the same workspace
    // would both observe a stale hash, both call TrailblazeCompiler.compile() against
    // the same outputDir, and both writeText() the hash file — last-writer-wins on the
    // hash but the per-target YAML writes can interleave. FileLock makes the second
    // process wait for the first to finish; by the time it acquires the lock the bundle
    // is fresh and the hash check below short-circuits.
    return withDistLock(distDir) {
      val storedHash = readStoredHash(hashFile)
      if (storedHash == expectedHash && allTargetsPresent(outputDir, expectedTargetIds)) {
        return@withDistLock BootstrapResult.UpToDate
      }

      // Console.info (not Console.log) so users running with --quiet still see the
      // multi-second startup pause attributed to a recompile. CompileCommand uses
      // Console.log for explicit `trailblaze compile` invocations, but those are
      // already a foregrounded operation the user opted into; the daemon-init
      // rebundle is implicit and hiding it in quiet mode would look like a hang.
      Console.info("Recompiling workspace packs...")
      val result = TrailblazeCompiler.compile(packsDir = packsDir, outputDir = outputDir)
      if (!result.isSuccess) {
        // Drop a stale hash file so a subsequent edit-and-retry doesn't accidentally pass
        // the up-to-date check against last run's hash. The user expects "fix and retry"
        // semantics, not "succeed because nothing changed since the last failure".
        hashFile.delete()
        throw WorkspaceCompileException(result.errors)
      }
      writeHash(hashFile, expectedHash)
      BootstrapResult.Recompiled(emitted = result.emittedTargets.size)
    }
  }

  /**
   * Hashes every `<id>/pack.yaml` referenced by [manifests], plus [version], in
   * deterministic order. Including the version covers the "framework upgrade ⇒ stale
   * compile output" case: framework-bundled packs that the workspace transitively
   * depends on can change shape across releases, so a bundle compiled against v1.2 is
   * not necessarily valid against v1.3. Tying the hash to the running CLI version
   * forces a recompile across upgrades regardless of whether any workspace `pack.yaml`
   * was edited.
   *
   * Pack files outside `<id>/pack.yaml` (like authored tool YAMLs under `tools/` or
   * inline scripts) are intentionally not hashed — they're consumed by
   * [TrailblazeCompiler] only via the `pack.yaml` ref, so a manifest edit is the
   * canonical signal that the bundle needs a refresh. If we ever start materializing
   * tool YAMLs at compile time, expand the hash here in lockstep.
   *
   * Manifest content is normalized to LF before hashing so a workspace with CRLF
   * line endings (Windows checkout, mixed-EOL editor) doesn't produce a different
   * hash than the same workspace on macOS / Linux. Without this, switching machines
   * would force a spurious recompile.
   *
   * Visible for testing.
   */
  internal fun computeWorkspaceHash(manifests: List<PackManifest>, version: String): String {
    val md = MessageDigest.getInstance("SHA-256")
    md.update(version.toByteArray(Charsets.UTF_8))
    md.update(SEPARATOR)

    for (manifest in manifests) {
      md.update(manifest.id.toByteArray(Charsets.UTF_8))
      md.update(SEPARATOR)
      val normalized = try {
        manifest.file.readText(Charsets.UTF_8).replace("\r\n", "\n")
      } catch (e: IOException) {
        throw WorkspaceCompileException(
          listOf("Cannot read pack manifest at ${manifest.file.absolutePath}: ${e.message}"),
        )
      }
      md.update(normalized.toByteArray(Charsets.UTF_8))
      md.update(SEPARATOR)
    }
    return md.digest().toLowerHex()
  }

  /**
   * Test convenience overload. Walks [packsDir] for `pack.yaml` files and forwards
   * to the manifest-list overload above. Production callers use the manifest list
   * directly so the listing happens once per [bootstrap] invocation.
   */
  internal fun computeWorkspaceHash(packsDir: File, version: String): String =
    computeWorkspaceHash(listPackManifests(packsDir), version)

  /**
   * A workspace pack manifest discovered under `packs/`. Carries both the pack id
   * (the directory name) and the manifest file so the hash and the per-target
   * existence check can both reference the same source of truth without relisting
   * the directory.
   */
  internal data class PackManifest(val id: String, val file: File)

  private fun listPackManifests(packsDir: File): List<PackManifest> =
    packsDir.listFiles { f -> f.isDirectory }
      ?.sortedBy { it.name }
      ?.mapNotNull { dir ->
        val manifest = File(dir, TrailblazeConfigPaths.PACK_MANIFEST_FILENAME)
        if (manifest.isFile) PackManifest(id = dir.name, file = manifest) else null
      }
      .orEmpty()

  /**
   * The hash-skip path also has to verify the materialized output is intact — the user
   * could have manually deleted a single `dist/targets/<id>.yaml` while leaving the
   * directory and `.bundle.hash` in place, in which case the bundle is no longer fresh
   * even though the input hash matches. We don't try to distinguish "user-deleted the
   * file" from "compile never produced it"; either way the right answer is recompile.
   *
   * Note: [TrailblazeCompiler] only writes a YAML file for *app* packs (those with a
   * `target:` block). Library packs contribute defaults but produce no output. We can't
   * tell the two apart cheaply here — and adding the parse step would defeat the
   * point of the hash-skip — so we err on the side of running compile when ANY expected
   * id is missing. The compile itself is fast on an unchanged input set.
   */
  private fun allTargetsPresent(outputDir: File, expectedIds: List<String>): Boolean {
    if (!outputDir.isDirectory) return false
    return expectedIds.all { id -> File(outputDir, "$id.yaml").isFile }
  }

  private fun readStoredHash(hashFile: File): String? = try {
    if (hashFile.isFile) hashFile.readText().trim().ifEmpty { null } else null
  } catch (_: Exception) {
    null
  }

  private fun writeHash(hashFile: File, hash: String) {
    hashFile.parentFile?.mkdirs()
    hashFile.writeText(hash)
  }

  /**
   * Acquires an exclusive [FileLock] on `<distDir>/.bundle.lock` for the duration of
   * [block], creating the lock file (and its parent dir) if needed. The lock is held
   * across `compile()` + `writeHash()` so a second process waits until the first has
   * finished writing both before re-evaluating freshness.
   */
  private inline fun <T> withDistLock(distDir: File, block: () -> T): T {
    distDir.mkdirs()
    val lockFile = File(distDir, LOCK_FILENAME)
    RandomAccessFile(lockFile, "rw").use { raf ->
      raf.channel.use { channel ->
        val lock: FileLock = channel.lock()
        try {
          return block()
        } finally {
          if (lock.isValid) lock.release()
        }
      }
    }
  }

  /** Field separator (NUL) for hashing — disambiguates concatenated entries. */
  private val SEPARATOR = byteArrayOf(0)

  private fun buildErrorMessage(errors: List<String>): String =
    if (errors.isEmpty()) {
      "Workspace pack compilation failed."
    } else {
      buildString {
        append("Workspace pack compilation failed:")
        for (error in errors) {
          append('\n')
          append("  - ")
          append(error)
        }
      }
    }
}
