package xyz.block.trailblaze.host.capture

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import xyz.block.trailblaze.capture.CaptureOptions
import xyz.block.trailblaze.capture.CaptureSession
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.util.Console

/**
 * Per-`SessionId` ownership for video/log capture across **every** path that creates a
 * Trailblaze session — `./trailblaze trail` from the CLI, the desktop UI's "Run trail"
 * button, and (most importantly for this class's existence) the MCP per-tool path
 * where the `blaze`/`ask`/`verify`/individual-tool dispatchers each open a Trailblaze
 * session without going through `DesktopYamlRunner`. Prior to this coordinator,
 * `DesktopYamlRunner` was the only place that started a `CaptureSession`, so MCP-driven
 * Android runs landed with no `video.mp4` and no `video_sprites.webp` — the report
 * timeline showed an empty scrubber.
 *
 * The coordinator does three things:
 *  1. Maintains a `SessionId -> CaptureSession` registry so a single session can have
 *     exactly one capture stream regardless of how many places try to start one.
 *  2. Owns the lifecycle: [startForSession] is idempotent and writes directly into the
 *     session's log dir (no temp-dir + move dance — the dir already exists by the time
 *     the session id is known), and [stopForSession] runs `stopAll()` and writes a
 *     `capture_debug.txt` next to the artifacts for diagnostics.
 *  3. Stays out of the way for platforms where capture isn't wired (today: iOS, which is
 *     disabled in [CaptureSession.fromOptions]; Compose/desktop, which has no branch).
 *     [startForSession] short-circuits with `false` so callers can treat it as
 *     fire-and-forget.
 *
 * ### Concurrency
 *
 * The coordinator uses a **reserve-then-start** pattern under a single `lock` so two
 * concurrent callers for the same `sessionId` can't both spawn a `screenrecord` /
 * `xcrun` subprocess:
 *
 *  1. Under `lock`, atomically check the map and insert an `ActiveCapture(started=false)`
 *     placeholder. If the map already had an entry, the second caller bails out.
 *  2. Release `lock` and call `captureSession.startAll(...)` — this can be slow (adb
 *     `wm size`, ffmpeg spawn, xcrun handshake) so holding `lock` across it would
 *     serialize the daemon's entire session-management surface.
 *  3. Re-acquire `lock` and flip `started = true`. If a concurrent [stopForSession]
 *     removed the placeholder in the meantime (`active[sessionId] !== reservation`),
 *     the caller treats the start as cancelled and runs a best-effort `stopAll` on the
 *     partially-started capture to avoid leaking subprocesses.
 *
 * `stopForSession` removes under `lock`, then checks `started`: a `started=false` entry
 * means a start is mid-flight and will clean itself up via the post-start check; a
 * `started=true` entry runs the normal `stopAll` + `capture_debug.txt` write.
 */
class SessionCaptureCoordinator(
  private val logsRepo: LogsRepo,
  /**
   * Test seam — swap the real `CaptureSession.fromOptions` for a factory that returns
   * fake `CaptureSession` instances so unit tests can drive idempotency / race /
   * exception paths without spawning real `screenrecord`/`xcrun` subprocesses.
   * Production code uses the default.
   */
  private val captureSessionFactory: (CaptureOptions, TrailblazeDevicePlatform) -> CaptureSession? =
    { options, platform -> CaptureSession.fromOptions(options, platform) },
) {

  private class ActiveCapture(
    val session: CaptureSession,
    val sessionDir: File,
    val deviceId: String,
    val platform: TrailblazeDevicePlatform,
    var started: Boolean = false,
  )

  private val lock = Any()
  private val active = mutableMapOf<SessionId, ActiveCapture>()

  /**
   * Session ids whose `stopAll()` threw — we removed the [active] entry but may have
   * leaked an underlying `screenrecord`/`xcrun` subprocess we couldn't kill. Refuse to
   * re-reserve these ids for the rest of the daemon's lifetime so a future caller can't
   * accidentally race the leaked process for the same session log dir.
   *
   * **Defense-in-depth:** under the current `CaptureSession.stopAll` contract (see
   * `CaptureSession.kt:40-42`) the per-stream exceptions are swallowed and the method
   * never propagates. So today this set stays empty and the tombstone refusal in
   * [startForSession] never fires. The wiring is here so that the day `CaptureSession`
   * is changed to surface failures (the more honest design — a coordinator can't react
   * to a failed capture if it never hears about it), the safety net is already in
   * place. Sticky on purpose: we don't know what cleanup state the subprocess is in.
   */
  private val tombstoned = mutableSetOf<SessionId>()

  /**
   * Starts capture for [sessionId]. Idempotent for the same session id and safe under
   * concurrent calls — see the class kdoc for the reserve-then-start protocol.
   *
   * @param options Capture toggles for this session. Caller is responsible for
   *   resolving CLI/UI overrides (`--no-capture-video`, `--capture-logcat`, the
   *   "Capture Network Traffic" desktop toggle, …) before calling. The coordinator
   *   does NOT apply its own defaults — passing a misconfigured `CaptureOptions`
   *   silently records the wrong artifacts, so the caller's resolution must be
   *   authoritative.
   * @return `true` when this call reserved the slot and started a fresh capture;
   *   `false` when (a) the slot was already taken by another caller, (b) the platform
   *   has no capture stream wired today (WEB / iOS / Compose), or (c) `startAll` threw
   *   and the slot was cleaned up.
   */
  fun startForSession(
    sessionId: SessionId,
    deviceId: String,
    platform: TrailblazeDevicePlatform,
    options: CaptureOptions,
    appId: String? = null,
  ): Boolean {
    // WEB is intentionally skipped here: `BasePlaywrightNativeTest` self-instruments
    // its capture inside `runTrailblazeYamlSuspend` and owns the
    // `PlaywrightVideoRecordDir` lifecycle. If the coordinator also publishes a record
    // dir, the manager's `syncRecordingWithRegistry` tears down + recreates the
    // BrowserContext at exactly the moment the trail is trying to navigate, wedging
    // the runBlocking call on the Playwright dispatcher thread. Web MCP capture is a
    // separate follow-up; this coordinator exists for Android (and iOS once
    // re-enabled in `CaptureSession.fromOptions`).
    if (platform == TrailblazeDevicePlatform.WEB) return false

    // Step 1: reserve the slot atomically under `lock`. Bail if another caller already
    // owns this sessionId, if this id was tombstoned by a previous failed `stopAll`
    // (could still have a leaked subprocess we don't want to race), OR if no capture
    // stream is wired for this platform (iOS today returns null from `fromOptions`).
    val reservation: ActiveCapture = synchronized(lock) {
      if (active.containsKey(sessionId)) return false
      if (tombstoned.contains(sessionId)) {
        Console.log(
          "[SessionCaptureCoordinator] refusing to start for tombstoned session $sessionId — " +
            "a previous stopAll() threw and we may have leaked subprocesses for this id",
        )
        return false
      }
      val sessionDir = logsRepo.getSessionDir(sessionId)
      if (!sessionDir.isDirectory && !sessionDir.mkdirs()) {
        Console.log(
          "[SessionCaptureCoordinator] could not create sessionDir for $sessionId at " +
            "${sessionDir.absolutePath} — refusing to start capture",
        )
        return false
      }
      val captureSession = captureSessionFactory(options, platform)
        ?: run {
          Console.log(
            "[SessionCaptureCoordinator] no capture wired for platform=$platform — skipping " +
              "session $sessionId",
          )
          return false
        }
      ActiveCapture(captureSession, sessionDir, deviceId, platform).also {
        active[sessionId] = it
      }
    }

    // Step 2: start capture OUTSIDE `lock`. This can block on adb/ffmpeg/xcrun startup,
    // so we deliberately don't serialize the daemon's session lifecycle on it.
    return try {
      reservation.session.startAll(reservation.sessionDir, deviceId, appId)

      // Step 3: re-acquire `lock` to commit the started state. If a concurrent
      // `stopForSession` removed the reservation mid-start, the slot won't be ours
      // anymore — treat that as "we got pre-empted" and clean up the partially-started
      // capture instead of leaking the subprocess.
      val committed = synchronized(lock) {
        if (active[sessionId] === reservation) {
          reservation.started = true
          true
        } else {
          false
        }
      }
      if (!committed) {
        runCatching { reservation.session.stopAll() }
        Console.log(
          "[SessionCaptureCoordinator] start raced with stop for session=$sessionId — " +
            "cleaned up the partially-started capture",
        )
        false
      } else {
        Console.log(
          "[SessionCaptureCoordinator] started capture for session=$sessionId " +
            "device=$deviceId platform=$platform sessionDir=${reservation.sessionDir.absolutePath}",
        )
        true
      }
    } catch (e: Exception) {
      // Step 4: undo the reservation and stop any subprocesses `startAll` may have
      // spawned before throwing (e.g. `screenrecord` started, ffmpeg muxer failed to
      // attach). Without this, every failed start leaks a screenrecord process for the
      // life of the daemon.
      synchronized(lock) { active.remove(sessionId, reservation) }
      runCatching { reservation.session.stopAll() }
      Console.log(
        "[SessionCaptureCoordinator] failed to start capture for session=$sessionId: ${e.message}",
      )
      false
    }
  }

  /**
   * Stops capture for [sessionId] and writes diagnostic metadata. Idempotent. Safe to
   * call multiple times — e.g. from both `endSessionForDevice` (the normal end) and a
   * later `cancelSessionForDevice` cleanup, only the first wins.
   *
   * If the entry is in the "reserved but not yet started" state (a concurrent
   * `startForSession` is between Step 1 and Step 3), returning `false` here cooperates
   * with that flow: the started-state guard in [startForSession] sees its reservation
   * has been removed and cleans up its own subprocesses.
   */
  fun stopForSession(sessionId: SessionId): Boolean {
    val capture = synchronized(lock) { active.remove(sessionId) } ?: return false
    if (!capture.started) {
      // The matching `startForSession` is still inside `captureSession.startAll`.
      // Removing the entry signals it to clean up on its own (see Step 3 above);
      // we don't run `stopAll` here because the capture isn't fully wired yet.
      Console.log(
        "[SessionCaptureCoordinator] stop preempted in-flight start for session=$sessionId — " +
          "the start path will clean up its own subprocesses",
      )
      return false
    }
    val debug = StringBuilder()
    debug.appendLine("sessionDir=${capture.sessionDir.absolutePath}")
    debug.appendLine("deviceId=${capture.deviceId}")
    debug.appendLine("platform=${capture.platform}")
    debug.appendLine("filesBeforeStop=${capture.sessionDir.list()?.toList() ?: emptyList<String>()}")
    val artifacts = try {
      capture.session.stopAll()
    } catch (e: Exception) {
      // Tombstone the session id — `stopAll` partially failed and we may have leaked a
      // subprocess we couldn't kill. Refuse to re-reserve this id so a later caller
      // can't race the leaked process for the same session log dir.
      synchronized(lock) { tombstoned.add(sessionId) }
      debug.appendLine("EXCEPTION on stopAll: ${e::class.simpleName}: ${e.message}")
      debug.appendLine("sessionId tombstoned — refusing further reservations for this id")
      Console.log(
        "[SessionCaptureCoordinator] stopAll threw for session=$sessionId: ${e.message} — " +
          "tombstoning to prevent re-reserve",
      )
      emptyList()
    }
    debug.appendLine("artifacts=${artifacts.size}")
    debug.appendLine(
      "artifactTypes=" + artifacts.joinToString(",") {
        "${it.type}:${it.file.name}:${it.file.exists()}:${it.file.length()}"
      },
    )
    debug.appendLine("filesAfterStop=${capture.sessionDir.list()?.toList() ?: emptyList<String>()}")
    runCatching { File(capture.sessionDir, "capture_debug.txt").writeText(debug.toString()) }
    Console.log(
      "[SessionCaptureCoordinator] stopped capture for session=$sessionId — " +
        "${artifacts.size} artifact(s) in ${capture.sessionDir.absolutePath}",
    )
    return true
  }

  /** True when capture is currently running (committed-started) for [sessionId]. */
  fun isActive(sessionId: SessionId): Boolean = synchronized(lock) {
    active[sessionId]?.started == true
  }

  /**
   * Best-effort shutdown of every still-active capture session. Called from daemon
   * shutdown hooks so a daemon kill (e.g. CLI source-change rebuild) doesn't leak
   * stale `screenrecord` / `xcrun` processes.
   *
   * Sessions stop in parallel with a per-session timeout — the default JVM shutdown
   * grace period is short, and a single wedged `stopAll()` (e.g. ffmpeg muxer stuck on
   * a missing keyframe) should not block the rest of the cleanup. Sessions that don't
   * stop in [perSessionTimeoutMs] are left for the OS to reap.
   */
  fun shutdownAll(perSessionTimeoutMs: Long = SHUTDOWN_PER_SESSION_TIMEOUT_MS) {
    val ids = synchronized(lock) { active.keys.toList() }
    if (ids.isEmpty()) return
    val pool = Executors.newFixedThreadPool(ids.size.coerceAtMost(8)) { runnable ->
      Thread(runnable, "session-capture-shutdown").apply { isDaemon = true }
    }
    try {
      val futures = ids.map { id -> pool.submit { runCatching { stopForSession(id) } } }
      val deadline = System.currentTimeMillis() + perSessionTimeoutMs * ids.size
      for (f in futures) {
        val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(0)
        try {
          f.get(remaining.coerceAtMost(perSessionTimeoutMs), TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
          Console.log(
            "[SessionCaptureCoordinator] shutdownAll: session stop did not complete within " +
              "${perSessionTimeoutMs}ms (${e::class.simpleName}: ${e.message})",
          )
        }
      }
    } finally {
      pool.shutdownNow()
    }
  }

  companion object {
    /**
     * Per-session deadline used by [shutdownAll]. ffmpeg muxer flush + adb `screenrecord`
     * teardown is typically <2s; 3000ms gives slow CI agents headroom without blocking
     * JVM shutdown grace on a wedged stream.
     */
    const val SHUTDOWN_PER_SESSION_TIMEOUT_MS = 3_000L

    /**
     * Documentation baseline showing the shape of `CaptureOptions` production callers
     * build inline (currently `TrailblazeDeviceManager.getOrCreateSessionResolution` and
     * `DesktopYamlRunner.captureSessionStarted`). Kept here so reviewers can see the
     * default sprite settings at a glance; also used by `SessionCaptureCoordinatorTest`
     * via the injectable `captureSessionFactory` seam. Not auto-applied — passing
     * misconfigured options silently records the wrong artifacts, so callers must
     * resolve their own.
     */
    val DEFAULT_CAPTURE_OPTIONS = CaptureOptions(
      captureVideo = true,
      captureLogcat = false,
      captureIosLogs = false,
      spriteFrameFps = 2,
      spriteFrameHeight = 720,
      spriteQuality = 80,
    )
  }
}
