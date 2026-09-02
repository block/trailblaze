package xyz.block.trailblaze.inprocess.apk

import kotlinx.serialization.Serializable

/**
 * The minimum library era an app must ship for the shell's own code to link against it.
 *
 * Direction matters and only runs one way. The shell is compiled and packaged against the OLDEST app
 * era it attaches to; a newer app is safe (these libraries keep binary compatibility forward), an
 * older app is not — its copy wins in the shared classloader and the shell's call sites die with
 * `NoSuchMethodError`/`NoClassDefFoundError` before the first step.
 *
 * A floor has two sources, and the probe uses both:
 *
 * 1. **Read off the shell APK** — every `.version` file the shell packages for a watched library
 *    states a minimum on the app's copy, because the shell's packaged code runs against the app's
 *    classes. Self-updating, no constant to drift, and the reason `--shell` is load-bearing for more
 *    than the dex intersection.
 * 2. **Declared** — the libraries the shell *links against* but does not package, because the build
 *    dedups them out in favour of the app's copy. Nothing in the shell's bytes records the version
 *    those call sites were compiled against, so it has to be stated. [DEFAULT_DECLARED_SHELL_FLOOR]
 *    is that statement; `--shell-floor <yaml>` replaces it.
 */
@Serializable
data class ShellFloor(
  val libraries: List<LibraryFloor> = emptyList(),
) {
  fun minVersionFor(library: String): LibraryFloor? = libraries.firstOrNull { it.library == library }

  /** This floor plus [other]'s entries; [other] wins on a library both name. */
  fun mergedWith(other: ShellFloor): ShellFloor {
    val byLibrary = libraries.associateBy { it.library }.toMutableMap()
    other.libraries.forEach { byLibrary[it.library] = it }
    return ShellFloor(byLibrary.values.sortedBy { it.library })
  }
}

@Serializable
data class LibraryFloor(
  val library: String,
  val minVersion: String,
  /** Why this floor exists, quoted into the failure when an app falls below it. */
  val why: String,
)

/**
 * The floor for libraries whose minimum the shell's own bytes cannot state usefully.
 *
 * Two kinds qualify: a library the shell links against but does not package (nothing in its bytes
 * records the era those call sites compiled against), and a library whose packaged `.version` file
 * is more precise than any dex evidence can answer — see the Compose entry below.
 *
 * Values come from `gradle/inprocess-compile-floor.gradle`, which is the repo's single statement of
 * "the oldest era we attach to" — see that file's header for why each version is there. Ktor
 * deliberately has no entry: that file is explicit that nothing about the app constrains its
 * version, and the shell packages its own copy.
 *
 * A drift guard lives in the internal build, where that Groovy file is: an OSS checkout has no copy
 * of it to compare against.
 */
val DEFAULT_DECLARED_SHELL_FLOOR: ShellFloor = ShellFloor(
  libraries = listOf(
    LibraryFloor(
      library = "org.jetbrains.kotlinx:kotlinx-coroutines-core",
      minVersion = "1.10.2",
      why = "coroutines 1.11.0 replaced the Kotlin-facing runBlocking entry point with runBlockingK " +
        "and moved interface-member default accessors onto the interface, so shell code compiled " +
        "against 1.11.0 dies with NoSuchMethodError on an older runtime",
    ),
    // Stated here rather than read off the shell's own
    // `META-INF/androidx.compose.runtime_runtime.version`, which says 1.11.4, because a floor is
    // only useful at a precision an APK's bytes can be tested against. Compose publishes identical
    // class sets across a minor line — runtime 1.11.0 and 1.11.4 both define the same 764 classes,
    // and ui 1.11.0 and 1.11.4 the same 1679 — so no dex marker can ever bound an app past 1.11.0.
    // A 1.11.4 floor would therefore report ERA_UNDETERMINABLE for every app that packages no
    // Compose `.version` file, including ones on a perfectly fine 1.11.x. 1.11.0 is the strongest
    // floor the dex can actually answer, and it catches what matters: an app still on 1.10.x, which
    // before this entry cleared with no era reason at all.
    LibraryFloor(
      library = "androidx.compose.runtime:runtime",
      minVersion = "1.11.0",
      why = "an in-process shell packages Compose 1.11.4 tooling whose code runs against the app's " +
        "runtime, and the 1.11 line is the oldest era those call sites link on",
    ),
  ),
)

/**
 * Compares dotted version strings numerically, segment by segment, then puts a pre-release below the
 * release with the same numbers.
 *
 * Deliberately not a full semver implementation. Every version this compares is an Android library
 * release (`1.10.2`, `1.11.4`, `1.9.0-beta01`), and the only question asked of it is "is this at
 * least that" — ordering two pre-releases of one version against each other never decides a floor.
 */
internal object LibraryVersions {

  /** Negative if [a] < [b], zero if equal, positive if [a] > [b]. */
  fun compare(a: String, b: String): Int {
    val left = segments(a)
    val right = segments(b)
    for (i in 0 until maxOf(left.size, right.size)) {
      val l = left.getOrElse(i) { 0 }
      val r = right.getOrElse(i) { 0 }
      if (l != r) return l.compareTo(r)
    }
    // Same numbers: a pre-release sorts below the release it leads up to. Only the presence of a
    // qualifier is read, not its text — `alpha01` vs `beta01` never decides a floor, but
    // "1.11.0-alpha01 satisfies a floor of 1.11.0" does, and it is false: an alpha is missing what
    // the release added.
    return preRelease(b).compareTo(preRelease(a))
  }

  private fun preRelease(version: String): Int =
    if (version.contains('-') || version.contains('+')) 1 else 0

  fun atLeast(version: String, floor: String): Boolean = compare(version, floor) >= 0

  private fun segments(version: String): List<Int> =
    version.trim()
      .substringBefore('-')
      .substringBefore('+')
      .split('.')
      .map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
}
