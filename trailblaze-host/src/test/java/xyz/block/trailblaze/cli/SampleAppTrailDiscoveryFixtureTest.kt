package xyz.block.trailblaze.cli

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import xyz.block.trailblaze.config.project.TrailDiscovery
import xyz.block.trailblaze.recordings.TrailRecordings

/**
 * CI fixture test: exercises [TrailDiscovery] against the committed sample-app trails
 * tree at `examples/android-sample-app/`.
 *
 * Runs under `./gradlew check`, so any regression in discovery — exclude rules, anchor
 * semantics, trail-name contract — that would stop the MCP `trail(action=LIST)` tool
 * or the desktop UI's Trails browser from finding sample-app fixtures fails the build.
 *
 * The CLI itself no longer consumes [TrailDiscovery]; `trailblaze trail` takes explicit
 * file arguments or a shell glob (see `docs/project_layout.md`). Discovery
 * is still foundational for the MCP and desktop UI code paths, which is what this
 * test gates.
 */
class SampleAppTrailDiscoveryFixtureTest {

  private lateinit var sampleAppRoot: File

  @Before
  fun locateSampleApp() {
    // Walk up from the test's working dir until we find the sample-app. Supports both
    // the standalone repo layout (`examples/android-sample-app/`) and a nested layout
    // where Trailblaze lives under a subdirectory of a larger repo
    // (`opensource/examples/android-sample-app/`). Guarding on "find it anywhere on the
    // way up" keeps this test portable across different runners (IDE, Gradle, CI).
    val candidate = generateSequence(File(".").absoluteFile) { it.parentFile }
      .mapNotNull { dir ->
        sequenceOf("examples/android-sample-app", "opensource/examples/android-sample-app")
          .map(dir::resolve)
          .firstOrNull { it.isDirectory }
      }
      .firstOrNull()
    Assume.assumeTrue(
      "sample-app fixture not found via walk-up from ${File(".").absoluteFile}",
      candidate != null,
    )
    sampleAppRoot = candidate!!
  }

  @Test
  fun `discovery against the sample-app trails dir finds every committed trail file`() {
    val trailsDir = File(sampleAppRoot, "trails")
    assertTrue(trailsDir.isDirectory, "missing trails dir at ${trailsDir.absolutePath}")

    val discovered = TrailDiscovery.discoverTrailFiles(trailsDir.toPath())
      .map { it.canonicalPath }
      .toSet()

    val groundTruth = trailsDir.walkTopDown()
      .filter { it.isFile && TrailRecordings.isTrailFile(it.name) }
      .map { it.canonicalPath }
      .toSet()

    assertEquals(
      groundTruth,
      discovered,
      "TrailDiscovery drifted from the sample-app's committed trail set",
    )
    assertTrue(discovered.isNotEmpty(), "no trails discovered — fixture may have been deleted")
  }

  @Test
  fun `discovery on the sample-app root prunes build and gradle output`() {
    // Run discovery at the sample-app root so TrailDiscovery has to walk across the
    // `build/` and `src/` siblings of `trails/`. If a CI worker has stale trail-shaped
    // files cached under `build/` (e.g., previous agent runs leaked artifacts), the
    // excludes must keep them out of the result.
    //
    // Paths are normalized to sample-app-relative form before any substring checks —
    // CI workers can live at paths like `/Users/build/.ci-storage/...` where the
    // absolute path itself contains `/build/`, which would otherwise trigger a false
    // positive on the excluded-dir scan below.
    val sampleAppRootCanonical = sampleAppRoot.canonicalFile
    val discovered = TrailDiscovery.discoverTrailFiles(sampleAppRoot.toPath())
      .map { File(it.canonicalPath).relativeTo(sampleAppRootCanonical).path }

    // Every discovered file must live under the `trails/` subdir — nothing from
    // build/, .gradle/, src/resources, etc.
    val outsideTrails = discovered.filter { !it.startsWith("trails${File.separator}") }
    assertEquals(
      emptyList(),
      outsideTrails,
      "discovery surfaced non-trails files: $outsideTrails",
    )

    for (excluded in setOf("build", ".gradle", ".git", "node_modules", ".trailblaze")) {
      val leaked = discovered.filter {
        it.startsWith("$excluded${File.separator}") ||
          it.contains("${File.separator}$excluded${File.separator}")
      }
      assertEquals(
        emptyList(),
        leaked,
        "excluded dir '$excluded' leaked into discovery: $leaked",
      )
    }
  }

  @Test
  fun `known sample-app trails are discoverable`() {
    // Pin a small set of stable, load-bearing fixtures so a silent drift in
    // isTrailFile / anchor rules that removes specific files from the result set
    // fails this test with a clear "known trail missing" error rather than just a
    // count mismatch.
    val expected = setOf(
      "trails/mcp-tools-demo/mcp-tools-demo.trail.yaml",
      "trails/catalog/overlay-tap/blaze.yaml",
      "trails/android-ondevice-instrumentation/forms/text-input/android-phone.trail.yaml",
    )

    val discovered = TrailDiscovery.discoverTrailFiles(sampleAppRoot.toPath())
      .map { it.relativeTo(sampleAppRoot).path }
      .toSet()

    for (path in expected) {
      assertTrue(
        path in discovered,
        "known sample-app trail '$path' not discovered; present in result: $discovered",
      )
    }
  }
}
