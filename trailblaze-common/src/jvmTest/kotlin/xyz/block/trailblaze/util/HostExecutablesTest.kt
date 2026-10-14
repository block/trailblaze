package xyz.block.trailblaze.util

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HostExecutablesTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private fun dirWithGit(name: String): File = tempFolder.newFolder(name).also { File(it, "git.exe").writeText("") }

  @Test
  fun `off Windows the bare name is used, which the OS looks up on PATH only`() {
    assertEquals("git", hostExecutable("git", windows = false, pathEnv = dirWithGit("bin").path))
  }

  @Test
  fun `on Windows the first PATH directory holding the exe wins, by absolute path`() {
    val empty = tempFolder.newFolder("empty")
    val first = dirWithGit("first")
    val second = dirWithGit("second")
    val path = listOf(empty, first, second).joinToString(File.pathSeparator) { it.path }

    assertEquals(File(first, "git.exe").absolutePath, hostExecutable("git", windows = true, pathEnv = path))
  }

  @Test
  fun `on Windows a quoted PATH entry is searched`() {
    val dir = dirWithGit("quoted")

    assertEquals(File(dir, "git.exe").absolutePath, hostExecutable("git", windows = true, pathEnv = "\"${dir.path}\""))
  }

  @Test
  fun `on Windows a relative PATH entry is never searched, since it names the working directory`() {
    val relative = File("build", "host-executables-test-${System.nanoTime()}")
    try {
      relative.mkdirs()
      File(relative, "git.exe").writeText("")
      val absolute = dirWithGit("absolute")
      val path = relative.path + File.pathSeparator + absolute.path

      assertEquals(File(absolute, "git.exe").absolutePath, hostExecutable("git", windows = true, pathEnv = path))
    } finally {
      relative.deleteRecursively()
    }
  }

  @Test
  fun `on Windows a tool missing from PATH fails rather than falling back to the bare name`() {
    val path = tempFolder.newFolder("nothing").path
    assertFailsWith<IOException> { hostExecutable("git", windows = true, pathEnv = path) }
  }
}
