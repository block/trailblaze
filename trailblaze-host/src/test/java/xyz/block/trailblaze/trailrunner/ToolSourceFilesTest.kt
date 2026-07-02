package xyz.block.trailblaze.trailrunner

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import kotlin.test.assertTrue

class ToolSourceFilesTest {

  @get:Rule
  val tmp = TemporaryFolder()

  @Test
  fun `buildIndex terminates on a symlink cycle and still indexes kt files`() {
    val root = tmp.newFolder("walk")
    val pkg = File(root, "kotlin/xyz/block/trailblaze").apply { mkdirs() }
    File(pkg, "Foo.kt").writeText("package xyz.block.trailblaze\nclass Foo")
    Files.createSymbolicLink(File(root, "loop").toPath(), root.toPath())

    val index = ToolSourceFiles.buildIndex(root)

    assertTrue(
      index.keys.any { it.endsWith("xyz/block/trailblaze/Foo.kt") },
      "expected Foo.kt indexed by its package-relative path, got: ${index.keys}",
    )
  }

  @Test
  fun `buildIndex stops descending past the depth cap`() {
    val root = tmp.newFolder("deep")
    var dir = File(root, "kotlin/xyz")
    dir.mkdirs()
    repeat(40) { dir = File(dir, "d$it").apply { mkdirs() } }
    File(dir, "TooDeep.kt").writeText("class TooDeep")

    val index = ToolSourceFiles.buildIndex(root)

    assertTrue(index.keys.none { it.endsWith("TooDeep.kt") }, "file past the depth cap should not be indexed")
  }
}
