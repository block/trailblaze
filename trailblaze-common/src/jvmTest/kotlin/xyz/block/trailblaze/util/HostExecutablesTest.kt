package xyz.block.trailblaze.util

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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

  private fun dirWith(name: String, vararg files: String): File =
    tempFolder.newFolder(name).also { dir -> files.forEach { File(dir, it).writeText("") } }

  private val pathExt = ".COM;.EXE;.BAT;.CMD"

  @Test
  fun `on Windows a bare command resolves to its exe on PATH`() {
    val dir = dirWith("platform-tools", "adb.exe")

    assertEquals(File(dir, "adb.exe").absolutePath, windowsCommand("adb", windows = true, pathEnv = dir.path, pathExt = pathExt))
  }

  @Test
  fun `on Windows a bare command resolves to a bat script, which Java alone never finds`() {
    val dir = dirWith("cmdline-tools", "sdkmanager.bat")

    assertEquals(
      File(dir, "sdkmanager.bat").absolutePath,
      windowsCommand("sdkmanager", windows = true, pathEnv = dir.path, pathExt = pathExt),
    )
  }

  @Test
  fun `on Windows PATH order wins over PATHEXT order`() {
    val first = dirWith("first", "tool.cmd")
    val second = dirWith("second", "tool.exe")
    val path = first.path + File.pathSeparator + second.path

    assertEquals(File(first, "tool.cmd").absolutePath, windowsCommand("tool", windows = true, pathEnv = path, pathExt = pathExt))
  }

  @Test
  fun `a command with an extension or a directory, or that PATH lacks, is passed through unchanged`() {
    val dir = dirWith("bin", "gradlew.bat.exe", "adb.exe")

    assertEquals("gradlew.bat", windowsCommand("gradlew.bat", windows = true, pathEnv = dir.path, pathExt = pathExt))
    assertEquals("tools\\adb", windowsCommand("tools\\adb", windows = true, pathEnv = dir.path, pathExt = pathExt))
    assertEquals("missing", windowsCommand("missing", windows = true, pathEnv = dir.path, pathExt = pathExt))
  }

  @Test
  fun `off Windows a command is passed through unchanged`() {
    val dir = dirWith("unix", "adb.exe")

    assertEquals("adb", windowsCommand("adb", windows = false, pathEnv = dir.path, pathExt = pathExt))
  }

  @Test
  fun `on Windows exec resolves against the child's PATH, whatever its case`() {
    val inherited = tempFolder.newFolder("inherited").also { File(it, "adb.exe").writeText("") }
    val supplied = tempFolder.newFolder("supplied").also { File(it, "adb.exe").writeText("") }

    // The caller's `Path` replaces the inherited PATH for the child, so it has to pick the program.
    assertEquals(
      listOf(File(supplied, "adb.exe").absolutePath, "devices"),
      hostArgv(listOf("adb", "devices"), mapOf("Path" to supplied.path), windows = true),
    )
    assertEquals(
      listOf(File(inherited, "adb.exe").absolutePath, "devices"),
      hostArgv(listOf("adb", "devices"), mapOf("PATH" to inherited.path), windows = true),
    )
    assertEquals(listOf("adb", "devices"), hostArgv(listOf("adb", "devices"), mapOf("PATH" to supplied.path), windows = false))
    // A bare name PATH lacks fails, rather than letting Windows try the working directory first.
    assertFailsWith<IOException> { hostArgv(listOf("adb"), mapOf("PATH" to tempFolder.newFolder("none").path), windows = true) }
  }

  @Test
  fun `a batch file whose arguments cmd exe would reinterpret is refused, and a plain one is not`() {
    for (arg in listOf("--sdk_root=C:\\sdk\\%USERNAME%", "a!b", "a&b", "a|b", "a>b", "a^b", "say \"hi\"", "a\nb")) {
      val problem = batchArgumentProblem(listOf("C:\\sdk\\sdkmanager.bat", "--list", arg), windows = true)
      assertNotNull(problem, "cmd.exe would reinterpret '$arg'")
      assertContains(problem, "argument 2")
    }
    assertNull(batchArgumentProblem(listOf("C:\\sdk\\sdkmanager.bat", "--list", "C:\\Program Files\\sdk"), windows = true))
    // cmd.exe reads the batch file's own path too, so a resolved `C:\\SDK\\%CHANNEL%` is refused.
    assertContains(
      assertNotNull(batchArgumentProblem(listOf("C:\\SDK\\%CHANNEL%\\sdkmanager.bat", "--list"), windows = true)),
      "its path",
    )
    // An exe gets its arguments as written, and off Windows nothing goes through cmd.exe.
    assertNull(batchArgumentProblem(listOf("C:\\sdk\\adb.exe", "a&b"), windows = true))
    assertNull(batchArgumentProblem(listOf("./gradlew.bat", "a&b"), windows = false))
  }

  @Test
  fun `on Windows a PATHEXT type no process can start is skipped`() {
    val dir = tempFolder.newFolder("scripts")
    File(dir, "tool.vbs").writeText("")
    File(dir, "tool.exe").writeText("")
    assertEquals(
      File(dir, "tool.exe").absolutePath,
      windowsCommand("tool", windows = true, pathEnv = dir.path, pathExt = ".VBS;.EXE"),
    )
  }
}
