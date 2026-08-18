import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class StaleUberJarPrunerTest {
  @get:Rule val tmp = TemporaryFolder()

  private fun dirWith(vararg names: String): File {
    val dir = tmp.newFolder()
    names.forEach { File(dir, it).writeText("bytes of $it") }
    return dir
  }

  @Test
  fun `keeps the current jar and its jsa sibling, deletes every other jar and jsa`() {
    val dir =
      dirWith(
        "Trailblaze-macos-arm64-20260813.1.jar",
        "Trailblaze-macos-arm64-20260813.1.jsa",
        "Trailblaze-macos-arm64-20260101.0.jar",
        "Trailblaze-macos-arm64-20260101.0.jsa",
      )

    val pruned = StaleUberJarPruner.prune(dir, "Trailblaze-macos-arm64-20260813.1.jar")

    assertEquals(2, pruned)
    assertTrue(File(dir, "Trailblaze-macos-arm64-20260813.1.jar").exists())
    assertTrue(File(dir, "Trailblaze-macos-arm64-20260813.1.jsa").exists())
    assertFalse(File(dir, "Trailblaze-macos-arm64-20260101.0.jar").exists())
    assertFalse(File(dir, "Trailblaze-macos-arm64-20260101.0.jsa").exists())
  }

  @Test
  fun `never touches non-jar files - the dev launcher reads its staleness marker from this dir`() {
    // `.blaze-source-hash` is how `dev_ensure_jar` (scripts/dev-jar-cache.sh)
    // decides it can skip the build; deleting it would silently cost every dev launcher
    // invocation a full repackage.
    val dir = dirWith("current.jar", "stale.jar", ".blaze-source-hash", "notes.txt")

    StaleUberJarPruner.prune(dir, "current.jar")

    assertTrue(File(dir, ".blaze-source-hash").exists())
    assertTrue(File(dir, "notes.txt").exists())
    assertFalse(File(dir, "stale.jar").exists())
  }

  @Test
  fun `a missing directory is a no-op, an already-clean one deletes nothing`() {
    assertEquals(0, StaleUberJarPruner.prune(File(tmp.root, "never-created"), "current.jar"))
    val clean = dirWith("current.jar", "current.jsa")
    assertEquals(0, StaleUberJarPruner.prune(clean, "current.jar"))
    assertTrue(File(clean, "current.jar").exists())
    assertTrue(File(clean, "current.jsa").exists())
  }

  @Test
  fun `throws when the path exists but cannot be listed as a directory`() {
    // listFiles() returns null for a non-directory (and for I/O errors) - NOT for an empty
    // dir. Returning quietly there would green-light a build whose stale JARs all survived.
    val notADir = tmp.newFile("compose-jars-as-a-file")

    val e = assertFailsWith<IOException> { StaleUberJarPruner.prune(notADir, "current.jar") }
    assertTrue(e.message!!.contains(notADir.name))
  }

  @Test
  fun `throws naming the artifact when a stale delete does not take`() {
    val dir = dirWith("current.jar", "stale.jar")
    // A read-only parent makes delete() return false on POSIX. Skip (not fail) where the
    // environment ignores that (e.g. running as root): the property under test is the
    // throw-on-survivor contract, not the platform's permission model.
    assumeTrue(dir.setWritable(false))
    try {
      assumeTrue(!File(dir, "stale.jar").delete())

      val e = assertFailsWith<IOException> { StaleUberJarPruner.prune(dir, "current.jar") }
      assertTrue(e.message!!.contains("stale.jar"))
    } finally {
      dir.setWritable(true)
    }
  }
}
