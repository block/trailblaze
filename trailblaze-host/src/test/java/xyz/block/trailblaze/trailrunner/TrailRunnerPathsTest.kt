package xyz.block.trailblaze.trailrunner

import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TrailRunnerPathsTest {

  @get:Rule
  val tmp = TemporaryFolder()

  private fun dirWith(vararg fileNames: String): File {
    val dir = tmp.newFolder()
    fileNames.forEach { File(dir, it).writeText("x") }
    return dir
  }

  @Test
  fun `a bare unified trail-yaml counts as trails`() {
    assertTrue(containsTrails(dirWith("trail.yaml")))
  }

  @Test
  fun `a classifier-named recording counts as trails`() {
    assertTrue(containsTrails(dirWith("android-phone.trail.yaml")))
  }

  @Test
  fun `an NL-only definition counts as trails`() {
    // Deliberate: a folder holding only NL trails is still a runnable trails workspace.
    assertTrue(containsTrails(dirWith("blaze.yaml")))
  }

  @Test
  fun `unrelated yaml does not count as trails`() {
    assertFalse(containsTrails(dirWith("notes.yaml", "config.yaml")))
  }

  @Test
  fun `a nested bare unified trail-yaml is found within the scan depth`() {
    val root = tmp.newFolder()
    val nested = File(root, "suite/case").apply { mkdirs() }
    File(nested, "trail.yaml").writeText("x")
    assertTrue(containsTrails(root))
  }
}
