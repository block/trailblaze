package xyz.block.trailblaze

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Unit tests for [TrailblazeYamlUtil.calculateTrailblazeYamlAssetPath] — the probing order behind
 * the no-arg `runFromAsset()` resolution that generated base-class shells rely on.
 *
 * Contract under test: named `<method>.trail.yaml` candidates win over the directory-per-test
 * unified layout, and a directory hit returns the DIRECTORY path (so the caller's
 * `findBestTrailResourcePath` can pick the classifier-appropriate file inside), never the
 * `trail.yaml` path itself.
 */
class TrailblazeYamlUtilTest {

  private val namedFilePaths = listOf(
    "trails/com.example/FooTest/myTest.trail.yaml",
    "trails/FooTest/myTest.trail.yaml",
    "trails/com/example/FooTest/myTest.trail.yaml",
  )

  @Test
  fun `resolves a named trail file when one exists`() {
    val resolved = TrailblazeYamlUtil.calculateTrailblazeYamlAssetPath(namedFilePaths) {
      it == "trails/FooTest/myTest.trail.yaml"
    }
    assertEquals("trails/FooTest/myTest.trail.yaml", resolved)
  }

  @Test
  fun `resolves a directory-per-test unified recording to its directory path`() {
    val resolved = TrailblazeYamlUtil.calculateTrailblazeYamlAssetPath(namedFilePaths) {
      it == "trails/FooTest/myTest/trail.yaml"
    }
    assertEquals("trails/FooTest/myTest", resolved)
  }

  @Test
  fun `named trail files win over a directory recording with the same identity`() {
    val existing = setOf(
      "trails/FooTest/myTest.trail.yaml",
      "trails/FooTest/myTest/trail.yaml",
    )
    val resolved = TrailblazeYamlUtil.calculateTrailblazeYamlAssetPath(namedFilePaths) { it in existing }
    assertEquals("trails/FooTest/myTest.trail.yaml", resolved)
  }

  @Test
  fun `error lists both the named and directory candidates when nothing resolves`() {
    val e = assertFailsWith<IllegalStateException> {
      TrailblazeYamlUtil.calculateTrailblazeYamlAssetPath(namedFilePaths) { false }
    }
    val message = e.message.orEmpty()
    namedFilePaths.forEach { named ->
      check(message.contains(named)) { "expected error to list $named: $message" }
      val dirCandidate = named.removeSuffix(".trail.yaml") + "/trail.yaml"
      check(message.contains(dirCandidate)) { "expected error to list $dirCandidate: $message" }
    }
  }

  // resolveTrailAsset is the shared probing order behind calculateTrailblazeYamlAssetPath AND the
  // on-device per-trail driver peek (an internal caller). These tests pin the generic contract
  // both callers rely on.

  @Test
  fun `resolveTrailAsset exhausts every named candidate before probing any recording directory`() {
    // The LAST named candidate must beat the FIRST recording directory — the driver peek would
    // otherwise resolve a different file than runFromAsset executes.
    val resolved = TrailblazeYamlUtil.resolveTrailAsset(
      namedFilePaths = namedFilePaths,
      resolveNamedFile = { path -> path.takeIf { it == namedFilePaths.last() } },
      resolveRecordingDir = { dir -> "$dir/trail.yaml" },
    )
    assertEquals(namedFilePaths.last(), resolved)
  }

  @Test
  fun `resolveTrailAsset recording-dir resolver receives the suffix-stripped candidate and its result is returned as-is`() {
    // The driver-peek usage shape: the resolver picks the best playable file INSIDE the recording
    // directory (e.g. a classifier-specific recording), and that value must come back untouched.
    val probedDirs = mutableListOf<String>()
    val resolved = TrailblazeYamlUtil.resolveTrailAsset(
      namedFilePaths = namedFilePaths,
      resolveNamedFile = { null },
      resolveRecordingDir = { dir ->
        probedDirs += dir
        "$dir/android-phone.trail.yaml".takeIf { dir == "trails/FooTest/myTest" }
      },
    )
    assertEquals("trails/FooTest/myTest/android-phone.trail.yaml", resolved)
    // Probed in candidate order, suffix-stripped, stopping at the hit.
    assertEquals(listOf("trails/com.example/FooTest/myTest", "trails/FooTest/myTest"), probedDirs)
  }

  @Test
  fun `resolveTrailAsset returns null when neither shape resolves`() {
    val resolved = TrailblazeYamlUtil.resolveTrailAsset<String>(
      namedFilePaths = namedFilePaths,
      resolveNamedFile = { null },
      resolveRecordingDir = { null },
    )
    assertEquals(null, resolved)
  }
}
