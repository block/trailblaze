package xyz.block.trailblaze.util

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Pins the boot-volume temp-dir selection for files written by CoreSimulator processes:
 * TMPDIR env when usable, never a bogus TMPDIR, always some usable directory. The whole point of
 * the helper is that it does NOT consult `java.io.tmpdir` when a boot-volume dir is available, so
 * an embedder's `-Djava.io.tmpdir=/Volumes/...` can't defeat it (the fix for the CI regression
 * where SimRender couldn't write the recording target).
 */
class CoreSimulatorTempFilesTest {

  @Test
  fun `uses TMPDIR when it names a writable directory`() {
    val dir = createTempDir()
    try {
      assertEquals(dir, CoreSimulatorTempFiles.bootVolumeTempDir(dir.absolutePath))
    } finally {
      dir.delete()
    }
  }

  @Test
  fun `ignores a TMPDIR that does not exist`() {
    val bogus = File("/nonexistent/trailblaze-tmpdir-test")
    val resolved = CoreSimulatorTempFiles.bootVolumeTempDir(bogus.absolutePath)
    assertNotEquals(bogus, resolved)
    assertTrue(resolved.isDirectory, "fallback must be a usable directory: $resolved")
  }

  @Test
  fun `ignores a TMPDIR that names a plain file`() {
    val file = File.createTempFile("tb-tmpdir-test-", ".txt")
    try {
      val resolved = CoreSimulatorTempFiles.bootVolumeTempDir(file.absolutePath)
      assertNotEquals(file, resolved)
      assertTrue(resolved.isDirectory)
    } finally {
      file.delete()
    }
  }

  @Test
  fun `falls back to a usable directory when TMPDIR is unset or blank`() {
    for (env in listOf(null, "", "   ")) {
      val resolved = CoreSimulatorTempFiles.bootVolumeTempDir(env)
      assertTrue(resolved.isDirectory, "TMPDIR=$env must resolve a usable directory: $resolved")
      if (isMacOs()) {
        assertEquals(File("/private/tmp"), resolved)
      }
    }
  }

  private fun createTempDir(): File =
    File.createTempFile("tb-tmpdir-test-", "").let { seed ->
      seed.delete()
      seed.mkdirs()
      seed
    }
}
