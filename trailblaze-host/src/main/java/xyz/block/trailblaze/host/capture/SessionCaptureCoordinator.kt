package xyz.block.trailblaze.host.capture

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import xyz.block.trailblaze.capture.CaptureMetadata
import xyz.block.trailblaze.capture.CaptureOptions
import xyz.block.trailblaze.capture.CaptureSession
import xyz.block.trailblaze.capture.ToolCallPhase
import xyz.block.trailblaze.capture.model.CaptureArtifact
import xyz.block.trailblaze.capture.model.CaptureFilenames
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.toolcalls.ToolCallObserver
import xyz.block.trailblaze.toolcalls.ToolCallObservers
import xyz.block.trailblaze.util.Console

/**
 * Per-`SessionId` ownership for video/log capture across **every** path that creates a
 * Trailblaze session — `./trailblaze run` from the CLI, the desktop UI's "Run trail"
 * button, and (most importantly for this class's existence) the MCP per-tool path
 * where the `step`/`ask`/`verify`/individual-tool dispatchers each open a Trailblaze
 * session without going through `DesktopYamlRunner`. Prior to this coordinator,
 * `DesktopYamlRunner` was the only place that started a `CaptureSession`, so MCP-driven
 * Android runs landed with no session recording — the report timeline showed an empty
 * scrubber.
 *
 * The coordinator does three things:
 *  1. Maintains a `SessionId -> CaptureSession` registry so a single session can have
 *     exactly one capture stream regardless of how many places try to start one.
 *  2. Owns the lifecycle: [startForSession] is idempotent and writes directly into the
 *     session's log dir (no temp-dir + move dance — the dir already exists by the time
 *     the session id is known), and [stopForSession] runs `stopAll()` and writes a
 *     `capture_debug.txt` next to the artifacts for diagnostics.
 *  3. Stays out of the way for platforms where capture isn't wired (Compose/desktop, which has no
 *     branch in [CaptureSession.fromOptions]). [startForSession] short-circuits with `false` so
 *     callers can treat it as fire-and-forget.
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
 *
 * ### Multi-device sessions
 *
 * A session that binds several devices (a trail's `config.devices:` configuration, or an MCP
 * session's named roster) is recorded on EVERY display, not just the one it started on: the
 * step that ran on the buyer display is otherwise a step whose recording shows the seller's screen
 * sitting still. [bindDevices] starts one video-only capture per companion into the same session
 * directory — under `video-<name>.webm` ([CaptureFilenames.companionVideoBasename]) so the files
 * cannot collide, while the start device keeps `video.webm` so every reader that knows only one
 * recording per session still finds the display the trail began on. [stopForSession] stops them
 * all and publishes ONE `capture_metadata.json` whose entries name their device
 * ([CaptureArtifact.deviceName]); the report matches each entry to the steps that ran there.
 *
 * Companions record video only. Their logs and memory would need their own filenames and their
 * own readers, and nothing asks for them yet. A web companion is recorded from its browser's live
 * screencast, never by Playwright's own recorder — see [bindDevices].
 */
class SessionCaptureCoordinator(
  private val logsRepo: LogsRepo,
  /**
   * Builds the capture for one companion device of a multi-device session (see [bindDevices]):
   * video only, written under the given basename so it lands beside the start device's recording
   * instead of over it. Its own seam because the primary factory is what every existing test drives
   * and a companion has a narrower contract — no logs, no memory, a filename that is not the
   * canonical one. The default records the way the primary does, minus the streams a companion
   * does not keep; a baguette-stream companion is not offered because that recorder names its own
   * file. A web companion gets no Playwright-recorder fallback; see [bindDevices].
   */
  private val companionCaptureFactory: (CaptureOptions, TrailblazeDevicePlatform, String) -> CaptureSession? =
    { options, platform, videoBasename ->
      CaptureSession.fromOptions(options, platform, videoBasename = videoBasename, webPlaywrightFallback = false)
    },
  /**
   * Test seam — swap the real `CaptureSession.fromOptions` for a factory that returns
   * fake `CaptureSession` instances so unit tests can drive idempotency / race /
   * exception paths without spawning real `screenrecord`/`xcrun` subprocesses.
   * Production code uses the default, which records iOS video via the shipping simctl recorder
   * unless the experimental baguette-stream recorder ([BaguetteIosVideoCapture]) is opted in via
   * [IosBaguetteVideoGate] (`trailblaze config ios-baguette-video true` or
   * `TRAILBLAZE_IOS_BAGUETTE_VIDEO=1`) — wall-clock-accurate frame timing so the report can overlay
   * session-log events on the video, with simctl as its automatic fallback. Android memory is
   * read through the on-device runner when one is installed ([OnDeviceRpcMemoryProbe]), over adb
   * otherwise.
   */
  private val captureSessionFactory: (CaptureOptions, TrailblazeDevicePlatform) -> CaptureSession? =
    { options, platform ->
      CaptureSession.fromOptions(
        options,
        platform,
        iosVideoStreamOverride =
          if (platform == TrailblazeDevicePlatform.IOS && IosBaguetteVideoGate.enabled()) {
            BaguetteIosVideoCapture()
          } else {
            null
          },
        androidMemoryProbeOverride =
          if (platform == TrailblazeDevicePlatform.ANDROID) OnDeviceRpcMemoryProbe() else null,
      )
    },
) {

  /** One device of a multi-device session, as [bindDevices] needs to see it. */
  data class BoundDevice(
    /** The name the trail's configuration (or the MCP roster) gave the device — `seller`, `buyer`. */
    val name: String,
    val deviceId: TrailblazeDeviceId,
    /**
     * The app the session drives on this device, when known — the target's first app id for the
     * device's platform. Handed to the companion's recorders as `startAll`'s appId; companions record
     * video only, and no video recorder reads it.
     */
    val appId: String? = null,
  )

  /** A companion display's running capture, keyed in [ActiveCapture.companions] by its name. */
  private class CompanionCapture(
    val name: String,
    val deviceId: TrailblazeDeviceId,
    val session: CaptureSession,
    /** The file basename this recording owns in the session directory; see [ActiveCapture.claimBasename]. */
    val basename: String,
    /** False while [bindDevices] is still inside `startAll`; see the reserve-then-start note there. */
    @Volatile var started: Boolean = false,
  )

  /**
   * Routes the agent loop's tool-call boundaries to the session's capture, so streams that sample
   * around every tool (memory) hear about them. A session whose capture is not (yet / any more)
   * started is simply not told.
   */
  private val toolCallObserver = object : ToolCallObserver {
    override fun onBeforeToolCall(sessionId: SessionId, toolName: String, traceId: TraceId?) =
      forwardToolCall(sessionId, ToolCallPhase.BEFORE, toolName, traceId)

    override fun onAfterToolCall(sessionId: SessionId, toolName: String, traceId: TraceId?) =
      forwardToolCall(sessionId, ToolCallPhase.AFTER, toolName, traceId)
  }

  /**
   * Whether [toolCallObserver] is currently in the process-global registry.
   *
   * Registration follows the captures, not the coordinator's construction: [ToolCallObservers]
   * holds observers by identity for the life of the JVM, so a coordinator that registered in its
   * constructor would stay on the dispatch path forever — harmless for one daemon-lifetime
   * instance, a genuine leak the moment one is built per run or per test. With no active capture
   * there is nothing to forward to anyway ([forwardToolCall] returns immediately), so listening
   * only while captures exist changes no behaviour.
   */
  private var observing = false

  private fun forwardToolCall(sessionId: SessionId, phase: ToolCallPhase, toolName: String, traceId: TraceId?) {
    val capture = synchronized(lock) { active[sessionId]?.takeIf { it.started } } ?: return
    capture.session.onToolCall(phase, toolName, traceId?.traceId)
  }

  /** Called with [lock] held, whenever [active] gains or loses an entry. */
  private fun syncObserverRegistration() {
    val wanted = active.isNotEmpty()
    if (wanted == observing) return
    observing = wanted
    if (wanted) ToolCallObservers.register(toolCallObserver) else ToolCallObservers.unregister(toolCallObserver)
  }

  /** Stops listening for tool calls. Captures still running are left to [stopForSession] / [shutdownAll]. */
  fun close() {
    synchronized(lock) {
      observing = false
      ToolCallObservers.unregister(toolCallObserver)
    }
  }

  private class ActiveCapture(
    val session: CaptureSession,
    val sessionDir: File,
    val deviceId: String,
    val platform: TrailblazeDevicePlatform,
    val options: CaptureOptions,
    var started: Boolean = false,
  ) {
    /** The configuration name of the device [session] records, once [bindDevices] has said. */
    @Volatile var deviceName: String? = null

    /** Companion displays being recorded alongside, by configuration name. Guarded by the coordinator's `lock`. */
    val companions = LinkedHashMap<String, CompanionCapture>()

    /** What companions unbound before the session ended captured; published with everything else at stop. */
    val finishedCompanionArtifacts = mutableListOf<CaptureArtifact>()

    /**
     * Every recording basename this session has handed out, the start device's included, lowercased.
     * Never released: a basename names a file on disk, so a companion unbound and bound again, or two
     * device names that sanitize to one basename, must each write a file of their own rather than
     * truncate one another's footage. Lowercased because the default macOS and Windows filesystems
     * treat `video-Buyer` and `video-buyer` as one file. Guarded by the coordinator's `lock`.
     */
    private val claimedBasenames = mutableSetOf(CaptureFilenames.VIDEO_BASENAME.lowercase())

    /** Claims [wanted], or `wanted-2`, `wanted-3`, … when an earlier recording already owns it. Call with `lock` held. */
    fun claimBasename(wanted: String): String {
      var candidate = wanted
      var n = 2
      while (!claimedBasenames.add(candidate.lowercase())) candidate = "$wanted-${n++}"
      return candidate
    }

    /**
     * The artifact list `capture_metadata.json` was last written with, or null before the session's
     * stop has written it. A companion whose unbind finishes stopping after that write adds its
     * artifacts here and rewrites the file, so footage that finished late is still on record.
     * Guarded by the coordinator's `lock`.
     */
    var publishedArtifacts: List<CaptureArtifact>? = null

    /** Serializes `capture_metadata.json` writes for this session; each write takes the newest list. */
    val metadataWriteLock = Any()
  }

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
   * Session ids whose capture has already been stopped. A session records once: a start that
   * arrives after the stop is refused rather than recording again into the same session dir.
   *
   * That late start is not hypothetical. A host run's own cleanup stops capture as it releases the
   * device, and the runner then fires its post-run capture start for the same session, a fallback
   * for runners that never report the session starting. Accepting it spawned a fresh recorder at
   * teardown, which the run's final stop ended about a second later, and that one-second clip
   * replaced the session's real recording. Seen on CI as an iOS video that starts after the trail
   * has finished.
   *
   * A stop marks the id ended even when nothing was recording yet. The device manager creates a
   * session and only then asks for its capture, outside its session lock, so an end or cancel can
   * land in that gap and find nothing to stop; the start that follows must still be refused.
   *
   * Insertion-ordered and capped at [MAX_REMEMBERED_ENDED_SESSIONS] so a long-lived daemon does not
   * grow it forever. The late start comes within seconds of the stop, so forgetting the oldest
   * costs nothing.
   */
  private val ended = LinkedHashSet<SessionId>()

  /** Called with [lock] held. */
  private fun rememberEnded(sessionId: SessionId) {
    ended.remove(sessionId)
    ended.add(sessionId)
    while (ended.size > MAX_REMEMBERED_ENDED_SESSIONS) ended.remove(ended.first())
  }

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
   *   has no coordinator-owned capture stream (WEB, which self-instruments — see below —
   *   or Compose/desktop, which has no branch in [CaptureSession.fromOptions]; Android
   *   and iOS are both wired), or (c) `startAll` threw and the slot was cleaned up.
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
    // the runBlocking call on the Playwright dispatcher thread. A session that starts on a
    // browser therefore has no capture here — and so no companion recordings either (see
    // [bindDevices]); web companions of an Android or iOS session are recorded.
    if (platform == TrailblazeDevicePlatform.WEB) return false

    // Step 1: reserve the slot atomically under `lock`. Bail if another caller already
    // owns this sessionId, if this id was tombstoned by a previous failed `stopAll`
    // (could still have a leaked subprocess we don't want to race), OR if no capture
    // stream is wired for this platform (`fromOptions` returns null when capture is off).
    val reservation: ActiveCapture = synchronized(lock) {
      if (active.containsKey(sessionId)) return false
      if (tombstoned.contains(sessionId)) {
        Console.log(
          "[SessionCaptureCoordinator] refusing to start for tombstoned session $sessionId — " +
            "a previous stopAll() threw and we may have leaked subprocesses for this id",
        )
        return false
      }
      if (sessionId in ended) {
        Console.log(
          "[SessionCaptureCoordinator] not restarting capture for session $sessionId — it was " +
            "already stopped, and a second recording would replace the first",
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
      ActiveCapture(captureSession, sessionDir, deviceId, platform, options).also {
        active[sessionId] = it
        syncObserverRegistration()
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
      synchronized(lock) {
        active.remove(sessionId, reservation)
        syncObserverRegistration()
      }
      runCatching { reservation.session.stopAll() }
      Console.log(
        "[SessionCaptureCoordinator] failed to start capture for session=$sessionId: ${e.message}",
      )
      false
    }
  }

  /**
   * Tells the capture for [sessionId] which devices the session binds, and records every companion.
   *
   * [devices] is the whole roster, start device included: the entry whose device is the one
   * capture already runs on names that recording, and every other entry becomes a companion
   * recording. Call it after [startForSession] has committed — from the same thread, as the trail
   * runner does — and as often as the roster changes: a name already recorded is left alone, so
   * re-sending the roster is free, and a companion bound mid-session (an MCP `device(action=BIND)`)
   * starts recording from that moment.
   *
   * Companions record only when the session's own capture records video: a session that opted out
   * of video has not asked for footage of any display — and a session that started on a browser has
   * no capture here at all ([startForSession] skips WEB), so none of its devices are recorded. A companion records video only: the session
   * has one `device.log` and one memory series, and they are the start device's.
   *
   * A web companion is recorded from its browser's live screencast, which it follows for the whole
   * session — a browser that comes up after the bind is picked up when it does. It never falls back
   * to Playwright's own recorder the way a web session's own capture can: that recorder is a
   * context-creation option, and turning it on for a browser the session is already driving
   * rebuilds its context mid-navigation (the wedge [startForSession]'s WEB skip avoids).
   *
   * Companion starts follow the same reserve-then-start protocol as the primary: the name is
   * reserved under `lock` before the recorder is started outside it, and a start that finds its
   * session already stopped tears itself down instead of leaking a recorder.
   *
   * @return how many companion recordings this call started.
   */
  fun bindDevices(sessionId: SessionId, devices: List<BoundDevice>): Int {
    val capture = synchronized(lock) { active[sessionId]?.takeIf { it.started } }
    if (capture == null) {
      Console.log(
        "[SessionCaptureCoordinator] no committed capture for session=$sessionId — " +
          "not recording its ${devices.size} bound device(s)",
      )
      return 0
    }
    val (self, others) = devices.partition { it.deviceId.instanceId == capture.deviceId }
    self.firstOrNull()?.let { capture.deviceName = it.name }
    if (others.isEmpty()) return 0
    if (!capture.options.captureVideo) {
      Console.log(
        "[SessionCaptureCoordinator] video capture is off for session=$sessionId, so its companion " +
          "device(s) ${others.map { it.name }} are not recorded either",
      )
      return 0
    }
    val companionOptions = capture.options.copy(captureLogcat = false, captureIosLogs = false, captureMemory = false)
    var started = 0
    for (device in others) {
      val reservation = synchronized(lock) {
        if (active[sessionId] !== capture || capture.companions.containsKey(device.name)) return@synchronized null
        val basename = capture.claimBasename(CaptureFilenames.companionVideoBasename(device.name))
        val session = companionCaptureFactory(companionOptions, device.deviceId.trailblazeDevicePlatform, basename)
          ?: run {
            Console.log(
              "[SessionCaptureCoordinator] no capture wired for companion '${device.name}' " +
                "(${device.deviceId.trailblazeDevicePlatform}) in session=$sessionId",
            )
            return@synchronized null
          }
        CompanionCapture(device.name, device.deviceId, session, basename).also { capture.companions[device.name] = it }
      } ?: continue
      try {
        reservation.session.startAll(capture.sessionDir, device.deviceId.instanceId, device.appId)
        val committed = synchronized(lock) {
          val live = active[sessionId] === capture && capture.companions[device.name] === reservation
          if (live) reservation.started = true
          live
        }
        if (!committed) {
          runCatching { reservation.session.stopAll(writeMetadata = false) }
          Console.log(
            "[SessionCaptureCoordinator] companion '${device.name}' start raced with stop for " +
              "session=$sessionId — cleaned up its recorder",
          )
          continue
        }
        started++
        Console.log(
          "[SessionCaptureCoordinator] recording companion '${device.name}' " +
            "(${device.deviceId.instanceId}) for session=$sessionId as ${reservation.basename}",
        )
      } catch (e: Exception) {
        synchronized(lock) { capture.companions.remove(device.name, reservation) }
        runCatching { reservation.session.stopAll(writeMetadata = false) }
        Console.log(
          "[SessionCaptureCoordinator] failed to record companion '${device.name}' " +
            "(${device.deviceId.instanceId}) for session=$sessionId: ${e.message}",
        )
      }
    }
    return started
  }

  /**
   * Stops recording the companion bound as [name] — an MCP `device(action=UNBIND)` — keeping what
   * it captured so far for the session's `capture_metadata.json`. The session's own capture is
   * not a companion and is left alone. Returns false when no such companion is recording.
   *
   * A companion still starting is let go too: its start then finds the name no longer reserved and
   * stops its own recorder, so nothing is left filming a device the session no longer binds.
   */
  fun unbindDevice(sessionId: SessionId, name: String): Boolean {
    val (capture, companion) = synchronized(lock) {
      val capture = active[sessionId] ?: return false
      val companion = capture.companions.remove(name) ?: return false
      if (!companion.started) return false
      capture to companion
    }
    val artifacts = stopCompanion(sessionId, companion)
    val publishedAlready = synchronized(lock) {
      capture.finishedCompanionArtifacts += artifacts
      val published = capture.publishedArtifacts ?: return@synchronized false
      // The session stopped while this companion was still stopping, and its metadata went out
      // without this recording. Add it and write again, or the file on disk is invisible to readers.
      capture.publishedArtifacts = published + artifacts
      artifacts.isNotEmpty()
    }
    if (publishedAlready) {
      runCatching { writeCaptureMetadata(capture) }.onFailure {
        Console.log("[SessionCaptureCoordinator] rewriting ${CaptureMetadata.FILENAME} for session=$sessionId failed: ${it.message}")
      }
    }
    return true
  }

  /** Writes the newest [ActiveCapture.publishedArtifacts]; writers queue on the session's own lock, so the last write is the fullest. */
  private fun writeCaptureMetadata(capture: ActiveCapture) {
    synchronized(capture.metadataWriteLock) {
      val artifacts = synchronized(lock) { capture.publishedArtifacts } ?: return
      if (artifacts.isNotEmpty()) CaptureMetadata.write(capture.sessionDir, artifacts)
    }
  }

  /** Stops one companion's recorder and tags what it produced with the device it filmed. */
  private fun stopCompanion(sessionId: SessionId, companion: CompanionCapture): List<CaptureArtifact> = try {
    companion.session.stopAll(writeMetadata = false)
      .map { it.copy(deviceName = companion.name, deviceId = companion.deviceId.instanceId) }
  } catch (e: Exception) {
    Console.log(
      "[SessionCaptureCoordinator] stopping companion '${companion.name}' for session=$sessionId " +
        "threw: ${e.message}",
    )
    emptyList()
  }

  /**
   * Stops capture for [sessionId] and writes diagnostic metadata. Idempotent. Safe to
   * call multiple times — e.g. from both `endSessionForDevice` (the normal end) and a
   * later `cancelSessionForDevice` cleanup, only the first wins. Final: a later
   * [startForSession] for the same id is refused (see [ended]).
   *
   * @param markEnded false only for a caller that is guessing which session is its own. It stops
   *   whatever is recording but leaves the id free to start, because the guess may be another run's
   *   session whose capture has not started yet, and marking it ended would leave it with no video.
   *
   * If the entry is in the "reserved but not yet started" state (a concurrent
   * `startForSession` is between Step 1 and Step 3), returning `false` here cooperates
   * with that flow: the started-state guard in [startForSession] sees its reservation
   * has been removed and cleans up its own subprocesses.
   */
  fun stopForSession(sessionId: SessionId, markEnded: Boolean = true): Boolean {
    val capture = synchronized(lock) {
      if (markEnded) rememberEnded(sessionId)
      active.remove(sessionId).also { syncObserverRegistration() }
    } ?: return false
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
    debug.appendLine("deviceName=${capture.deviceName}")
    debug.appendLine("platform=${capture.platform}")
    // Snapshot under lock; the entry is already out of `active`, so no bind can add to it after
    // this — a bind mid-start sees the session gone and tears its own recorder down.
    val companions = synchronized(lock) { capture.companions.values.toList() }
    debug.appendLine(
      "companions=" + companions.joinToString(",") { "${it.name}:${it.deviceId.instanceId}:${it.started}" },
    )
    debug.appendLine("filesBeforeStop=${capture.sessionDir.list()?.toList() ?: emptyList<String>()}")
    val primaryArtifacts = try {
      // The merged list is written below, once, with every device's entries — each capture writing
      // its own would leave whichever stopped last as the only one on record.
      capture.session.stopAll(writeMetadata = false)
        .map { it.copy(deviceName = capture.deviceName, deviceId = capture.deviceId) }
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
    val companionArtifacts = companions.filter { it.started }.flatMap { stopCompanion(sessionId, it) }
    // Taking the finished list and publishing happen under one lock hold, so an unbind still
    // stopping its companion either lands in this list or sees it published and writes again.
    val artifacts = synchronized(lock) {
      (primaryArtifacts + companionArtifacts + capture.finishedCompanionArtifacts).also { capture.publishedArtifacts = it }
    }
    runCatching { writeCaptureMetadata(capture) }
      .onFailure { debug.appendLine("EXCEPTION writing ${CaptureMetadata.FILENAME}: ${it.message}") }
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
    /** Cap on [ended]; see there. */
    private const val MAX_REMEMBERED_ENDED_SESSIONS = 1_024

    /**
     * Per-session deadline used by [shutdownAll]. ffmpeg muxer flush + adb `screenrecord`
     * teardown is typically <2s; 3000ms gives slow CI agents headroom without blocking
     * JVM shutdown grace on a wedged stream.
     */
    const val SHUTDOWN_PER_SESSION_TIMEOUT_MS = 3_000L

    /**
     * Documentation baseline showing how production callers resolve `CaptureOptions`
     * (currently `TrailblazeDeviceManager.getOrCreateSessionResolution` and
     * `DesktopYamlRunner.captureSessionStarted`, all through
     * `CaptureOptions.hostCaptureOptions`, which owns the video opt-in and its env
     * override). Also used by `SessionCaptureCoordinatorTest` via the injectable
     * `captureSessionFactory` seam. Not auto-applied — passing misconfigured options
     * silently records the wrong artifacts, so callers must resolve their own.
     */
    val DEFAULT_CAPTURE_OPTIONS = CaptureOptions.hostCaptureOptions(
      captureVideo = false,
      captureLogcat = true,
      captureIosLogs = true,
    )
  }
}
