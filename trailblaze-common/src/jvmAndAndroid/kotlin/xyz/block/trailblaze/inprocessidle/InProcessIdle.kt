package xyz.block.trailblaze.inprocessidle

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Not the feature — the shared *facts* every participant in the in-process idle detector must agree
 * on: the localhost port the detector serves, the `PING`/`PONG` liveness exchange, the detector's
 * package naming convention, the asset path its APK is staged at, the instrumentation component that
 * attaches it, and the sysprop that turns the settle race on. The behavior lives in
 * [InProcessIdleAttacher], [InProcessIdleLaunchReattacher] and [InProcessIdleSettleClient].
 *
 * One home because the writer and the readers are separate objects — [InProcessIdleAttacher]
 * deploys the detector and writes the sysprop, [InProcessIdleLaunchReattacher] re-attaches it
 * around `launchApp`, and [InProcessIdleSettleClient] reads the sysprop and speaks the `AWAIT_IDLE`
 * half of the same protocol. A port, package-name or sysprop change that lands in only some of them
 * doesn't fail loudly; it silently drops the run back to heuristic settle speed.
 *
 * Shared by the on-device participants and by the host: `InProcessIdleApkInstaller` installs the
 * same build over adb and writes the same sysprop, so it has to agree on all of this too. That
 * is why this lives in the shared module rather than beside the Android-only behavior.
 *
 * Public rather than internal so a test module that flips the sysprop itself — an A/B baseline leg
 * forcing the race off, say — names it from here instead of re-typing the string.
 */
object InProcessIdle {

  /** Localhost port the detector's server binds inside the target app's process. */
  const val PORT = 7777

  /** Detector package prefix; see [packageFor]. */
  const val PACKAGE_PREFIX = "xyz.block.trailblaze.inprocessidle."

  /** The bare-`Instrumentation` component `am instrument` starts to attach the detector. */
  const val INSTRUMENTATION_CLASS = "xyz.block.trailblaze.inprocessidle.InProcessIdleInstrumentation"

  /**
   * Per-device on/off switch for the settle race, written by the attach and read per settle gate.
   * A sysprop rather than an env var because the reader runs on-device with no host env to inherit.
   */
  const val SETTLE_SYSPROP = "debug.trailblaze.settle.inProcessIdle"

  /** Read bound on a `PING` — the detector answers immediately or not at all. */
  private const val PING_READ_TIMEOUT_MS = 2_000

  /**
   * The one label that identifies a detector build: the target applicationId's last dotted segment
   * (`com.example.app` -> `app`). [packageFor] and [assetPathFor] both derive from this, so the
   * package the attach installs and the asset it installs it from can never name different builds.
   *
   * Consequence worth knowing before adding a target: two applicationIds sharing a last label
   * (`com.a.pos` and `com.b.pos`) collapse to the same flavor, so they cannot have distinct
   * detectors. Only one detector can serve a device at a time anyway ([PORT] is shared), but a
   * bundle staging both would silently install one build for the other's appId.
   */
  fun flavorFor(appId: String): String = appId.substringAfterLast('.')

  /**
   * Detector package for a target applicationId: [flavorFor] appended to [PACKAGE_PREFIX] (e.g.
   * `com.example.app` -> `xyz.block.trailblaze.inprocessidle.app`). The detector APK build uses the
   * same convention, so the two agree without configuration.
   */
  fun packageFor(appId: String): String = PACKAGE_PREFIX + flavorFor(appId)

  /**
   * Where a test APK stages the detector build for a target applicationId, relative to its own
   * assets. Written by the `inProcessIdle { }` block of the Trailblaze Android Gradle plugin and
   * read by [InProcessIdleAttacher].
   */
  fun assetPathFor(appId: String): String = "inprocess-idle-apks/trailblaze-inprocess-idle-${flavorFor(appId)}.apk"

  /**
   * Where the CLI binary bundles the detector build for a target applicationId, as a classpath
   * resource. The host-side attach installs from here over adb; a test APK installs the same build
   * from its own assets ([assetPathFor]). Both derive from [flavorFor], so the two delivery
   * mechanisms can never name different builds.
   *
   * A build that bundles no detector for an appId simply has no such resource, which is how "this
   * app cannot be turbo here" reads as an absence rather than an error.
   */
  fun cliResourcePathFor(appId: String): String =
    "/apks/inprocess-idle/trailblaze-inprocess-idle-${flavorFor(appId)}.apk"

  /**
   * Whether `am instrument`'s output says the attach failed.
   *
   * `am instrument` without `-w` exits zero immediately, so a rejected attach — an unresolvable
   * component, or the signature mismatch that is by far the most common cause — shows up only as
   * an error line in its output. Every participant that starts an attach has to read the same
   * thing, or one of them reports success on a detector that never came up.
   */
  fun amInstrumentReportedFailure(output: String): Boolean =
    output.contains("Exception", ignoreCase = true) || output.contains("unable", ignoreCase = true)

  /**
   * One `PING` exchange with whatever holds [PORT]. Returns the reply line (`PONG <appId>` from an
   * attached detector) or null when nothing answers.
   *
   * Never logs: "nothing answers" is the normal state before an attach, and the callers that do
   * care about a failure say so with their own context. Catches `Exception` and not `Throwable` on
   * purpose — a probe that reads a null must mean "the detector isn't there", so an `Error` has to
   * keep propagating rather than be reported as an absent detector.
   *
   * [port] is injectable for tests only; every caller uses the default. A test that bound [PORT]
   * itself would collide with a real detector, and with any other test on the same agent.
   *
   * `@JvmOverloads` keeps the shorter signatures on the class after [readTimeoutMs] was added, so
   * a Java caller compiled against `ping(int, int)` still links. This module is published; Kotlin
   * callers bind to the synthetic `ping$default`, which no annotation can pin, so they still
   * recompile — which they do, since the detector APK this talks to ships from the same build.
   */
  @JvmOverloads
  fun ping(
    connectTimeoutMs: Int,
    port: Int = PORT,
    readTimeoutMs: Int = PING_READ_TIMEOUT_MS,
  ): String? = try {
    Socket().use { socket ->
      socket.connect(InetSocketAddress("127.0.0.1", port), connectTimeoutMs)
      // Two separate bounds, because a detector that ACCEPTS the connection and then never
      // replies is a different failure from one that isn't listening — and only the read bound
      // caps it. A caller on a latency-sensitive path passes its own, so `connectTimeoutMs`
      // alone is never mistaken for the probe's total cost.
      socket.soTimeout = readTimeoutMs
      socket.getOutputStream().apply {
        write("PING\n".toByteArray())
        flush()
      }
      BufferedReader(InputStreamReader(socket.getInputStream())).readLine()
    }
  } catch (e: Exception) {
    null
  }
}
