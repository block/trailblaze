package xyz.block.trailblaze.desktop

import java.io.File
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Coverage for [resolveWorkspaceConfigDir] — the desktop app's mapping from the picked trails
 * directory to the workspace config dir, across both workspace layouts.
 */
class ResolveWorkspaceConfigDirTest {

  @get:Rule
  val tmp = TemporaryFolder()

  @Test
  fun `legacy fallback is the config subdir of the picked dir, existing or not`() {
    val trailsDir = tmp.newFolder("trails")

    assertEquals(File(trailsDir, "config"), resolveWorkspaceConfigDir(trailsDir))
  }

  @Test
  fun `standalone dir inside the picked dir wins without an anchor file`() {
    val root = tmp.newFolder("ws")
    val standalone = File(root, "trailblaze-config").apply { mkdirs() }

    assertEquals(standalone, resolveWorkspaceConfigDir(root))
  }

  @Test
  fun `standalone sibling of the picked dir wins without an anchor file`() {
    val root = tmp.newFolder("ws")
    val standalone = File(root, "trailblaze-config").apply { mkdirs() }
    val trailsDir = File(root, "trails").apply { mkdirs() }

    assertEquals(standalone, resolveWorkspaceConfigDir(trailsDir))
  }

  @Test
  fun `anchored standalone dir is found from a deeply-nested picked dir`() {
    val root = tmp.newFolder("ws")
    val standalone = File(root, "trailblaze-config").apply { mkdirs() }
    File(standalone, "trailblaze.yaml").writeText("")
    val nested = File(root, "features/checkout/trails").apply { mkdirs() }

    assertEquals(standalone, resolveWorkspaceConfigDir(nested))
  }

  @Test
  fun `unanchored standalone dir above the parent does not hijack the workspace`() {
    val root = tmp.newFolder("ws")
    File(root, "trailblaze-config").mkdirs() // no trailblaze.yaml
    val nested = File(root, "features/checkout/trails").apply { mkdirs() }

    assertEquals(File(nested, "config"), resolveWorkspaceConfigDir(nested))
  }

  @Test
  fun `closest anchored standalone dir wins over a higher one`() {
    val outer = tmp.newFolder("outer")
    File(outer, "trailblaze-config").apply { mkdirs(); File(this, "trailblaze.yaml").writeText("") }
    val inner = File(outer, "inner").apply { mkdirs() }
    val innerConfig = File(inner, "trailblaze-config").apply { mkdirs(); File(this, "trailblaze.yaml").writeText("") }
    val nested = File(inner, "a/b").apply { mkdirs() }

    assertEquals(innerConfig, resolveWorkspaceConfigDir(nested))
  }
}
