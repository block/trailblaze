package xyz.block.trailblaze.host.networkcapture

import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.util.AndroidHostAdbUtils
import xyz.block.trailblaze.util.Console
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap

/**
 * Host-driven Android network capture via an HTTP(S) MITM proxy ([mitmproxy](https://mitmproxy.org)).
 *
 * Unlike in-app capture approaches (which require the app under test to embed a capture plugin and
 * therefore only work for first-party apps), this captures **any** app's traffic — the path for
 * third-party apps and external users — by routing the emulator through a host-side `mitmdump`,
 * installing mitmproxy's CA into the device system trust
 * store, and writing each flow into the shared [xyz.block.trailblaze.network.NetworkEvent] schema at
 * `<sessionDir>/network.ndjson` — the same file the desktop app's Network tab and the HTML report
 * already render. The mapping is done by the bundled `mitmproxy_netcapture.py` addon.
 *
 * ### Scope / limitations (v1)
 * - **Android emulator, API 34+.** Uses `10.0.2.2` as the emulator→host address and a writable-system
 *   (rootable / userdebug) image to install the CA into the Conscrypt APEX store. API < 34 (legacy
 *   `/system` store) is detected and skipped with a clear message, leaving the device untouched — a
 *   planned follow-up. Physical devices and Play (non-root) images are out of scope.
 * - **Cert pinning** apps and **QUIC/HTTP3** traffic are not decrypted/captured (proxy limitation).
 * - **Chrome** ignores the system CA (Chrome Root Store) so browser traffic isn't captured; native
 *   apps using the system network stack are.
 * - **Sensitive data:** captured request/response bodies and full URLs are written to
 *   `<sessionDir>/network.ndjson` (and the shared HTML report) with only `Authorization` request
 *   headers and `Set-Cookie` response headers redacted — query-string tokens and body PII are NOT
 *   scrubbed. Don't run captures against real user data; broader redaction is a follow-up.
 *
 * ### Lifecycle
 * One [Session] per `sessionId`; [start] is idempotent. The full device mutation (proxy + CA) is
 * reverted in [stop] AND defensively at the next [start] AND via a JVM shutdown hook, so a crashed
 * run never strands the device with a dangling proxy / untrusted-cert state ("connection is not
 * private"). On API 34+ the CA lives in the Conscrypt APEX, installed via a bind-mount propagated
 * into the zygote mount namespaces.
 */
object MitmproxyAndroidNetworkCaptureActivator : AndroidNetworkCaptureActivator {

  /** Env override pointing directly at a `mitmdump` binary (skips managed-venv resolution). */
  private const val ENV_MITMDUMP = "TRAILBLAZE_MITMDUMP"

  /** Emulator's address for the host loopback (standard Android emulator networking). */
  private const val EMULATOR_HOST = "10.0.2.2"

  /**
   * Minimum Android API the CA-install path supports. The recipe targets the API 34+ Conscrypt APEX
   * store; the legacy `/system/etc/security/cacerts` remount for API 24–33 is a planned follow-up.
   */
  private const val MIN_SUPPORTED_SDK = 34

  private val cacheRoot: File = File(System.getProperty("user.home"), ".trailblaze/network-capture")
  private val confDir: File = File(cacheRoot, "mitmproxy")
  private val caCert: File = File(confDir, "mitmproxy-ca-cert.pem")

  private val sessions: MutableMap<String, Session> = ConcurrentHashMap()

  @Volatile private var shutdownHookInstalled = false

  override fun start(
    sessionId: String,
    sessionDir: File,
    deviceId: TrailblazeDeviceId,
    targetAppId: String?,
  ) {
    installShutdownHookOnce()
    sessions.compute(sessionId) { id, existing ->
      if (existing != null) return@compute existing
      val session = Session(id, sessionDir, deviceId)
      runCatching { session.start() }
        .onFailure { Console.error("[mitm-capture] $id: start failed: ${it.message}") }
      session
    }
  }

  override fun stop(sessionId: String) {
    sessions.remove(sessionId)?.let { session ->
      runCatching { session.stop() }
        .onFailure { Console.error("[mitm-capture] $sessionId: stop failed: ${it.message}") }
    }
  }

  private fun installShutdownHookOnce() {
    if (shutdownHookInstalled) return
    synchronized(this) {
      if (shutdownHookInstalled) return
      Runtime.getRuntime().addShutdownHook(
        Thread {
          // Snapshot + clear so the device is reverted even on an abrupt daemon shutdown.
          sessions.values.toList().forEach { runCatching { it.stop() } }
          sessions.clear()
        },
      )
      shutdownHookInstalled = true
    }
  }

  /**
   * Resolve a usable `mitmdump`: explicit env override, else a managed venv, else search PATH.
   * Returns null (not a bare "mitmdump") when none is found, so the caller can emit an actionable
   * "install mitmproxy / set $ENV_MITMDUMP" message instead of failing later with an opaque
   * "cannot run program" error.
   */
  private fun resolveMitmdump(): String? {
    System.getenv(ENV_MITMDUMP)?.takeIf { it.isNotBlank() }?.let { path ->
      if (File(path).canExecute()) return path
      Console.error("[mitm-capture] $ENV_MITMDUMP=$path is not executable; falling back")
    }
    val venvMitmdump = File(cacheRoot, "venv/bin/mitmdump")
    if (venvMitmdump.canExecute()) return venvMitmdump.absolutePath
    // Search PATH for an executable `mitmdump`.
    val pathDirs = System.getenv("PATH")?.split(File.pathSeparator).orEmpty()
    return pathDirs.asSequence()
      .map { File(it, "mitmdump") }
      .firstOrNull { it.canExecute() }
      ?.absolutePath
  }

  /** Per-session capture bridge: owns the mitmdump process and the device proxy/CA mutation. */
  private class Session(
    private val sessionId: String,
    private val sessionDir: File,
    private val deviceId: TrailblazeDeviceId,
  ) {
    @Volatile private var process: Process? = null
    @Volatile private var port: Int = 0
    @Volatile private var certHash: String? = null

    /**
     * Intentionally BLOCKS (unlike the SPI's "should not block" guidance, which targets the
     * background-polling in-app capture path): the CA + proxy must be fully in place on the device BEFORE the
     * trail launches the app under test, or the app's first connections miss the proxy / reject the
     * cert. Setup is a few seconds (CA-wait + cert install + wifi bounce); failures degrade to a
     * logged no-op rather than throwing.
     */
    fun start() {
      sessionDir.mkdirs()
      // Loud, once-per-session opt-in banner: this MITM-proxy path is an EXPERIMENTAL, best-effort
      // capture mode you turned on explicitly — not a supported Trailblaze feature. Surface the
      // limitations every time so nobody mistakes it for a general capability or points it at real
      // user data.
      Console.error(
        "[mitm-capture] ⚠️  EXPERIMENTAL Android network capture is ON via the opt-in " +
          "TRAILBLAZE_ANDROID_PROXY_CAPTURE env var (unset it to turn this off). This is a " +
          "best-effort MITM proxy, NOT a supported capture mode. Limitations: emulator + API " +
          "$MIN_SUPPORTED_SDK+ only; cert-pinned apps and QUIC/HTTP3 are NOT captured; " +
          "request/response bodies and full URLs are written to the session with only Authorization " +
          "/ Set-Cookie redacted — do NOT run it against real user data.",
      )
      // The CA-install recipe targets the API 34+ Conscrypt APEX store. On API < 34 the system store
      // is /system/etc/security/cacerts (a writable-system remount — a planned follow-up), so rather
      // than silently set a proxy whose cert nothing trusts (which would break the app's HTTPS while
      // capturing nothing), skip cleanly and leave the device untouched.
      val sdk = deviceSdkInt()
      if (sdk != null && sdk < MIN_SUPPORTED_SDK) {
        Console.error(
          "[mitm-capture] $sessionId: Android proxy capture currently requires API $MIN_SUPPORTED_SDK+ " +
            "(device is API $sdk); skipping with the device left untouched. (API 24–33 system-store " +
            "install is a planned follow-up.)",
        )
        return
      }
      val mitmdump = resolveMitmdump()
      if (mitmdump == null) {
        Console.error(
          "[mitm-capture] $sessionId: mitmdump not found — install mitmproxy (e.g. " +
            "`pip install mitmproxy`) and add it to PATH, or set $ENV_MITMDUMP=/path/to/mitmdump. " +
            "Android network capture is disabled for this session.",
        )
        return
      }
      val addon = extractAddon()
      if (addon == null) {
        Console.error("[mitm-capture] $sessionId: could not extract addon; capture disabled")
        return
      }
      confDir.mkdirs()

      // Defensive: revert any stale proxy/CA from a previously-crashed run before we set ours.
      revertDevice(quiet = true)

      port = ServerSocket(0).use { it.localPort }
      val pb = ProcessBuilder(
        mitmdump,
        "-s", addon.absolutePath,
        "--set", "confdir=${confDir.absolutePath}",
        "--listen-host", "127.0.0.1",
        "--listen-port", port.toString(),
        "-q",
      ).redirectErrorStream(true)
      pb.environment()["TRAILBLAZE_SESSION_ID"] = sessionId
      pb.environment()["TRAILBLAZE_SESSION_DIR"] = sessionDir.absolutePath
      pb.redirectOutput(File(sessionDir, "mitmdump.log"))
      process = pb.start()
      Console.log("[mitm-capture] $sessionId: mitmdump pid=${process?.pid()} on 127.0.0.1:$port")

      // mitmdump writes the CA to confdir on startup; wait for it AND confirm the process is still
      // alive. The CA persists in the shared confdir across runs, so awaitFile alone would pass on a
      // stale cert even if THIS mitmdump died at startup (bad binary, addon/version error, bind race)
      // — we'd then point the device proxy at a dead port and kill the app's network.
      if (!awaitFile(caCert, timeoutMs = 15_000) || process?.isAlive != true) {
        Console.error(
          "[mitm-capture] $sessionId: mitmdump exited at startup or CA never appeared at " +
            "${caCert.absolutePath} (see ${File(sessionDir, "mitmdump.log")}); capture disabled.",
        )
        stop()
        return
      }

      // Device mutation: install the CA, point the proxy at mitmdump, bounce wifi. If any step fails,
      // fully revert via stop() (clears proxy/CA, kills mitmdump) so we never strand the device with a
      // dangling proxy / untrusted-cert state.
      try {
        val hash = computeAndroidCertHash(caCert)
          ?: error("could not compute cert hash (is openssl on PATH?)")
        certHash = hash
        installCaOnDevice(hash)
        setProxy("$EMULATOR_HOST:$port")
        bounceWifi()
      } catch (t: Throwable) {
        Console.error("[mitm-capture] $sessionId: device setup failed (${t.message}); reverting.")
        stop()
        return
      }
      Console.log("[mitm-capture] $sessionId: capture active → ${File(sessionDir, "network.ndjson")}")
    }

    fun stop() {
      revertDevice(quiet = false)
      process?.let { p ->
        p.destroy()
        if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
          p.destroyForcibly()
          p.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)
        }
      }
      process = null
      Console.log("[mitm-capture] $sessionId: capture stopped, device reverted")
    }

    /** Clear the proxy + remove our CA from the device. Idempotent; safe to call when nothing is set. */
    private fun revertDevice(quiet: Boolean) {
      runCatching { adb(listOf("settings", "delete", "global", "http_proxy")) }
        .onFailure { if (!quiet) Console.error("[mitm-capture] $sessionId: proxy clear failed: ${it.message}") }
      // Unmount the CA bind-mount from the global + zygote namespaces (no-op if absent).
      runCatching {
        adb(
          listOf(
            "su", "0", "sh", "-c",
            "'for pid in 1 \$(pidof zygote) \$(pidof zygote64); do " +
              "nsenter --mount=/proc/\$pid/ns/mnt -- umount /apex/com.android.conscrypt/cacerts 2>/dev/null || true; done; " +
              "umount /apex/com.android.conscrypt/cacerts 2>/dev/null || true; " +
              "rm -rf /data/local/tmp/tb-cacerts-copy /data/local/tmp/tb-mitm-ca.0'",
          ),
        )
      }
      runCatching { bounceWifi() }
    }

    /**
     * Install [caCert] into the device system trust store. On API 34+ the active store is the
     * Conscrypt APEX (`/apex/com.android.conscrypt/cacerts`), which is read-only, so we copy it to a
     * writable dir, add our `<hash>.0`, set the system_file SELinux context, and bind-mount it back —
     * propagating the mount into the zygote namespaces so freshly-forked apps trust it. Requires
     * root (userdebug emulator).
     */
    private fun installCaOnDevice(hash: String) {
      val remoteCert = "/data/local/tmp/tb-mitm-ca.0"
      check(AndroidHostAdbUtils.pushFile(deviceId, caCert, remoteCert)) {
        "failed to push CA cert to $remoteCert (without it the device-side install can't trust the proxy)"
      }
      adb(
        listOf(
          "su", "0", "sh", "-c",
          "'SRC=/apex/com.android.conscrypt/cacerts; TMP=/data/local/tmp/tb-cacerts-copy; " +
            "rm -rf \$TMP; mkdir -p \$TMP; cp -f \$SRC/* \$TMP/ 2>/dev/null; " +
            "cp -f $remoteCert \$TMP/$hash.0; chown root:root \$TMP/*; chmod 644 \$TMP/*; " +
            "chcon u:object_r:system_file:s0 \$TMP/*; mount -o bind \$TMP \$SRC; " +
            "for pid in 1 \$(pidof zygote) \$(pidof zygote64); do " +
            "nsenter --mount=/proc/\$pid/ns/mnt -- mount -o bind \$TMP \$SRC 2>/dev/null || true; done'",
        ),
      )
      // Verify the cert actually landed — the mount can fail silently on a non-rooted /
      // non-writable-system image (execAdbShellCommand doesn't surface the non-zero exit). If it's
      // not there, throw so start()'s try/catch reverts instead of setting a proxy with no trusted CA.
      val installed = adb(
        listOf("su", "0", "sh", "-c", "'ls /apex/com.android.conscrypt/cacerts/$hash.0 2>/dev/null'"),
      )
      check(installed.contains("$hash.0")) {
        "CA not present in the system store after install (device not rootable / writable-system?)"
      }
    }

    private fun setProxy(hostPort: String) = adb(listOf("settings", "put", "global", "http_proxy", hostPort))

    private fun bounceWifi() {
      // Apps cache the proxy; a connectivity bounce forces them to re-read it.
      adb(listOf("svc", "wifi", "disable"))
      adb(listOf("svc", "wifi", "enable"))
    }

    private fun adb(args: List<String>): String =
      AndroidHostAdbUtils.execAdbShellCommand(deviceId, args)

    /** Device API level via `getprop ro.build.version.sdk`; null if it can't be read/parsed. */
    private fun deviceSdkInt(): Int? =
      runCatching { adb(listOf("getprop", "ro.build.version.sdk")).trim().toInt() }.getOrNull()
  }

  // --- helpers (object-level so they're shared / testable) ---

  private const val ADDON_RESOURCE = "networkcapture/mitmproxy_netcapture.py"

  private fun extractAddon(): File? = try {
    val bytes = MitmproxyAndroidNetworkCaptureActivator::class.java.classLoader
      ?.getResourceAsStream(ADDON_RESOURCE)
      ?.use { it.readBytes() }
    if (bytes == null || bytes.isEmpty()) {
      null
    } else {
      val out = File(cacheRoot, "mitmproxy_netcapture.py")
      out.parentFile.mkdirs()
      if (!out.isFile || out.length() != bytes.size.toLong() || !out.readBytes().contentEquals(bytes)) {
        out.writeBytes(bytes)
      }
      out
    }
  } catch (t: Throwable) {
    Console.error("[mitm-capture] failed to extract addon: ${t.message}")
    null
  }

  private fun awaitFile(file: File, timeoutMs: Long): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      if (file.isFile && file.length() > 0) return true
      Thread.sleep(200)
    }
    return file.isFile && file.length() > 0
  }

  /**
   * Android names system CA files `<subject_hash_old>.0`. Compute it via host `openssl`
   * (`-subject_hash_old`), which every dev host has. Returns null if openssl is unavailable.
   */
  private fun computeAndroidCertHash(cert: File): String? = try {
    val proc = ProcessBuilder(
      "openssl", "x509", "-inform", "PEM", "-subject_hash_old", "-noout", "-in", cert.absolutePath,
    ).redirectErrorStream(true).start()
    val out = proc.inputStream.bufferedReader().use { it.readText() }.trim()
    proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
    // Only an 8-hex-char subject hash is accepted, so the value spliced into the device shell in
    // installCaOnDevice can't carry shell metacharacters.
    out.lineSequence().firstOrNull { it.matches(Regex("[0-9a-f]{8}")) }
  } catch (t: Throwable) {
    Console.error("[mitm-capture] openssl hash failed: ${t.message}")
    null
  }
}
