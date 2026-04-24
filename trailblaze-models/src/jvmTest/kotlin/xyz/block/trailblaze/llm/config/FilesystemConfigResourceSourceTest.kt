package xyz.block.trailblaze.llm.config

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression coverage for the filesystem side of `trailblaze-config/` discovery.
 *
 * These are pure-logic tests — they don't spawn Trailblaze or touch the classpath. The
 * value is pinning the layout convention (`<rootDir>/targets/foo.yaml` → key `"foo"`) and
 * the graceful-missing-subdir behavior so a refactor of `ClasspathResourceDiscovery` can't
 * silently break the filesystem source without tripping a test.
 */
class FilesystemConfigResourceSourceTest {

  private lateinit var rootDir: File

  @BeforeTest
  fun setUp() {
    rootDir = Files.createTempDirectory("filesystem-config-test").toFile()
  }

  @AfterTest
  fun tearDown() {
    rootDir.deleteRecursively()
  }

  @Test
  fun `discovers yaml files under a subdirectory`() {
    val targetsDir = File(rootDir, "targets").apply { mkdir() }
    File(targetsDir, "foo.yaml").writeText("id: foo\ndisplay_name: Foo")
    File(targetsDir, "bar.yaml").writeText("id: bar\ndisplay_name: Bar")

    val source = FilesystemConfigResourceSource(rootDir)
    val loaded = source.discoverAndLoad(
      directoryPath = "${TrailblazeConfigPaths.CONFIG_DIR}/targets",
      suffix = ".yaml",
    )

    assertEquals(setOf("foo", "bar"), loaded.keys)
    assertEquals("id: foo\ndisplay_name: Foo", loaded["foo"])
    assertEquals("id: bar\ndisplay_name: Bar", loaded["bar"])
  }

  @Test
  fun `ignores files that don't match the suffix`() {
    val targetsDir = File(rootDir, "targets").apply { mkdir() }
    File(targetsDir, "yes.yaml").writeText("id: yes")
    File(targetsDir, "README.md").writeText("# notes")
    File(targetsDir, "also-not.txt").writeText("skip me")

    val source = FilesystemConfigResourceSource(rootDir)
    val loaded = source.discoverAndLoad(
      directoryPath = "${TrailblazeConfigPaths.CONFIG_DIR}/targets",
      suffix = ".yaml",
    )

    assertEquals(setOf("yes"), loaded.keys)
  }

  @Test
  fun `missing subdirectory returns empty map instead of erroring`() {
    // Root exists, but no targets/ inside. Users often ship just toolsets/ or just
    // targets/; the loader must treat a missing subdir as a zero-contributions case,
    // not a failure.
    val source = FilesystemConfigResourceSource(rootDir)
    val loaded = source.discoverAndLoad(
      directoryPath = "${TrailblazeConfigPaths.CONFIG_DIR}/toolsets",
      suffix = ".yaml",
    )

    assertTrue(loaded.isEmpty())
  }

  @Test
  fun `empty subdirectory returns empty map`() {
    File(rootDir, "targets").mkdir()
    val source = FilesystemConfigResourceSource(rootDir)
    val loaded = source.discoverAndLoad(
      directoryPath = "${TrailblazeConfigPaths.CONFIG_DIR}/targets",
      suffix = ".yaml",
    )
    assertTrue(loaded.isEmpty())
  }

  @Test
  fun `nested subdirectories are not descended into`() {
    // The classpath convention is a flat list of yaml files, no nesting. Tests that
    // convention survives the filesystem port.
    val targetsDir = File(rootDir, "targets").apply { mkdir() }
    File(targetsDir, "top.yaml").writeText("id: top")
    val nested = File(targetsDir, "nested").apply { mkdir() }
    File(nested, "hidden.yaml").writeText("id: hidden")

    val source = FilesystemConfigResourceSource(rootDir)
    val loaded = source.discoverAndLoad(
      directoryPath = "${TrailblazeConfigPaths.CONFIG_DIR}/targets",
      suffix = ".yaml",
    )

    assertEquals(setOf("top"), loaded.keys)
  }

  @Test
  fun `paths without the trailblaze-config prefix resolve against rootDir directly`() {
    // The prefix-stripping logic in resolveSubDir applies only when the path starts with
    // `trailblaze-config/`. A bare path like `"providers"` should still resolve under the
    // root — useful for callers that pre-strip the prefix themselves.
    File(rootDir, "providers").apply { mkdir() }
      .let { File(it, "openai.yaml").writeText("id: openai") }

    val source = FilesystemConfigResourceSource(rootDir)
    val loaded = source.discoverAndLoad(
      directoryPath = "providers",
      suffix = ".yaml",
    )

    assertEquals(setOf("openai"), loaded.keys)
  }
}
