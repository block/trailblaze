package xyz.block.trailblaze.host

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `trailblaze check` shares one [TrailCorpus] across its per-trail phases so each trail is decoded
 * once. A regression to decoding per phase would otherwise only show up as a slower `check --all`.
 */
class TrailCorpusTest {

  @get:Rule
  val tmp = TemporaryFolder()

  @Test
  fun `every phase that asks for a trail gets the one decode`() {
    val trailsRoot = tmp.newFolder("trails")
    File(trailsRoot, "login.trail.yaml").writeText(GOOD_TRAIL)

    val corpus = TrailCorpus.load(trailsRoot)
    val trail = corpus.trails.single()

    // The recording-validation phase reads `recordingTrails`, the lint phase `trails`: one object.
    assertSame(trail, corpus.recordingTrails.single())
    assertSame(trail.document.getOrThrow(), trail.document.getOrThrow())
  }

  @Test
  fun `a trail that fails to decode fails the same way on every access, and its sibling still decodes`() {
    val trailsRoot = tmp.newFolder("trails")
    File(trailsRoot, "good.trail.yaml").writeText(GOOD_TRAIL)
    File(trailsRoot, "bad.trail.yaml").writeText("config: [unbalanced\n")

    val byName = TrailCorpus.load(trailsRoot).trails.associateBy { it.file.name }
    assertEquals(setOf("good.trail.yaml", "bad.trail.yaml"), byName.keys)

    val bad = byName.getValue("bad.trail.yaml")
    val failure = assertNotNull(bad.document.exceptionOrNull())
    assertSame(failure, bad.document.exceptionOrNull())
    assertTrue(bad.text.isSuccess, "reading succeeded; only the decode failed")

    assertTrue(byName.getValue("good.trail.yaml").document.isSuccess)
  }

  private companion object {
    // Tool name deliberately not registered on the classpath: it decodes via the raw-args fallback.
    val GOOD_TRAIL =
      """
      config:
        target: demo
      trail:
      - step: Open settings
        recording:
          android:
          - demoTap:
              text: Settings
      """.trimIndent()
  }
}
