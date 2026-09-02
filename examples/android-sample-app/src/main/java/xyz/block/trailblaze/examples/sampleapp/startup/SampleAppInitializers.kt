package xyz.block.trailblaze.examples.sampleapp.startup

import android.content.Context
import androidx.startup.Initializer
import xyz.block.trailblaze.examples.sampleapp.R

/**
 * The sample app's `androidx.startup` initializers: two of them, with a dependency edge, because
 * that is the smallest fixture that can tell "the initializers ran" from "the initializers ran in
 * dependency order".
 *
 * They exist as a **tripwire for the in-process (`ANDROID_TEST`) driver**, which replays trails
 * inside this app's own process under instrumentation. Measured 2026-08-31 on API 34 and API 36:
 * an instrumented process DOES install the app under test's ContentProviders, so
 * `androidx.startup.InitializationProvider` runs and initializes both of these at process bind,
 * before the first test line — the same as a normal launch.
 *
 * What the tripwire guards is that staying true. [SampleAppGreetingInitializer]'s value is
 * load-bearing — [SampleAppStartupState.greeting] has no fallback and the app's Activity reads it
 * in `onCreate` — so if startup discovery in this process ever breaks (a duplicated
 * `startup-runtime` in the test APK hijacking discovery is the known candidate), the sample app
 * dies with a directed message on the in-process lane instead of some other app dying
 * mysteriously somewhere else.
 */
class SampleAppBaseInitializer : Initializer<Unit> {

  override fun create(context: Context) {
    StartupInitializerRecord.record(NAME)
    SampleAppStartupState.baseInitialized = true
  }

  override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()

  companion object {
    const val NAME: String = "base"
  }
}

/** Depends on [SampleAppBaseInitializer], and produces the greeting the Activity reads. */
class SampleAppGreetingInitializer : Initializer<Unit> {

  override fun create(context: Context) {
    check(SampleAppStartupState.baseInitialized) {
      "SampleAppGreetingInitializer ran before SampleAppBaseInitializer, which it declares as a " +
        "dependency. Whatever ran these initializers ignored androidx.startup's dependency graph."
    }
    StartupInitializerRecord.record(NAME)
    SampleAppStartupState.greeting = context.getString(R.string.app_name)
  }

  override fun dependencies(): List<Class<out Initializer<*>>> =
    listOf(SampleAppBaseInitializer::class.java)

  companion object {
    const val NAME: String = "greeting"
  }
}

/** State the initializers above produce and the app then depends on. */
object SampleAppStartupState {

  @Volatile
  @JvmStatic
  var baseInitialized: Boolean = false

  @Volatile
  @JvmStatic
  var greeting: String? = null

  /**
   * The greeting, or a directed failure naming the startup path.
   *
   * Read from the app's `onCreate`, where a real app reads its DI graph. Both a normal launch and
   * an instrumented one run the app's initializers from `InitializationProvider`, so this never
   * fires today — if it does, initializer discovery in this process is broken and that is the
   * finding, not a missing harness step.
   */
  @JvmStatic
  fun requireGreeting(): String = checkNotNull(greeting) {
    "SampleAppStartupState.greeting was never set, so this app's androidx.startup initializers " +
      "did not run. InitializationProvider runs them at process bind on both a normal launch and " +
      "an instrumented one (measured 2026-08-31, API 34 + 36), so startup discovery in this " +
      "process is broken — a duplicated androidx.startup:startup-runtime in an attached test APK " +
      "is the known candidate."
  }
}
