package xyz.block.trailblaze.android.test

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import xyz.block.trailblaze.android.test.hierarchy.ForeignWindow

/**
 * Launching the app under test by intent — the launch every in-process harness needs and none
 * should have to write: the per-test `@Before` in [LaunchedAppTrailblazeTest], and the mid-trail
 * relaunch [tools.MaestroCommandAdapters] performs when a recorded `launchApp` targets the app
 * under test. One implementation so the "wait for a DIFFERENT resumed Activity" subtlety below
 * cannot drift between callers.
 *
 * Entirely app-agnostic: the package is the instrumentation's own target package, and the entry
 * point is that package's launcher Activity. An app with no launcher entry point (or one that must
 * be launched some other way) writes its own `@Before` against [awaitResumedActivity] instead.
 */
object AppUnderTestLauncher {

  /** Whether this process has launched the app yet — the first launch is a real cold start. */
  @Volatile
  var hasLaunched: Boolean = false
    private set

  /**
   * True from this process's first launch until a tool that may move the screen runs (anything
   * but `launchApp`, a read, a broadcast or a permission grant). The screen is then the entry
   * point on whatever data the process started with, so a trailhead's restart or reinstall launch
   * would only repeat the cold start the instrumentation just paid for.
   */
  @Volatile
  var untouchedSinceColdLaunch: Boolean = false
    private set

  /**
   * True once a UI tool other than `launchApp` acted on the app in this process, so its data may
   * have changed since the host cleared it. Setup tools that do not touch the app's UI leave it.
   */
  @Volatile
  var usedSinceProcessStart: Boolean = false
    private set

  /**
   * Set by a harness that leaves this process's first launch to the first tool that needs the app
   * (see [launchIfDeferred]). Off by default: a harness that launches its Activity some other way
   * must not have the launcher Activity started under it.
   */
  @Volatile
  var launchOnFirstUse: Boolean = false

  /**
   * Launches the app when [launchOnFirstUse] deferred its first launch and nothing has made it.
   * A screen the trail opened before its first UI step is adopted as that launch instead, since
   * starting the entry point with CLEAR_TASK would throw it away: one of this app's Activities
   * already resumed (a deep link over adb), or another app in front (Settings, opened over adb).
   * The home screen and the system UI are not such a screen. A host-driven run starts from the
   * home screen (the host shows it before a fresh run), so another app in front is one it opened.
   */
  fun launchIfDeferred() {
    if (!launchOnFirstUse || hasLaunched) return
    if (resumedActivityOrNull() != null || anotherAppWasOpened()) hasLaunched = true else launchAppUnderTest()
  }

  /** True when an app other than this one, the home screen or the system UI is in front. */
  private fun anotherAppWasOpened(): Boolean {
    val front = ForeignWindow.frontPackage() ?: return false
    val packageManager = InstrumentationRegistry.getInstrumentation().targetContext.packageManager
    val home = packageManager
      .resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
      ?.activityInfo?.packageName
      // Unresolvable (package visibility hides it) or ambiguous (the chooser, "android"): which
      // window is home is unknown, so launch as a fresh run would.
      ?.takeIf { it != "android" }
      ?: return false
    return front != home && front != SYSTEM_UI_PACKAGE
  }

  /**
   * Written to the app's own no-backup dir by an explicit `launchApp` and by the first UI tool that
   * acts on the app. `pm clear` deletes it, so its absence proves the data was cleared since then.
   * On disk rather than in memory so it outlives a process restart (a broadcast the app answers by
   * restarting): a REINSTALL launch in the next process must still see the data was used. The
   * instrumentation's own cold launch does not write it; that start is on the host-cleared data.
   */
  fun freshDataMarker(): java.io.File =
    java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir, FRESH_DATA_MARKER)

  /** Records that the app has run on its current data; see [freshDataMarker]. */
  fun markDataUsed() {
    val marker = freshDataMarker()
    marker.parentFile?.mkdirs()
    marker.createNewFile()
  }

  /** A UI tool other than `launchApp` acted on the app; see [usedSinceProcessStart]. */
  fun markUsed() {
    if (usedSinceProcessStart) return
    usedSinceProcessStart = true
    markDataUsed()
  }

  /** A tool that may move the screen ran, so the app may no longer be at its fresh entry point. */
  fun markTouched() {
    untouchedSinceColdLaunch = false
  }

  /**
   * Launches (or relaunches) the app under test at its entry point and waits for the launched
   * Activity to reach RESUMED.
   *
   * [clearTask] (the default) adds CLEAR_TASK so the app starts from its entry point rather than
   * whatever is on the back stack. `startActivity` is async, so "an Activity is RESUMED" is
   * already true for whatever is on screen NOW — the wait is for a DIFFERENT instance than the one
   * resumed at call time, or it returns immediately and the next step runs against the old screen.
   *
   * `clearTask = false` is a warm resume — the launcher intent brings the app's existing task
   * forward without recreating it (Maestro `launchApp` with `stopApp: false`). There the CURRENT
   * resumed Activity is a legitimate outcome (the app may already be frontmost, or the task comes
   * back with the same instance on top), so the wait accepts any resumed Activity.
   */
  fun launchAppUnderTest(clearTask: Boolean = true) {
    val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    initializeOkHttpAndroidPlatform(targetContext.applicationContext)
    val beforeLaunch = if (clearTask) resumedActivityOrNull() else null
    val coldLaunch = !hasLaunched
    targetContext.startActivity(entryPointIntent(targetContext, clearTask))
    awaitResumedActivity(otherThan = beforeLaunch)
    hasLaunched = true
    if (coldLaunch) untouchedSinceColdLaunch = true
  }

  private fun entryPointIntent(targetContext: Context, clearTask: Boolean): Intent {
    val packageName = targetContext.packageName
    val launchIntent =
      checkNotNull(targetContext.packageManager.getLaunchIntentForPackage(packageName)) {
        "No launcher Activity for '$packageName'. The instrumentation is running inside the app " +
          "under test (android:targetPackage), so this is the app's own package — an app with no " +
          "launcher entry point needs a harness that launches the right Activity itself."
      }
    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (clearTask) launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
    return launchIntent
  }

  /**
   * The currently resumed Activity, waiting for one to exist.
   *
   * Polled rather than taken from an `ActivityScenario`: the app is launched by intent, so no
   * scenario owns it, and the resumed Activity can change mid-trail as the trail navigates.
   *
   * [otherThan] rejects one known instance, so a caller that just asked for a launch waits for the
   * Activity that launch produces rather than accepting the one already on screen. Callers reading
   * the CURRENT Activity mid-trail pass nothing.
   *
   * [yieldToAnotherApp] stops waiting with [AnotherAppInFrontException] once another app's window
   * is in front: the app's Activity stays paused behind it, so the wait could only time out. A
   * target reading the screen mid-trail passes it, and a launch, whose wait starts with the
   * launcher still in front, does not.
   */
  fun awaitResumedActivity(otherThan: Activity? = null, yieldToAnotherApp: Boolean = false): Activity {
    val deadline = System.currentTimeMillis() + LAUNCH_TIMEOUT_MS
    while (true) {
      resumedActivityOrNull()?.takeIf { it !== otherThan }?.let { return it }
      if (yieldToAnotherApp) ForeignWindow.frontPackage()?.let { throw AnotherAppInFrontException(it) }
      check(System.currentTimeMillis() < deadline) {
        "No Activity reached RESUMED within ${LAUNCH_TIMEOUT_MS}ms of launching the app under test."
      }
      Thread.sleep(POLL_MS)
    }
  }

  /**
   * Waits until a resumed Activity observes [locale]. The Activity may be either a replacement
   * created for the configuration change or the same instance when the app handles `locale` in
   * `android:configChanges`.
   */
  internal fun awaitActivityWithLocale(
    locale: String,
    activityProvider: () -> Activity? = ::resumedActivityOrNull,
  ): Activity {
    val expectedLocale = Locale.forLanguageTag(locale).toLanguageTag()
    val deadline = System.currentTimeMillis() + LAUNCH_TIMEOUT_MS
    while (true) {
      activityProvider()?.let { activity ->
        val actualLocale = activity.resources.configuration.locales[0].toLanguageTag()
        if (actualLocale.equals(expectedLocale, ignoreCase = true)) return activity
      }
      check(System.currentTimeMillis() < deadline) {
        "No resumed Activity observed device locale `$expectedLocale` within ${LAUNCH_TIMEOUT_MS}ms."
      }
      Thread.sleep(POLL_MS)
    }
  }

  /**
   * The resumed Activity, waiting up to [timeoutMs], or null if none arrives in that budget.
   *
   * For callers that must answer on a deadline rather than block for the full launch timeout —
   * the RPC screen-state captor, which serves the host's readiness polling and must report "not
   * ready" instead of parking a request thread while the app is between Activities.
   */
  fun resumedActivityWithin(timeoutMs: Long): Activity? {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
      val remaining = deadline - System.currentTimeMillis()
      if (remaining <= 0) return null
      // NOT resumedActivityOrNull(): that uses `runOnMainSync`, which blocks until the main thread
      // gets to it. A wedged or ANR-ing main thread is exactly the case this bounded read exists
      // for, and there `runOnMainSync` never returns — the deadline would never be consulted and
      // the calling Ktor worker would be lost for good.
      resumedActivityOrNullWithin(remaining)?.let { return it }
      if (System.currentTimeMillis() >= deadline) return null
      Thread.sleep(POLL_MS)
    }
  }

  /**
   * [resumedActivityOrNull] with a ceiling on how long the main thread may take to answer.
   *
   * Null is ambiguous by design — "no Activity is RESUMED" and "the main thread did not answer in
   * time" are the same answer to both callers: [resumedActivityWithin], which reports not-ready
   * either way, and the default-locale probe, which skips the wait either way.
   *
   * Same post-and-await shape as [onMainThreadForCapture], kept separate because the contracts
   * differ: caller-supplied budget and null on expiry here, fixed deadline and a thrown not-ready
   * there. A hang fix in one deserves a look at the other.
   */
  internal fun resumedActivityOrNullWithin(budgetMs: Long): Activity? {
    val answered = CountDownLatch(1)
    val resumed = AtomicReference<Activity?>(null)
    Handler(Looper.getMainLooper()).post {
      resumed.set(
        ActivityLifecycleMonitorRegistry.getInstance()
          .getActivitiesInStage(Stage.RESUMED)
          .firstOrNull(),
      )
      answered.countDown()
    }
    return if (answered.await(budgetMs, TimeUnit.MILLISECONDS)) resumed.get() else null
  }

  /** The resumed Activity right now, or null if none is. `getActivitiesInStage` needs the main thread. */
  fun resumedActivityOrNull(): Activity? {
    var resumed: Activity? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      resumed =
        ActivityLifecycleMonitorRegistry.getInstance()
          .getActivitiesInStage(Stage.RESUMED)
          .firstOrNull()
    }
    return resumed
  }

  /**
   * Runs OkHttp's `androidx.startup` initializer by hand, giving its platform the application
   * Context. No-op when the app does not ship OkHttp.
   *
   * The observed failure this exists for is real: 2026-08-27, a large app under this driver died in
   * its Activity's `onCreate` with `IOException: Platform applicationContext not initialized`,
   * because it reads OkHttp's platform while building its DI graph. **Its recorded explanation was
   * wrong.** That explanation — that an instrumented process does not install the app's
   * ContentProviders, so `androidx.startup` never runs — was refuted by measurement on 2026-08-31:
   * providers DO install under instrumentation, and the app's declared initializers run at process
   * bind, in dependency order, before the first line of test code (API 34 and API 36).
   *
   * So this stays as a direct initialization that bypasses `androidx.startup` discovery entirely,
   * for the case where discovery in the app's process is broken rather than absent — a duplicated
   * `androidx.startup:startup-runtime` in the attached test APK is the leading candidate, since its
   * copy is the one that loads and its discovery resolves a marker string through an R constant
   * baked against the wrong resource table. It logs when it fires so the next occurrence — or
   * non-occurrence — is attributable from logcat rather than re-diagnosed from scratch. Reflective
   * because OkHttp is the app's dependency, not this module's.
   */
  private fun initializeOkHttpAndroidPlatform(context: Context) {
    val initializer =
      try {
        Class.forName("okhttp3.internal.platform.PlatformInitializer")
      } catch (e: ClassNotFoundException) {
        // No OkHttp in the runtime at all — nothing to initialize, and nothing that can fail later.
        // Logged, not silent: both exits have to speak, or a logcat with no line from this method
        // can't tell "the app ships no OkHttp" from "the launcher never got here".
        Log.i(TAG, "No okhttp3.internal.platform.PlatformInitializer in this runtime — nothing to initialize.")
        return
      }
    initializer
      .getMethod("create", Context::class.java)
      .invoke(initializer.getDeclaredConstructor().newInstance(), context)
    Log.i(
      TAG,
      "Initialized OkHttp's platform directly (${initializer.name}). This bypasses androidx.startup " +
        "discovery; the app's own InitializationProvider is expected to have run it already.",
    )
  }

  // Generous: a big app's cold start on a farm emulator's first launch includes dex/AOT warmup
  // and can need several times the 30s a sample app does. The ceiling only costs time when the
  // launch has genuinely failed.
  private const val FRESH_DATA_MARKER = ".trailblaze-in-process-launched"
  private const val LAUNCH_TIMEOUT_MS = 120_000L
  private const val POLL_MS = 100L
  private const val TAG = "AppUnderTestLauncher"
  private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
}

/**
 * Another app's window came to the front while this process waited for the app's Activity: a
 * browser the app opened, a permission prompt, a settings screen a trail started. The screen is
 * read through that window instead; see [ForeignWindow.screenState].
 */
class AnotherAppInFrontException(val frontPackage: String) :
  IllegalStateException("$frontPackage's window is in front of the app under test")
