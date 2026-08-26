package xyz.block.trailblaze.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Regression guard for blank screenshots in the release desktop app (block/trailblaze#194).
 *
 * The release/Homebrew uber JAR is ProGuard-shrunk (`-Ptrailblaze.proguard=true`); source and dev
 * builds are not, which is why this only ever reproduced from a published binary. Coil's
 * `RealImageLoader` runs `addServiceLoaderComponents()` on every `ImageLoader` construction, which
 * does a `ServiceLoader.load(FetcherServiceLoaderTarget::class.java, ...)`. `coil-network-ktor3`
 * ships a `META-INF/services/coil3.util.FetcherServiceLoaderTarget` naming
 * `KtorNetworkFetcherServiceLoaderTarget` — an `internal` class that nothing but that services file
 * references, so the shrinker deletes the class while `-adaptresourcefilecontents` leaves the
 * services file still naming it. `ServiceLoader` then throws `ServiceConfigurationError`, and
 * because that propagates out of the lazy `fetcherFactories` flatMap it takes down the ENTIRE
 * fetcher list — including `FileUriFetcher`, so the desktop app's purely local
 * `file:///…/logs/<session>/<shot>.webp` loads fail too. `RealImageLoader.execute` catches
 * Throwable and returns an `ErrorResult`, so every session screenshot pane renders blank with no
 * error surfaced anywhere. Trail Runner was unaffected because it loads screenshots as plain
 * `<img src="/static/…">` and never touches Coil.
 *
 * A runtime "load an image" test can't guard this: unit tests run against the intact (non-shrunk)
 * classes, so they pass with or without the keep. The only thing that regresses is the ProGuard
 * ruleset, so that's what this asserts — the same posture and reasoning as
 * [xyz.block.trailblaze.quickjs.tools.QuickJsProguardKeepRegressionTest], which guards the
 * identical failure shape for quickjs-kt.
 *
 * Downstream release builds `-include` this ProGuard file, so this single keep covers every release
 * JAR built from it; this test verifies that file, the shared source of truth.
 */
class CoilProguardKeepRegressionTest {

  @Test
  fun `ProGuard rules keep coil3 classes and their members`() {
    val rulesFile = locateProguardRules()
    assertTrue(
      hasActiveCoilKeep(rulesFile.readText()),
      "${rulesFile.path} is missing a keep for coil3. Without " +
        "`-keep class coil3.** { *; }`, ProGuard deletes the ServiceLoader-discovered " +
        "`KtorNetworkFetcherServiceLoaderTarget` while leaving the META-INF/services file that " +
        "names it, ServiceLoader throws ServiceConfigurationError, and Coil ends up with NO " +
        "fetchers at all — so every screenshot in the desktop app's session views and UI " +
        "Inspector renders as a blank pane. See block/trailblaze#194 before removing this keep.",
    )
  }

  @Test
  fun `ProGuard rules keep the ServiceLoader target implementations`() {
    val rules = activeRules(locateProguardRules().readText())
    listOf("FetcherServiceLoaderTarget", "DecoderServiceLoaderTarget").forEach { target ->
      assertTrue(
        Regex("""-keep\s+class\s+\*\s+implements\s+coil3\.util\.$target\s*\{\s*\*;\s*}""")
          .containsMatchIn(rules),
        "Missing `-keep class * implements coil3.util.$target { *; }`. The `coil3.**` package " +
          "keep covers Coil's own bundled providers, but a provider contributed from any other " +
          "package would still be shrunk away and poison the whole component list.",
      )
    }
  }

  @Test
  fun `keep detector matches an active rule and rejects a commented or absent one`() {
    val activeKeep = "-keep class coil3.** { *; }"
    assertTrue(hasActiveCoilKeep(activeKeep), "a live keep line should match")
    assertTrue(
      hasActiveCoilKeep("  -keep  class   coil3.**  {  *;  }  "),
      "whitespace/formatting variation should still match",
    )
    assertTrue(
      hasActiveCoilKeep("$activeKeep # keep for ServiceLoader fetchers"),
      "a trailing inline comment must not hide an otherwise-active rule",
    )
    // The regression the comment-stripping defends against: a commented-out keep is inert to
    // ProGuard, so the detector must NOT treat it as present.
    assertFalse(hasActiveCoilKeep("# $activeKeep"), "a fully commented-out keep must not match")
    assertFalse(
      hasActiveCoilKeep("   #   $activeKeep"),
      "an indented commented-out keep must not match",
    )
    assertFalse(hasActiveCoilKeep("-keep class org.other.** { *; }"), "an absent keep must not match")
    // Keeping only the class shells would still let the shrinker strip members.
    assertFalse(
      hasActiveCoilKeep("-keep class coil3.** "),
      "a class-only keep without `{ *; }` must not satisfy the guard",
    )
  }

  private companion object {
    // The keep must retain the classes AND their members (`*;`). Tolerant of whitespace and
    // formatting so this isn't brittle to a reformat.
    private val KEEP_REGEX = Regex("""-keep\s+class\s+coil3\.\*\*\s*\{\s*\*;\s*}""")

    /** Drops comment lines, which are inert to ProGuard, before matching. */
    fun activeRules(rulesText: String): String =
      rulesText.lineSequence().filterNot { it.trimStart().startsWith("#") }.joinToString("\n")

    /** True iff [rulesText] has an active (non-commented) `-keep class coil3.** { *; }`. */
    fun hasActiveCoilKeep(rulesText: String): Boolean =
      KEEP_REGEX.containsMatchIn(activeRules(rulesText))
  }

  private fun locateProguardRules(): File {
    // Walk up from cwd, same pattern as QuickJsProguardKeepRegressionTest. Gradle runs tests with
    // cwd at the module dir, and :trailblaze-desktop is this module's sibling, so the first hit on
    // the way up is the right file regardless of where the checkout root sits.
    val repoRelativePath = "trailblaze-desktop/proguard-rules.pro"
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    while (dir != null) {
      val candidate = File(dir, repoRelativePath)
      if (candidate.isFile) return candidate
      dir = dir.parentFile
    }
    fail("Could not locate $repoRelativePath by walking up from ${System.getProperty("user.dir")}.")
  }
}
