package xyz.block.trailblaze.cli

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.block.trailblaze.ui.TrailblazeDesktopUtil

/**
 * Per-terminal device-pin storage.
 *
 * **What this is.** A small JSON file mapping `(shellPid → boundDevice)` so the
 * CLI resolver can answer "what device should this terminal default to?"
 * without making the user export a shell env var. The file is read on every
 * device-resolving command and consulted as a resolver tier between the
 * `TRAILBLAZE_DEVICE` env var and connected-device autodetect — see
 * [resolveDeviceWithAutodetect].
 *
 * **Why a file and not the daemon.** The pin must survive `trailblaze app --stop
 * && trailblaze app start` so a user who restarts their daemon doesn't lose
 * their terminal binding. Keeping it in a file gives us that for free; the
 * daemon stays stateless on this axis. The cost is concurrency control on the
 * file, addressed below.
 *
 * **PID reuse.** Shell PIDs are eventually recycled by the OS. The window for
 * a stale pin to bite is "user closes terminal A AND opens terminal B that
 * gets terminal A's recycled PID AND never runs `device connect` themselves
 * AND runs a device command in the same project," and the worst-case impact
 * is "terminal B inherits an unexpected device default." That's a one-in-a-
 * million sequence, the recovery is `trailblaze device connect <whatever>`,
 * and detecting it requires a start-instant comparison on every read — the
 * cost of the prevention outweighs the cost of the bug. So we don't try.
 * Liveness checking (PID exists at all) IS done — that's what cleans out
 * dead shells; it just doesn't fingerprint the live process.
 *
 * **Garbage collection.** Lazy. Every mutation drops entries whose PID is no
 * longer alive. No background sweep — at worst the file accumulates dead
 * entries until something writes it, at which point they're evicted in
 * O(N). At typical scale (handful of shells) this is negligible.
 *
 * **Concurrency.** Two terminals can call `trailblaze device connect` in the
 * same instant. We use kernel-level advisory file locking
 * ([java.nio.channels.FileChannel.lock]) for read-modify-write: acquire
 * exclusive lock → read → mutate → write to tempfile → fsync → rename →
 * release. Locks are kept short (single-digit-KB file) so contention is
 * effectively zero in practice. An in-JVM monitor on top of the file lock
 * guards thread-level concurrency within a single daemon (Java's
 * `FileChannel.lock` is process-scoped, not thread-scoped).
 *
 * **Multi-daemon isolation.** The file path is port-scoped
 * ([pinFileFor]) — a user running `TRAILBLAZE_PORT=52527 trailblaze app start`
 * for an isolated test daemon gets its own pin map. Different daemons
 * typically have different device lists, so cross-daemon pin sharing would
 * point at non-existent device ids.
 *
 * **Test hooks.** Every method takes the file path explicitly, and the
 * liveness check is injectable via [LivenessProbe]. Production calls
 * default to [ProcessHandle.of]; tests pass a deterministic lambda so the
 * store's eviction logic can be unit-tested without spawning real processes.
 */
internal object ShellDevicePinStore {

  /** Lookup result for "what's pinned for shell pid X?". */
  sealed class PinLookup {
    /**
     * Entry present and live; safe to use. Carries the full [Entry] so callers
     * can pull either [Entry.device] or [Entry.target] without a second file
     * read. The device-only callers ([resolveDeviceWithAutodetect],
     * [DeviceDisconnectCommand]) read `.entry.device`; the target tier in
     * [resolveCliTargetPin] reads `.entry.target`.
     */
    data class Found(val entry: Entry) : PinLookup() {
      val device: String get() = entry.device
      val target: String? get() = entry.target
    }
    /** Entry absent or PID dead (shell exited). */
    data object NotFound : PinLookup()
  }

  /**
   * Predicate: is this PID currently bound to a running process?
   *
   * Production is [::isPidAlive] (delegates to [ProcessHandle.of]). Tests
   * pass a deterministic lambda so the store's eviction logic can be
   * unit-tested without spawning real processes.
   */
  fun interface LivenessProbe {
    operator fun invoke(pid: Long): Boolean
  }

  /**
   * One terminal's pin. [device] is the bound device id (e.g. `android/emulator-5554`).
   * [target] is the bound target app (e.g. `default`, `sampleapp`) when the user passed
   * `--target X` to `device connect` or `device rebind`, otherwise null. Nullable
   * because some users `device connect` bare and let workspace config / built-in
   * default supply the target on each action call.
   *
   * Persisting [target] alongside [device] is what makes the connect-time target
   * survive a daemon restart: every subsequent CLI invocation reads this entry,
   * re-applies the target as a per-device override on the daemon, and the user's
   * connect-time intent stays in force even after `trailblaze app --stop &&
   * trailblaze app start`. Without this field, the target would live only in the
   * daemon's in-memory `SessionTargetRegistry` and quietly degrade to workspace
   * config on daemon restart — see PR #3611 review feedback.
   */
  @Serializable
  internal data class Entry(val device: String, val target: String? = null)

  @Serializable
  internal data class PinFile(val shells: Map<String, Entry> = emptyMap())

  private val json = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
    encodeDefaults = true
    ignoreUnknownKeys = true
  }

  /**
   * Per-file in-JVM lock — `FileChannel.lock()` is **process-scoped** in Java
   * (overlapping locks on the same channel throw [java.nio.channels.OverlappingFileLockException]),
   * so without an in-JVM monitor two threads in the same daemon racing on
   * `mutate` would either lose a write or crash. The file lock still guards
   * cross-JVM concurrency (two `./trailblaze` processes writing simultaneously);
   * this monitor guards thread-level concurrency within a single JVM.
   *
   * Keyed by canonical path so different files are independent and tests
   * using TemporaryFolder don't contend on a global lock.
   */
  private val jvmLocks: MutableMap<String, Any> = java.util.concurrent.ConcurrentHashMap()

  private fun jvmLockFor(file: File): Any =
    jvmLocks.computeIfAbsent(file.canonicalPath) { Any() }

  /**
   * Default location for the pin file, scoped to the daemon port so isolated
   * daemons don't collide. Falls under `~/.trailblaze/` alongside the rest of
   * the per-user state.
   */
  fun pinFileFor(port: Int): File =
    File(TrailblazeDesktopUtil.getDefaultAppDataDirectory(), "shell-device-pins-$port.json")

  /**
   * Look up the device pinned for [shellPid], evicting the entry inline if
   * the PID is no longer alive. Idempotent — repeat calls return the same
   * answer without writing the file unless eviction was triggered.
   *
   * Safe to call when [file] doesn't exist (returns [PinLookup.NotFound]
   * without creating it) — that's the cold-start case for a user who's
   * never run `device connect`.
   */
  fun resolvePin(
    file: File,
    shellPid: Long,
    probe: LivenessProbe = LivenessProbe(::isPidAlive),
  ): PinLookup {
    if (!file.exists()) return PinLookup.NotFound
    val map = readMapOrEmpty(file)
    val entry = map.shells[shellPid.toString()] ?: return PinLookup.NotFound
    return if (probe(shellPid)) {
      PinLookup.Found(entry)
    } else {
      // PID dead — evict and report not-found. The eviction write is
      // best-effort; a failure here doesn't affect correctness (the next
      // read will retry the eviction).
      runCatching { mutate(file, probe) { it - shellPid.toString() } }
      PinLookup.NotFound
    }
  }

  /**
   * Set [shellPid] → ([device], [target]) in the pin file. Replaces any prior
   * entry for the same PID (a user re-running `device connect` to switch
   * devices, or `device rebind --target` to swap the bound target).
   *
   * [target] is null when the user pinned a device without `--target`; null is
   * preserved on read so action commands fall through to env / workspace
   * config / built-in default rather than re-applying a stale value.
   *
   * Also opportunistically evicts dead entries from the file as part of the
   * same read-modify-write — no separate GC pass needed.
   */
  fun setPin(
    file: File,
    shellPid: Long,
    device: String,
    target: String? = null,
    probe: LivenessProbe = LivenessProbe(::isPidAlive),
  ) {
    mutate(file, probe) { current ->
      current + (shellPid.toString() to Entry(device = device, target = target))
    }
  }

  /**
   * Remove the entry for [shellPid] if present. No-op when absent. Also
   * sweeps dead entries during the same read-modify-write.
   */
  fun clearPin(
    file: File,
    shellPid: Long,
    probe: LivenessProbe = LivenessProbe(::isPidAlive),
  ) {
    mutate(file, probe) { current -> current - shellPid.toString() }
  }

  /**
   * Atomically null out the `target` field on this terminal's pin while
   * leaving the device binding alone. Used by `--target=clear` to wipe the
   * connect-time target without disconnecting.
   *
   * Single `mutate()` call — read AND write happen inside one lock window,
   * so a concurrent writer to the same PID can't slip a write in between a
   * naïve "resolvePin then setPin" sequence. (Same-PID concurrency is rare
   * — requires backgrounded `./trailblaze &` invocations from one shell —
   * but the atomic variant costs the same as the split version and removes
   * the race outright.)
   *
   * No-op when there's no existing entry for [shellPid], when the entry's
   * target is already null, or when the entry's PID is dead (in which case
   * the entry is also evicted as part of the standard mutate-time GC).
   */
  fun clearPinTarget(
    file: File,
    shellPid: Long,
    probe: LivenessProbe = LivenessProbe(::isPidAlive),
  ) {
    mutate(file, probe) { current ->
      val existing = current[shellPid.toString()] ?: return@mutate current
      if (existing.target == null) return@mutate current
      current + (shellPid.toString() to existing.copy(target = null))
    }
  }

  /**
   * Read-only snapshot of the file's entries. Does NOT evict dead entries
   * (read-only). Used by diagnostics / `trailblaze device list-pins` kind of
   * surfaces; the resolver uses [resolvePin] instead so it stays self-
   * cleaning.
   */
  fun readAll(file: File): Map<Long, Entry> {
    if (!file.exists()) return emptyMap()
    val map = readMapOrEmpty(file)
    return map.shells.mapNotNull { (k, v) -> k.toLongOrNull()?.let { it to v } }.toMap()
  }

  // ---------------- internals ----------------

  /**
   * Read-modify-write under exclusive file lock, with opportunistic eviction
   * of dead entries. [transform] receives the current map and returns the
   * post-transform map. Dead-entry pruning is applied to the result so any
   * mutation also cleans up.
   *
   * The lock is held only across read + write of the JSON body (microseconds
   * for KB-scale files). On JVM exit mid-write, the OS releases the lock and
   * the rename below ensures readers never see a partial file (worst case
   * they see the pre-write contents).
   */
  private fun mutate(
    file: File,
    probe: LivenessProbe,
    transform: (Map<String, Entry>) -> Map<String, Entry>,
  ) {
    file.parentFile?.mkdirs()
    // Two-layer lock: synchronize in-JVM threads on `jvmLockFor(file)` so
    // racing threads serialize their RMW; inside the monitor, also take the
    // OS-level file lock so racing JVMs (two `trailblaze` invocations) don't
    // step on each other. Single-layer file-lock is insufficient because
    // `FileChannel.lock()` is process-scoped — two threads in the same JVM
    // either get an OverlappingFileLockException or silently bypass the
    // lock. See [jvmLocks] for the keyed-monitor rationale.
    // The cross-process lock lives on a SIBLING `.lock` file, never on the
    // data file itself. The atomic-rename below replaces the data-file
    // inode, so if we locked the data file directly, a second JVM that
    // opened the path between our acquire and our rename would block on
    // the now-orphaned inode, then read stale contents from its
    // still-open handle and overwrite our update. Locking a stable lock
    // path that nothing renames keeps both writers serialized on the same
    // inode for the duration of their RMW. The lock file is touched once
    // and never moved or rewritten — opening it for "rw" just creates it
    // if missing.
    val lockFile = File(file.parentFile, "${file.name}.lock")
    // Defensive check: if the lock path exists but isn't a regular file (e.g.
    // a user `mkdir`d it by accident, or filesystem weirdness left a directory
    // at the path), `RandomAccessFile` will throw a `FileNotFoundException`
    // with a misleading message ("Is a directory"). Surface a clear error
    // instead so the user knows what to fix.
    if (lockFile.exists() && !lockFile.isFile) {
      throw IllegalStateException(
        "Pin lock path $lockFile is not a regular file (got: ${if (lockFile.isDirectory) "directory" else "other"}). " +
          "Remove it and retry.",
      )
    }
    synchronized(jvmLockFor(file)) {
      RandomAccessFile(lockFile, "rw").use { lockRaf ->
        lockRaf.channel.lock().use {
          val current = if (file.exists() && file.length() > 0) {
            runCatching {
              json.decodeFromString(PinFile.serializer(), file.readText()).shells
            }.getOrDefault(emptyMap())
          } else {
            emptyMap()
          }
          val transformed = transform(current)
          // Keep entries whose PID is still alive. Entries with non-numeric
          // keys (corrupted file from a manual edit) are dropped — they
          // can't refer to a real OS process anyway.
          val gced = transformed.filter { (pidStr, _) ->
            val pid = pidStr.toLongOrNull() ?: return@filter false
            probe(pid)
          }
          val payload = json.encodeToString(PinFile.serializer(), PinFile(shells = gced))
          // Write to a temp sibling and atomically rename into place. The
          // sibling is created next to the data file so the rename is on
          // the same filesystem (a cross-device rename would fall back to
          // copy+delete, losing atomicity).
          val tmp = File(file.parentFile, "${file.name}.tmp")
          tmp.writeText(payload)
          Files.move(
            tmp.toPath(),
            file.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
          )
        }
      }
    }
  }

  private fun readMapOrEmpty(file: File): PinFile =
    runCatching { json.decodeFromString(PinFile.serializer(), file.readText()) }
      .getOrDefault(PinFile())

  /**
   * Default production liveness check. Returns `true` when [pid] is bound to
   * a currently-running process. `ProcessHandle.of` returns
   * `Optional.empty()` for non-existent PIDs on Unix; the additional
   * `isAlive` check covers zombie/exiting states where the PID still
   * resolves but the process is gone.
   */
  private fun isPidAlive(pid: Long): Boolean =
    ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
}
