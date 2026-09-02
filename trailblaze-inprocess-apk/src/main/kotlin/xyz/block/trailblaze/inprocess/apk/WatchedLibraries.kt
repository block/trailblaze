package xyz.block.trailblaze.inprocess.apk

/**
 * A dex fact that bounds a library's version.
 *
 * The bound runs both ways, which is what makes a marker worth more than a presence check: if the
 * marker is defined the app is at or above [DexMarker.introducedIn]; if the library is present and
 * the marker is not, the app is below it.
 */
sealed interface DexMarker {
  /** The library version this marker first shipped in. */
  val introducedIn: String

  /** Human-readable form, quoted verbatim into [LibraryEra.evidence]. */
  val describe: String

  /** A class the library defines from [introducedIn] onward. */
  data class ClassDefined(
    val fqn: String,
    override val introducedIn: String,
  ) : DexMarker {
    override val describe: String get() = "class $fqn"
  }

  /** A method [classFqn] declares from [introducedIn] onward. */
  data class MethodDeclared(
    val classFqn: String,
    val method: String,
    override val introducedIn: String,
  ) : DexMarker {
    override val describe: String get() = "$classFqn#$method"
  }
}

/**
 * One library whose era decides whether an app can host the in-process shell.
 *
 * The list is short by design: it names the libraries that actually collide in a shared classloader
 * (docs/internal/devlog/2026-08-27-inprocess-passenger-apk-vision.md, "one process, one
 * classloader"), not every dependency an app has. It is a constructor parameter of [ApkProbe], so a
 * caller with a library of its own to watch supplies its own list rather than editing this one.
 *
 * **Every [boundaries] marker here was qualified against the published artifacts, not guessed.** A
 * candidate becomes a marker only once the class set of the release named in `introducedIn` contains
 * it and the class set of the release before it does not — checked by listing both jars, e.g.:
 *
 * ```
 * unzip -l kotlinx-coroutines-core-jvm-1.10.1.jar | grep GuidanceKt   # absent
 * unzip -l kotlinx-coroutines-core-jvm-1.10.2.jar | grep GuidanceKt   # present
 * ```
 *
 * That check needs the published artifacts, so it cannot run as an offline unit test;
 * `WatchedLibraryMarkerTest` pins the invariants that *are* checkable without them — a marker whose
 * package does not belong to its library can never be defined by that library, which is the typo
 * that would report every app as below the floor. A guessed marker is worse than none: it reports a
 * version the app does not have, and the floor check believes it.
 */
data class WatchedLibrary(
  /** Maven coordinate, used verbatim as [LibraryEra.library]. */
  val library: String,

  /**
   * `META-INF/<group>_<artifact>.version` entries that name this library.
   *
   * AGP packages one per AndroidX artifact and a few others do the same, so when they survive into
   * the APK the era is exact and nothing below has to run.
   */
  val versionFiles: List<String> = emptyList(),

  /** A class whose *definition* proves the app ships this library. */
  val presenceClass: String,

  /** Version boundaries readable from dex, for an APK that packages no `.version` file. */
  val boundaries: List<DexMarker> = emptyList(),

  /**
   * `.version` entries **in the shell** whose version becomes a floor on the app's copy.
   *
   * The shell's own packaged copy of a library runs against the *app's* classes, so whatever the
   * shell packages states a minimum for the app. The Compose case is the concrete one: an in-process
   * module packages `ui-test-junit4`, and a `ui-test` newer than the app's `ui` reaches for internals
   * that release added and dies inside the rule before the first step.
   *
   * Empty means the shell's bytes cannot state a floor for this library — either it is deduped out
   * of the shell (so only the declared floor covers it) or nothing about the shell constrains it.
   */
  val shellFloorVersionFiles: List<String> = emptyList(),
)

/**
 * The default watch list.
 *
 * Every entry is a library that can end up in the app's process from both sides at once, or whose
 * era the shell's own bytecode links against. Nothing app-specific belongs here — a caller with a
 * library of its own to watch passes its own list to [ApkProbe].
 */
val DEFAULT_WATCHED_LIBRARIES: List<WatchedLibrary> = listOf(
  WatchedLibrary(
    library = "androidx.compose.ui:ui",
    versionFiles = listOf("META-INF/androidx.compose.ui_ui.version"),
    presenceClass = "androidx.compose.ui.platform.AndroidComposeView",
    boundaries = listOf(
      DexMarker.ClassDefined("androidx.compose.ui.platform.ComposeViewContext", introducedIn = "1.11.0"),
      DexMarker.ClassDefined("androidx.compose.ui.autofill.FillableData", introducedIn = "1.10.0"),
    ),
    // `ui-test-junit4` is the artifact an in-process module packages, and its version IS the
    // statement about the app's Compose it needs. `ui` itself is listed second for a shell that
    // somehow packages Compose proper.
    shellFloorVersionFiles = listOf(
      "META-INF/androidx.compose.ui_ui-test-junit4.version",
      "META-INF/androidx.compose.ui_ui.version",
    ),
  ),
  WatchedLibrary(
    library = "androidx.compose.runtime:runtime",
    versionFiles = listOf("META-INF/androidx.compose.runtime_runtime.version"),
    presenceClass = "androidx.compose.runtime.Composer",
    boundaries = listOf(
      DexMarker.ClassDefined("androidx.compose.runtime.HostDefaultProvider", introducedIn = "1.11.0"),
      DexMarker.ClassDefined("androidx.compose.runtime.tooling.ComposeStackTrace", introducedIn = "1.10.0"),
    ),
  ),
  WatchedLibrary(
    library = "org.jetbrains.kotlinx:kotlinx-coroutines-core",
    versionFiles = listOf("META-INF/kotlinx_coroutines_core.version"),
    presenceClass = "kotlinx.coroutines.BuildersKt",
    // The boundary the in-process compile floor exists for: 1.11.0 replaced the Kotlin-facing
    // `runBlocking` entry point with `runBlockingK`, so code compiled against 1.11.0 throws
    // NoSuchMethodError on a 1.10.x runtime. Its presence is therefore both a version marker and
    // the exact linkage question the floor asks.
    boundaries = listOf(
      DexMarker.MethodDeclared("kotlinx.coroutines.BuildersKt", "runBlockingK", introducedIn = "1.11.0"),
      // 1.10.2 is the shell's floor exactly, and this class first appears in 1.10.2, so an APK that
      // packages no `.version` file can still be checked against the floor rather than reported as
      // undeterminable.
      DexMarker.ClassDefined("kotlinx.coroutines.GuidanceKt", introducedIn = "1.10.2"),
      DexMarker.ClassDefined("kotlinx.coroutines.DispatchException", introducedIn = "1.10.0"),
    ),
  ),
  WatchedLibrary(
    library = "org.jetbrains.kotlin:kotlin-stdlib",
    presenceClass = "kotlin.Unit",
  ),
  WatchedLibrary(
    library = "androidx.startup:startup-runtime",
    versionFiles = listOf("META-INF/androidx.startup_startup-runtime.version"),
    presenceClass = "androidx.startup.AppInitializer",
  ),
  WatchedLibrary(
    // Instrumentation-only, so the app is expected to ship none — "our copy is the only copy" is
    // bucket 1 of the 08-27 classpath analysis. Watched precisely so an app that *does* ship it
    // shows up as an overlap rather than as a mystery.
    library = "androidx.test.espresso:espresso-core",
    presenceClass = "androidx.test.espresso.Espresso",
  ),
  WatchedLibrary(
    library = "androidx.dynamicanimation:dynamicanimation",
    versionFiles = listOf("META-INF/androidx.dynamicanimation_dynamicanimation.version"),
    presenceClass = "androidx.dynamicanimation.animation.SpringAnimation",
    boundaries = listOf(
      DexMarker.ClassDefined("androidx.dynamicanimation.animation.FrameCallbackScheduler", introducedIn = "1.1.0"),
    ),
  ),
  WatchedLibrary(
    library = "androidx.tracing:tracing",
    versionFiles = listOf("META-INF/androidx.tracing_tracing.version"),
    presenceClass = "androidx.tracing.Trace",
  ),
  WatchedLibrary(
    library = "io.ktor:ktor-client-core",
    presenceClass = "io.ktor.client.HttpClient",
    boundaries = listOf(
      DexMarker.ClassDefined("io.ktor.client.plugins.sse.SSEBufferPolicy", introducedIn = "3.4.0"),
      DexMarker.ClassDefined("io.ktor.client.call.DelegatedCall", introducedIn = "3.2.0"),
    ),
  ),
)
