package xyz.block.trailblaze.android.test

import android.os.Build
import android.util.DisplayMetrics
import androidx.test.platform.app.InstrumentationRegistry
import java.util.TimeZone
import org.junit.runner.Description
import xyz.block.trailblaze.FileReadWriteUtil
import xyz.block.trailblaze.devices.TrailblazeAndroidDeviceCategory
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDeviceOrientation
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeScreenStateLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.rules.TrailblazeLoggingRule
import xyz.block.trailblaze.tracing.TraceLevel
import xyz.block.trailblaze.tracing.TrailblazeTracer
import xyz.block.trailblaze.util.Console

/**
 * Session logging for the in-process ANDROID_TEST driver.
 *
 * Writes each log, screenshot, and trace to `Download/trailblaze-logs` on the device — the
 * directory CI pulls off the emulator and hands to the report generator. Without a rule like this
 * a passing run leaves nothing behind but a JUnit verdict, and a failing one leaves nothing at all.
 *
 * A local counterpart to `:trailblaze-android`'s `TrailblazeAndroidLoggingRule` rather than a
 * reuse of it: that module carries Maestro and UiAutomator, which this driver deliberately keeps
 * out of an app's instrumentation harness. The two agree on the directory name because the CI
 * pull path is the contract between them.
 */
class AndroidTestLoggingRule(
  override val trailblazeDeviceInfoProvider: () -> TrailblazeDeviceInfo = { defaultDeviceInfo() },
  /**
   * The level to record this test at, or null for "not requested".
   *
   * Defaults to the instrumentation argument, which is how a CI lane or a Gradle invocation asks.
   * A harness that already knows what it wants can pass it directly instead of round-tripping
   * through an argument — and that is also what lets the apply-and-restore behavior be tested
   * without starting an instrumentation.
   */
  private val requestedTraceLevel: TraceLevel? = requestedTraceLevelFromArgs(),
) : TrailblazeLoggingRule(
  logsBaseUrl = AndroidTestInstrumentation.logsEndpoint(),
  writeLogToDisk = { sessionId: SessionId, log: TrailblazeLog ->
    writeToLogsDir(
      fileName = "${sessionId.value}_${log.timestamp.toEpochMilliseconds()}.json",
      bytes = TrailblazeJsonInstance
        .encodeToString(TrailblazeLog.serializer(), log)
        .toByteArray(),
      what = "log",
    )
  },
  writeScreenshotToDisk = { screenshot: TrailblazeScreenStateLog ->
    screenshot.screenState.screenshotBytes?.let { bytes ->
      writeToLogsDir(fileName = screenshot.fileName, bytes = bytes, what = "screenshot")
    }
  },
  writeTraceToDisk = { sessionId: SessionId, json: String ->
    writeToLogsDir(
      fileName = "${sessionId.value}-trace.json",
      bytes = json.toByteArray(),
      what = "trace",
    )
  },
) {

  /** The level to hand back after the test, or null when this run asked for nothing. */
  private var levelToRestore: TraceLevel? = null

  /**
   * Applies [requestedTraceLevel] for the duration of the test.
   *
   * The driver's fine-grained spans are `traceDetail`, so they record only at
   * [TraceLevel.VERBOSE] — and this instrumentation is a third process that the host's
   * `TRAILBLAZE_TRACE_LEVEL` never reaches. Without this hook the detail is unreachable in the
   * workflow the in-process driver actually runs in: a trail replayed on device would record its
   * tool spans and nothing underneath them, no matter what the run was asked for.
   *
   * Not `TrailblazeTracer.withLevel`, which brackets a block — the JUnit lifecycle hands us two
   * methods rather than one scope, so this saves and restores explicitly. One instrumentation runs
   * one test at a time, so there are no overlapping requests for `withLevel`'s counter to resolve.
   *
   * Applied before `super`, so the session start is traced at the requested level too, but with its
   * own restore on failure. [SimpleTestRule] calls this method outside the `try/finally` that runs
   * [afterTestExecution], so a throwing session start would otherwise leave the level applied for
   * the rest of the instrumentation — and the level is process-global, so every later test class in
   * the run inherits it.
   */
  override fun beforeTestExecution(description: Description) {
    requestedTraceLevel?.let { requested ->
      levelToRestore = TrailblazeTracer.level
      TrailblazeTracer.level = requested
    }
    try {
      super.beforeTestExecution(description)
    } catch (t: Throwable) {
      restoreLevel()
      throw t
    }
  }

  /**
   * Restores the level after the trace has been exported.
   *
   * After `super`, not before: the export is the last thing that reads this run's recording, and a
   * level put back early would apply to whatever the export itself records.
   */
  override fun afterTestExecution(description: Description, result: Result<Nothing?>) {
    try {
      super.afterTestExecution(description, result)
    } finally {
      restoreLevel()
    }
  }

  /** Idempotent, so whichever of the two paths above runs first is the one that restores. */
  private fun restoreLevel() {
    levelToRestore?.let { TrailblazeTracer.level = it }
    levelToRestore = null
  }

  companion object {
    /**
     * Instrumentation argument that sets the trace level for a run, named after the host system
     * property (`-Dtrailblaze.trace.level`) so the two spellings of the same knob match.
     *
     * An argument works here where it does not for the accessibility driver: this instrumentation
     * *is* the run, so the value is read once at the start of the process that does the work. The
     * on-device RPC server is reused across runs, which makes an argument read at instrumentation
     * start stale for every run after the first.
     *
     * ```bash
     * ./gradlew :app:connectedDebugAndroidTest \
     *   -Pandroid.testInstrumentationRunnerArguments.trailblaze.trace.level=verbose
     * ```
     */
    const val TRACE_LEVEL_ARG: String = "trailblaze.trace.level"

    /**
     * Reads [TRACE_LEVEL_ARG], or null when this run asked for nothing.
     *
     * Absent means null, which leaves the process level alone rather than pinning it to `normal` —
     * an app harness that configured the tracer itself keeps its setting.
     *
     * A value that is present but unreadable is a different thing, and gets said out loud plus the
     * documented `normal` fallback. Collapsing it into "not requested" is how someone spends an
     * afternoon wondering where their spans went after typing `verbise`. Same rule as the host's
     * `resolveTraceLevel`, so the two spellings of this knob behave alike.
     */
    private fun requestedTraceLevelFromArgs(): TraceLevel? =
      AndroidTestInstrumentation.stringArg(TRACE_LEVEL_ARG)?.let { raw ->
        TraceLevel.parse(raw) ?: TraceLevel.NORMAL.also {
          Console.log(
            "[TrailblazeTracer] ignoring unrecognized $TRACE_LEVEL_ARG \"$raw\" — " +
              "expected off, normal or verbose"
          )
        }
      }

    /** Must match what CI pulls — see `ATF_DIRECTORIES_TO_PULL` in the farm step scripts. */
    private const val LOGS_DIR = "trailblaze-logs"

    private fun writeToLogsDir(fileName: String, bytes: ByteArray, what: String) {
      try {
        FileReadWriteUtil.writeToDownloadsFile(
          context = InstrumentationRegistry.getInstrumentation().context,
          fileName = fileName,
          contentBytes = bytes,
          directory = LOGS_DIR,
        )
      } catch (e: Exception) {
        // Swallowed on purpose: a report artifact must never be the reason a trail fails.
        Console.log("Error writing $what to disk: ${e.message}")
      }
    }

    private fun displayMetrics(): DisplayMetrics =
      InstrumentationRegistry.getInstrumentation().context.resources.displayMetrics

    private fun smallestScreenWidthDp(): Int =
      InstrumentationRegistry.getInstrumentation().context.resources
        .configuration.smallestScreenWidthDp

    private fun deviceCategory(): TrailblazeAndroidDeviceCategory =
      if (smallestScreenWidthDp() >= 600) {
        TrailblazeAndroidDeviceCategory.TABLET
      } else {
        TrailblazeAndroidDeviceCategory.PHONE
      }

    /**
     * Broad-first, exactly as `TrailblazeAndroidOnDeviceClassifier` emits it: the platform, then
     * the form factor. This list IS how a unified trail picks a recording — it is joined into
     * `android-phone` and resolved up its lineage (`android-phone` → `android` → `all`). Dropping
     * the platform segment would make an `android:`-keyed recording unreachable and the step would
     * replay as if it had never been recorded.
     */
    fun defaultDeviceClassifiers(): List<TrailblazeDeviceClassifier> = listOf(
      TrailblazeDevicePlatform.ANDROID.asTrailblazeDeviceClassifier(),
      deviceCategory().asTrailblazeDeviceClassifier(),
    )

    fun defaultDeviceInfo(): TrailblazeDeviceInfo {
      val metrics = displayMetrics()
      val heightGreaterThanWidth = metrics.heightPixels > metrics.widthPixels
      return TrailblazeDeviceInfo(
        trailblazeDeviceId = TrailblazeDeviceId(
          instanceId = TrailblazeDriverType.ANDROID_TEST.name,
          trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
        ),
        trailblazeDriverType = TrailblazeDriverType.ANDROID_TEST,
        widthPixels = metrics.widthPixels,
        heightPixels = metrics.heightPixels,
        locale = InstrumentationRegistry.getInstrumentation().context.resources
          .configuration.locales.get(0).toLanguageTag(),
        orientation = when (deviceCategory()) {
          TrailblazeAndroidDeviceCategory.PHONE ->
            if (heightGreaterThanWidth) {
              TrailblazeDeviceOrientation.PORTRAIT
            } else {
              TrailblazeDeviceOrientation.LANDSCAPE
            }
          TrailblazeAndroidDeviceCategory.TABLET ->
            if (heightGreaterThanWidth) {
              TrailblazeDeviceOrientation.LANDSCAPE
            } else {
              TrailblazeDeviceOrientation.PORTRAIT
            }
        },
        classifiers = defaultDeviceClassifiers(),
        metadata = mapOf(
          "manufacturer" to Build.MANUFACTURER,
          "model" to Build.MODEL,
          "release" to Build.VERSION.RELEASE.toString(),
          "sdk_int" to Build.VERSION.SDK_INT.toString(),
          "timezone" to TimeZone.getDefault().id,
          "smallestScreenWidthDp" to smallestScreenWidthDp().toString(),
          "densityDpi" to metrics.densityDpi.toString(),
        ),
      )
    }
  }
}
