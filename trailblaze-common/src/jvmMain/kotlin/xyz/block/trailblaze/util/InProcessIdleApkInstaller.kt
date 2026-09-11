package xyz.block.trailblaze.util

import xyz.block.trailblaze.device.AndroidPackageDump
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePort
import xyz.block.trailblaze.inprocessidle.AotCompileDecision
import xyz.block.trailblaze.inprocessidle.InProcessIdle
import java.io.File
import java.net.JarURLConnection
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Host side of turbo mode: puts the in-process idle detector on a device and turns the settle race
 * on, so every settle gate for the rest of the session can finish as soon as the app under test
 * says it is idle instead of waiting out an event-quiet heuristic.
 *
 * The detector is a tiny bare-`Instrumentation` APK that runs INSIDE the target app's process and
 * answers idleness over localhost TCP [InProcessIdle.PORT]. One pre-signed APK per target
 * applicationId is bundled in the CLI binary under [InProcessIdle.cliResourcePathFor]; a build with no bundled
 * APK for the requested app is a normal, silent no-op, not an error.
 *
 * This is deliberately NOT automatic. The sysprop it sets is device state that outlives the run,
 * attaching restarts the target app's process, and the speed win is only established on some
 * flows — so turbo is opt-in per device and off by default. Nothing here runs unless someone asks
 * for it.
 *
 * ## What "attached" requires
 *
 * Android only lets an instrumentation APK attach to an app signed with the SAME certificate. So
 * an attach can only ever work for an app whose signing key was available when the bundled
 * detector APKs were built. Everything else falls back silently to today's behavior — which is
 * safe, but means an engineer who sees no speedup deserves to be told why. Every outcome here is
 * a distinct [Outcome] value for exactly that reason.
 *
 * ## Ordering that is load-bearing
 *
 *  - **Never reinstall over an attached detector.** A package update tears down the instrumentation
 *    host, which kills the target app's process mid-run. [ensureAttached] pings first and returns
 *    early when the detector already serves this app.
 *  - **`am instrument` must precede the foreground launch.** It restarts the target's process
 *    headless, so attaching after a launch would kill the UI the launch just brought up.
 *  - **The launch must follow promptly.** A heavy app's `Application` init, started headless at
 *    background priority, can overrun the platform's ~20s process-start ANR watchdog. AOT
 *    compiling the target first ([aotCompileTarget]) takes the JIT cost out of that window and
 *    turns a scheduling race into a deterministic attach.
 */
object InProcessIdleApkInstaller {

  /** How long to wait for `PONG <appId>` after starting the attach. */
  private const val PONG_WAIT_MS = 60_000L

  /**
   * Bound on the ahead-of-time compile. Generous because it scales with APK size — a large POS
   * build takes tens of seconds — and because giving up early is worse than waiting: an
   * un-compiled target is exactly the case where the attach loses its race with the startup
   * watchdog.
   */
  private const val AOT_COMPILE_TIMEOUT_MS = 300_000L
  private const val PONG_POLL_INTERVAL_MS = 500L

  /**
   * Local port the host forwards to [deviceId]'s detector port.
   *
   * Deliberately not [InProcessIdle.PORT] itself: the host may be a developer's own machine with
   * something else on 7777, and a collision there would make a working detector look absent.
   *
   * Per device, not a single constant, because `adb forward` binds a HOST port and rebinds it
   * silently. Two devices sharing one local port would each steal the forward from the other, and
   * the loser's [ping] would then be answered by the other device's detector — reporting
   * `Attached` or `PortHeldByOtherApp` for a device that has neither. Derived the same way as
   * every other device-scoped port so the arithmetic (and the reserved-port skipping) has one home.
   */
  private fun hostForwardPortFor(deviceId: TrailblazeDeviceId): Int =
    TrailblazeDevicePort.getPortForDevice(deviceId, suffix = "inprocess-idle")

  /** Whether this build bundles a detector APK that could attach to [appId]. */
  fun hasBundledApkFor(appId: String): Boolean =
    InProcessIdleApkInstaller::class.java.getResource(InProcessIdle.cliResourcePathFor(appId)) != null

  /**
   * Whether [appId] is installed on [deviceId].
   *
   * A bundled detector is necessary but not sufficient: a target that declares several
   * applicationIds (a debug, an internal and a release id, say) can bundle helpers for more than
   * one of them, and attaching to the wrong — absent — one just fails. Checking installs is what
   * turns "first id with a helper" into "first id we can actually attach to".
   *
   * Non-suspend on purpose: the session-start hook that calls this is not a coroutine, and this is
   * the same `pm path` probe the detector's own install check already uses. Treats an adb failure
   * as "not installed" so a flaky probe declines turbo rather than attempting a doomed attach.
   */
  fun isAppInstalled(deviceId: TrailblazeDeviceId, appId: String): Boolean = try {
    AndroidHostAdbUtils
      .execAdbShellCommand(deviceId, listOf("pm", "path", appId))
      .contains("package:")
  } catch (t: Throwable) {
    false
  }

  /**
   * Applications this build bundles a detector for, as their last dotted labels.
   *
   * Read off the classpath entry this class was loaded from rather than probed against a list of
   * candidate labels written in source: `getResource` cannot list a directory inside a JAR, but
   * the JAR is a zip this process can open — and a hand-maintained list drifts from whatever a
   * build actually bundles, silently, because nothing fails when it goes stale.
   *
   * Empty when that entry is neither a readable archive nor a directory. Only messages read this;
   * an attach resolves the one resource it needs by name. So an unreadable classpath costs a
   * helpful sentence and nothing else.
   */
  fun bundledLabels(): List<String> = bundledDetectorLabels

  /**
   * Resolved once per process. The scan walks whole archives, and the answer can't change while
   * the classpath is fixed, so re-deriving it per diagnostic line would be pure waste.
   */
  private val bundledDetectorLabels: List<String> by lazy {
    bundledDetectorFileNames().toDetectorLabels()
  }

  /**
   * Asks the classloader for EVERY classpath entry carrying [DETECTOR_RESOURCE_DIR], not just the
   * one this class was loaded from. The detector APKs are packaged by a different module than the
   * one holding this class, so the two share an archive only in the shaded CLI — on a per-module
   * classpath (IDE, unit tests, any non-shadow run) this class's own entry has no `apks/` at all,
   * and inspecting it alone would report "none bundled" while the resources sit right there.
   */
  private fun bundledDetectorFileNames(): List<String> =
    bundledDetectorFileNames(InProcessIdleApkInstaller::class.java.classLoader)

  /** Takes the loader so a test can point it at an archive this class was not loaded from. */
  internal fun bundledDetectorFileNames(loader: ClassLoader?): List<String> {
    if (loader == null) return emptyList()
    val viaResourceDir = runCatching {
      loader.getResources(DETECTOR_RESOURCE_DIR)
        .asSequence()
        .flatMap { detectorFileNamesUnder(it) }
        .toList()
    }.getOrDefault(emptyList())
    if (viaResourceDir.isNotEmpty()) return viaResourceDir

    // `getResources(<dir>)` resolves a directory inside an archive only when the archive carries
    // an explicit entry for it. Gradle's jars do; not every archive does, and one that doesn't
    // would report "none bundled" while holding the APKs. Fall back to reading the loader's own
    // classpath entries, where a file entry alone is enough.
    return runCatching {
      (loader as? URLClassLoader)?.urLs.orEmpty()
        .asSequence()
        .filter { it.protocol == "file" }
        .map { File(it.toURI()) }
        .flatMap { detectorFileNamesInClasspathEntry(it) }
        .distinct()
        .toList()
    }.getOrDefault(emptyList())
  }

  /** File names under [DETECTOR_RESOURCE_DIR] in one classpath entry, exploded dir or archive. */
  private fun detectorFileNamesInClasspathEntry(entry: File): List<String> = runCatching {
    when {
      entry.isDirectory ->
        File(entry, DETECTOR_RESOURCE_DIR).listFiles()?.map { it.name }.orEmpty()

      entry.isFile -> ZipFile(entry).use { archive -> archive.detectorFileNames() }

      else -> emptyList()
    }
  }.getOrDefault(emptyList())

  /** [bundledLabels] without the process-wide cache, so a test can vary the classpath. */
  internal fun bundledLabelsFrom(loader: ClassLoader?): List<String> =
    bundledDetectorFileNames(loader).toDetectorLabels()

  private fun List<String>.toDetectorLabels(): List<String> = this
    .filter { it.startsWith(DETECTOR_FILE_PREFIX) && it.endsWith(DETECTOR_FILE_SUFFIX) }
    .map { it.removePrefix(DETECTOR_FILE_PREFIX).removeSuffix(DETECTOR_FILE_SUFFIX) }
    .distinct()
    .sorted()

  /** File names directly under one resolved [DETECTOR_RESOURCE_DIR] URL, exploded or in a jar. */
  private fun detectorFileNamesUnder(dirUrl: URL): List<String> = runCatching {
    when (dirUrl.protocol) {
      "file" -> File(dirUrl.toURI()).listFiles()?.map { it.name }.orEmpty()

      // Deliberately NOT closed: with URL caching on (the default) this JarFile is shared with
      // every other reader of the same archive, and closing it here would break theirs.
      "jar" -> (dirUrl.openConnection() as JarURLConnection).jarFile.detectorFileNames()

      else -> emptyList()
    }
  }.getOrDefault(emptyList())

  /** Names of the files sitting directly in [DETECTOR_RESOURCE_DIR] inside an archive. */
  private fun ZipFile.detectorFileNames(): List<String> = entries()
    .asSequence()
    .map { it.name }
    .filter { it.startsWith("$DETECTOR_RESOURCE_DIR/") }
    .map { it.substringAfterLast('/') }
    .filter { it.isNotEmpty() }
    .toList()

  /** Where [InProcessIdle.cliResourcePathFor] puts a bundled detector, and how it names one. */
  private const val DETECTOR_RESOURCE_DIR = "apks/inprocess-idle"
  private const val DETECTOR_FILE_PREFIX = "trailblaze-inprocess-idle-"
  private const val DETECTOR_FILE_SUFFIX = ".apk"

  /** What [ensureAttached] did, and why. */
  sealed interface Outcome {
    /** The detector is serving for this app and the settle race is on. */
    data class Attached(val alreadyAttached: Boolean) : Outcome

    /** No detector APK is bundled for this app in this build — nothing to attach, no error. */
    data class NotBundled(val appId: String) : Outcome

    /**
     * Another app's detector holds the port. Only one detector serves per device, and detaching
     * the other one means force-stopping that app, which is too destructive to do implicitly.
     */
    data class PortHeldByOtherApp(val otherAppId: String) : Outcome

    /** Something failed. The run can continue — settle gates just stay at heuristic speed. */
    data class Failed(val reason: String) : Outcome
  }

  /**
   * Installs the bundled detector for [appId] if needed, attaches it, waits for it to answer, then
   * turns the settle race on. Idempotent: an already-attached detector costs one socket probe.
   *
   * Never throws — turbo is an accelerator, and every settle gate races the detector against the
   * standard heuristic, so a failed attach means the session runs exactly as it does today.
   */
  fun ensureAttached(
    deviceId: TrailblazeDeviceId,
    appId: String,
    aotCompileTarget: Boolean = true,
    log: (String) -> Unit = { Console.log(it) },
  ): Outcome = try {
    forwardDetectorPort(deviceId)

    val reply = ping(deviceId) ?: pingAfterForwardRecovery(deviceId, log)
    when {
      reply == "PONG $appId" -> {
        log("[turbo] detector already attached to $appId")
        enableSettleRace(deviceId, log)
        Outcome.Attached(alreadyAttached = true)
      }

      reply?.startsWith("PONG ") == true -> {
        val other = reply.removePrefix("PONG ").trim()
        log(
          "[turbo] port ${InProcessIdle.PORT} already serves $other. Only one app can be turbo at " +
            "a time on a device; stop that app first if you meant to switch.",
        )
        Outcome.PortHeldByOtherApp(other)
      }

      !hasBundledApkFor(appId) -> {
        log(
          "[turbo] no detector is bundled for $appId, so it cannot be made turbo by this build. " +
            "An attach needs a detector signed with the app's own certificate; this build has: " +
            bundledLabels().joinToString(", ").ifEmpty { "(none)" },
        )
        Outcome.NotBundled(appId)
      }

      else -> attach(deviceId, appId, aotCompileTarget, log)
    }
  } catch (t: Throwable) {
    log("[turbo] attach for $appId failed: ${t.message}")
    Outcome.Failed(t.message ?: t::class.simpleName ?: "unknown")
  }

  /**
   * Turns the settle race off without detaching anything. Leaves the detector installed and
   * serving, so turning it back on is a single sysprop away.
   *
   * Returns whether the switch is now confirmed off. **The write is read back rather than
   * trusted**: `setprop` says nothing useful about failure, and a switch that stays on is not a
   * missed optimization — the driver reads it on every action, and the detector's idle request is
   * not app-scoped, so a switch left on can end a wait early on whatever process still holds the
   * detector port. A caller that asked for turbo to be off needs to know it did not happen.
   */
  fun disableSettleRace(deviceId: TrailblazeDeviceId, log: (String) -> Unit = { Console.log(it) }): Boolean {
    AndroidHostAdbUtils.execAdbShellCommandWithTimeout(
      deviceId,
      listOf("setprop", InProcessIdle.SETTLE_SYSPROP, "0"),
    )
    // Bounded, like the write: this runs inside session start, and a wedged adb transport must
    // surface as "could not confirm" rather than hold the run.
    if (readSettleRaceSwitch(deviceId) != false) return false
    log("[turbo] off — settle gates use the event-quiet heuristic only")
    return true
  }

  /**
   * Whether the settle race is currently on for [deviceId]. Throws when the switch could not be
   * read within the adb timeout, so a caller never mistakes an unreadable switch for an off one.
   */
  fun isSettleRaceEnabled(deviceId: TrailblazeDeviceId): Boolean =
    readSettleRaceSwitch(deviceId)
      ?: error("could not read ${InProcessIdle.SETTLE_SYSPROP} on ${deviceId.instanceId} within the adb timeout")

  /** The switch as the device reports it, or null when the bounded `getprop` produced nothing. */
  private fun readSettleRaceSwitch(deviceId: TrailblazeDeviceId): Boolean? =
    AndroidHostAdbUtils.execAdbShellCommandWithTimeout(deviceId, listOf("getprop", InProcessIdle.SETTLE_SYSPROP))
      ?.trim()
      ?.let { it == "1" || it.equals("true", ignoreCase = true) }

  private fun attach(
    deviceId: TrailblazeDeviceId,
    appId: String,
    aotCompileTarget: Boolean,
    log: (String) -> Unit,
  ): Outcome {
    val detectorPackage = InProcessIdle.packageFor(appId)

    if (!installIfStale(deviceId, appId, detectorPackage, log)) {
      return Outcome.Failed("could not install $detectorPackage")
    }

    // Before the instrumented start, not after: this is what keeps the attach from racing the
    // process-start ANR watchdog on a heavy app.
    if (aotCompileTarget) aotCompileTarget(deviceId, appId, log)

    log("[turbo] attaching $detectorPackage to $appId")
    val output = AndroidHostAdbUtils.execAdbShellCommand(
      deviceId,
      listOf("am", "instrument", "$detectorPackage/${InProcessIdle.INSTRUMENTATION_CLASS}"),
    )
    // `am instrument` without `-w` exits zero immediately, so an unresolvable component or a
    // signature mismatch shows up only as an error line in its output.
    if (InProcessIdle.amInstrumentReportedFailure(output)) {
      log(
        "[turbo] could not attach to $appId: ${output.trim().replace('\n', ' ').take(300)}. The " +
          "usual cause is that $appId is signed with a different certificate than the bundled " +
          "detector, which Android will not allow.",
      )
      return Outcome.Failed("am instrument rejected the attach")
    }

    // `am instrument` cold-starts the target headless; bringing it to the foreground promptly
    // keeps a slow init inside the (much wider) foreground ANR window.
    AndroidHostAdbUtils.launchAppWithAdbMonkey(deviceId, appId)

    if (!awaitPong(deviceId, appId)) {
      log(
        "[turbo] $appId never answered within ${PONG_WAIT_MS}ms — leaving turbo off so the " +
          "session runs at normal speed rather than half-attached",
      )
      return Outcome.Failed("detector never answered PING")
    }

    log("[turbo] attached to $appId")
    enableSettleRace(deviceId, log)
    return Outcome.Attached(alreadyAttached = false)
  }

  /**
   * Installs the bundled detector unless the exact same bytes are already installed.
   *
   * The SHA marker is what makes this safe to call on every attach: reinstalling a detector that
   * is already attached would kill the app it is attached to, and comparing bundled bytes against
   * a device-side marker is how we avoid doing that pointlessly.
   */
  private fun installIfStale(
    deviceId: TrailblazeDeviceId,
    appId: String,
    detectorPackage: String,
    log: (String) -> Unit,
  ): Boolean {
    val bundledSha = bundledApkSha(appId)
    val installed = AndroidHostAdbUtils
      .execAdbShellCommand(deviceId, listOf("pm", "path", detectorPackage))
      .contains("package:")
    if (installed && bundledSha != null && deviceSha(deviceId, detectorPackage) == bundledSha) {
      log("[turbo] $detectorPackage already installed and current")
      return true
    }

    val resource = InProcessIdleApkInstaller::class.java.getResourceAsStream(InProcessIdle.cliResourcePathFor(appId))
      ?: return false
    val tempApk = Files.createTempFile("trailblaze-inprocess-idle-", ".apk").toFile().apply { deleteOnExit() }
    resource.use { input -> tempApk.outputStream().use { input.copyTo(it) } }

    log("[turbo] installing $detectorPackage (${tempApk.length() / 1024} KB)")
    if (!AndroidHostAdbUtils.installApkFile(tempApk, deviceId)) return false
    if (bundledSha != null) writeDeviceSha(deviceId, detectorPackage, bundledSha, log)
    return true
  }

  /**
   * AOT-compiles the target so the instrumented cold start does not pay JIT inside the ANR
   * window. Non-fatal: without it the attach merely races the watchdog, and [awaitPong] reports
   * the outcome either way.
   *
   * Whether to compile at all is [AotCompileDecision]'s call, shared with the on-device attacher so
   * the two paths cannot drift apart on the question; this only knows how to issue the compile over
   * adb.
   */
  private fun aotCompileTarget(deviceId: TrailblazeDeviceId, appId: String, log: (String) -> Unit) {
    val decision = AotCompileDecision.forTarget(
      requested = true,
      appId = appId,
      debuggable = { targetDebuggable(deviceId, appId) },
    )
    val compilerFilterArgs = when (decision) {
      // Unreachable while `requested` is a constant, but stated rather than lumped into an `else`
      // so a future caller-driven opt-in gets a compile error here instead of silently compiling.
      AotCompileDecision.NotRequested -> return
      is AotCompileDecision.Skip -> {
        log("[turbo] skipping the ahead-of-time compile: ${decision.reason}")
        return
      }
      is AotCompileDecision.Compile -> decision.compilerFilterArgs
    }
    log("[turbo] compiling $appId ahead of time so the attach doesn't race the app's startup (a no-op when already compiled)")
    try {
      AndroidHostAdbUtils.execAdbShellCommandWithTimeout(
        deviceId = deviceId,
        args = listOf("cmd", "package", "compile") + compilerFilterArgs + appId,
        timeoutMs = AOT_COMPILE_TIMEOUT_MS,
      )
    } catch (t: Throwable) {
      log("[turbo] ahead-of-time compile of $appId failed (${t.message}) — attaching anyway")
    }
  }

  /**
   * Whether [appId] is a debug build, read off `dumpsys package` — the host has no
   * `PackageManager`, so text is the only transport available here.
   *
   * A dump we cannot get, or one that does not mention the package, reads as
   * [AndroidPackageDump.Debuggable.UNKNOWN] rather than as "not debuggable"; see
   * [AotCompileDecision.forTarget] for what that resolves to and why.
   */
  private fun targetDebuggable(
    deviceId: TrailblazeDeviceId,
    appId: String,
  ): AndroidPackageDump.Debuggable = try {
    val dump = AndroidHostAdbUtils.execAdbShellCommandWithTimeout(
      deviceId = deviceId,
      args = listOf("dumpsys", "package", appId),
      timeoutMs = PACKAGE_DUMP_TIMEOUT_MS,
    )
    // A null return is the timeout / non-zero-exit path, i.e. no dump to read.
    if (dump == null) {
      AndroidPackageDump.Debuggable.UNKNOWN
    } else {
      AndroidPackageDump.debuggable(dumpsysPackageOutput = dump, appId = appId)
    }
  } catch (t: Throwable) {
    AndroidPackageDump.Debuggable.UNKNOWN
  }

  /** `dumpsys package <appId>` is a few hundred lines; well under a second on any device. */
  private const val PACKAGE_DUMP_TIMEOUT_MS = 15_000L

  private fun enableSettleRace(deviceId: TrailblazeDeviceId, log: (String) -> Unit) {
    AndroidHostAdbUtils.execAdbShellCommandWithTimeout(deviceId, listOf("setprop", InProcessIdle.SETTLE_SYSPROP, "1"))
    log("[turbo] on — settle gates now finish as soon as the app reports idle")
  }

  private fun forwardDetectorPort(deviceId: TrailblazeDeviceId) {
    AndroidHostAdbUtils.adbPortForward(
      deviceId = deviceId,
      localPort = hostForwardPortFor(deviceId),
      remotePort = InProcessIdle.PORT,
    )
  }

  /**
   * Re-probes the detector after forcing its port forward back into existence.
   *
   * [AndroidHostAdbUtils.adbPortForward] short-circuits on this JVM's own record of which forwards
   * are live, and the daemon outlives adb-server restarts, `kill-server` version skew and emulator
   * hiccups — so a cached entry whose socket has died makes an attached detector look absent, and
   * every later session then pays the full [PONG_WAIT_MS] probing a forward that is not there.
   * Recovering re-reads adb's own forward table and re-establishes the forward.
   *
   * Only runs when the detector did not answer, so a healthy attach pays nothing for it, and a
   * first-ever attach pays one host-services query.
   */
  private fun pingAfterForwardRecovery(deviceId: TrailblazeDeviceId, log: (String) -> Unit): String? {
    val wasInAdbForwardTable = AndroidHostAdbUtils.diagnoseAndReAdbPortForward(
      deviceId = deviceId,
      localPort = hostForwardPortFor(deviceId),
      remotePort = InProcessIdle.PORT,
    )
    if (wasInAdbForwardTable == false) {
      log("[turbo] the detector's port forward was missing from adb's table — re-established it before probing again")
    }
    return ping(deviceId)
  }

  private fun awaitPong(deviceId: TrailblazeDeviceId, appId: String): Boolean {
    val expected = "PONG $appId"
    val deadline = System.currentTimeMillis() + PONG_WAIT_MS
    while (System.currentTimeMillis() < deadline) {
      if (ping(deviceId) == expected) return true
      Thread.sleep(PONG_POLL_INTERVAL_MS)
    }
    return ping(deviceId) == expected
  }

  /** The shared `PING` exchange, aimed at the forwarded host port rather than the device port. */
  private fun ping(deviceId: TrailblazeDeviceId): String? =
    InProcessIdle.ping(connectTimeoutMs = 500, port = hostForwardPortFor(deviceId))

  private fun deviceShaMarkerPath(detectorPackage: String) =
    "/data/local/tmp/trailblaze-inprocess-idle-$detectorPackage-sha.txt"

  private fun deviceSha(deviceId: TrailblazeDeviceId, detectorPackage: String): String? = try {
    AndroidHostAdbUtils
      .execAdbShellCommand(deviceId, listOf("cat", deviceShaMarkerPath(detectorPackage)))
      .trim()
      .takeIf { it.isNotEmpty() && !it.contains("No such file") }
  } catch (t: Throwable) {
    null
  }

  private fun writeDeviceSha(
    deviceId: TrailblazeDeviceId,
    detectorPackage: String,
    sha: String,
    log: (String) -> Unit,
  ) {
    try {
      // `execAdbShellCommand` space-joins argv into one device-shell command, so a redirect works
      // as an argument here; the SHA is hex, so nothing needs quoting.
      AndroidHostAdbUtils.execAdbShellCommand(
        deviceId,
        listOf("printf", "%s", sha, ">", deviceShaMarkerPath(detectorPackage)),
      )
    } catch (t: Throwable) {
      log("[turbo] could not record the installed detector version (${t.message}) — it will reinstall next time")
    }
  }

  private fun bundledApkSha(appId: String): String? = try {
    InProcessIdleApkInstaller::class.java.getResourceAsStream(InProcessIdle.cliResourcePathFor(appId))?.use { input ->
      val digest = MessageDigest.getInstance("SHA-256")
      val buffer = ByteArray(8192)
      var read: Int
      while (input.read(buffer).also { read = it } != -1) digest.update(buffer, 0, read)
      digest.digest().joinToString("") { "%02x".format(it) }
    }
  } catch (t: Throwable) {
    null
  }
}
