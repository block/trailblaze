package xyz.block.trailblaze.config.project

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.testing.ClasspathFixture

/**
 * Tests for [PackSource] containment validation. The [PackSource.readSibling] entry
 * point rejects paths that would escape the pack directory (`..` segments) or that
 * are absolute, mirroring the same rule for both `Filesystem` and `Classpath` variants.
 *
 * Pack manifests are commit-owned and trusted today, so this is defense-in-depth — but
 * the abstraction has to stay safe for future use cases (untrusted authors, remote
 * pack distribution) without re-litigating the rule.
 */
class PackSourceTest {

  private val classpath = ClasspathFixture()

  @AfterTest fun cleanup() = classpath.cleanup()

  @Test
  fun `Filesystem readSibling reads a real sibling file`() {
    val packDir = newTempDir()
    File(packDir, "waypoints").mkdirs()
    File(packDir, "waypoints/test.waypoint.yaml").writeText("id: test")

    val source = PackSource.Filesystem(packDir = packDir)
    val content = source.readSibling("waypoints/test.waypoint.yaml")

    assertEquals("id: test", content)
  }

  @Test
  fun `Filesystem readSibling returns null when file does not exist`() {
    val packDir = newTempDir()
    val source = PackSource.Filesystem(packDir = packDir)
    assertNull(source.readSibling("nonexistent.yaml"))
  }

  @Test
  fun `Filesystem readSibling rejects parent-traversal segments`() {
    val packDir = newTempDir()
    val source = PackSource.Filesystem(packDir = packDir)
    assertFailsWith<IllegalArgumentException> {
      source.readSibling("../escape.yaml")
    }
    assertFailsWith<IllegalArgumentException> {
      source.readSibling("waypoints/../../escape.yaml")
    }
  }

  @Test
  fun `Filesystem readSibling rejects absolute paths`() {
    val packDir = newTempDir()
    val source = PackSource.Filesystem(packDir = packDir)
    assertFailsWith<IllegalArgumentException> {
      source.readSibling("/etc/passwd")
    }
    assertFailsWith<IllegalArgumentException> {
      source.readSibling("/var/lib/foo.yaml")
    }
  }

  @Test
  fun `Filesystem readSibling rejects backslash-rooted paths`() {
    val packDir = newTempDir()
    val source = PackSource.Filesystem(packDir = packDir)
    assertFailsWith<IllegalArgumentException> {
      source.readSibling("\\windows\\system32")
    }
  }

  @Test
  fun `Filesystem readSibling rejects backslash-separated parent traversal`() {
    val packDir = newTempDir()
    val source = PackSource.Filesystem(packDir = packDir)
    assertFailsWith<IllegalArgumentException> {
      source.readSibling("waypoints\\..\\..\\escape.yaml")
    }
  }

  @Test
  fun `Filesystem readSibling rejects blank paths`() {
    val packDir = newTempDir()
    val source = PackSource.Filesystem(packDir = packDir)
    assertFailsWith<IllegalArgumentException> { source.readSibling("") }
    assertFailsWith<IllegalArgumentException> { source.readSibling("   ") }
  }

  @Test
  fun `Filesystem readSibling rejects URL-encoded escape vectors`() {
    val packDir = newTempDir()
    val source = PackSource.Filesystem(packDir = packDir)
    // %2e%2e is the URL-encoded form of "..". Even though the textual `..` check
    // wouldn't trigger here, the `%` ban catches it at the textual layer.
    assertFailsWith<IllegalArgumentException> { source.readSibling("%2e%2e/escape.yaml") }
    assertFailsWith<IllegalArgumentException> { source.readSibling("waypoints/%2e%2e/escape.yaml") }
    // Even non-traversal `%` characters are banned — legitimate pack-sibling filenames
    // have no reason to contain them, and forbidding the character at the textual
    // layer forecloses any future encoded-traversal vector at the source.
    assertFailsWith<IllegalArgumentException> { source.readSibling("waypoints/foo%20bar.yaml") }
  }

  @Test
  fun `Filesystem readSibling rejects symlinks that resolve outside packDir`() {
    val outsideDir = newTempDir()
    val outsideFile = File(outsideDir, "escape.yaml").apply { writeText("escaped") }
    val packDir = newTempDir()
    // Drop a symlink INSIDE the pack dir pointing at a file OUTSIDE it. Textual
    // validation passes (no `..`, no leading `/`, no `%`), but the canonical-path
    // containment check should still reject the read because the canonical resolved
    // path lives outside the pack directory.
    val symlinkPath = File(packDir, "tricky.yaml").toPath()
    java.nio.file.Files.createSymbolicLink(symlinkPath, outsideFile.toPath())

    val source = PackSource.Filesystem(packDir = packDir)
    val ex = assertFailsWith<IllegalArgumentException> { source.readSibling("tricky.yaml") }
    assertTrue(
      ex.message!!.contains("resolved outside packDir"),
      "Expected containment-check rejection message, got: ${ex.message}",
    )
  }

  @Test
  fun `Filesystem readSibling rejects path resolving exactly to packDir itself`() {
    val packDir = newTempDir()
    val source = PackSource.Filesystem(packDir = packDir)
    // A symlink pointing AT the pack dir itself resolves canonically to packDir,
    // which isn't a valid sibling-file read. Reject explicitly.
    val symlinkPath = File(packDir, "self.yaml").toPath()
    java.nio.file.Files.createSymbolicLink(symlinkPath, packDir.toPath())
    assertFailsWith<IllegalArgumentException> { source.readSibling("self.yaml") }
  }

  @Test
  fun `Filesystem readSibling allows symlinks that stay inside packDir`() {
    val packDir = newTempDir()
    val realFile = File(packDir, "real").apply {
      mkdirs()
      File(this, "data.yaml").writeText("inner")
    }
    // A symlink under packDir pointing at another path *also* under packDir is fine.
    val symlinkPath = File(packDir, "link.yaml").toPath()
    java.nio.file.Files.createSymbolicLink(symlinkPath, File(realFile, "data.yaml").toPath())

    val source = PackSource.Filesystem(packDir = packDir)
    assertEquals("inner", source.readSibling("link.yaml"))
  }

  @Test
  fun `Classpath readSibling rejects parent-traversal segments`() {
    val source = PackSource.Classpath(resourceDir = "trailblaze-config/packs/test")
    assertFailsWith<IllegalArgumentException> {
      source.readSibling("../escape.yaml")
    }
    assertFailsWith<IllegalArgumentException> {
      source.readSibling("waypoints/../../escape.yaml")
    }
  }

  @Test
  fun `Classpath readSibling rejects absolute paths`() {
    val source = PackSource.Classpath(resourceDir = "trailblaze-config/packs/test")
    assertFailsWith<IllegalArgumentException> {
      source.readSibling("/absolute/path.yaml")
    }
  }

  @Test
  fun `Classpath readSibling rejects blank paths`() {
    val source = PackSource.Classpath(resourceDir = "trailblaze-config/packs/test")
    assertFailsWith<IllegalArgumentException> { source.readSibling("") }
  }

  @Test
  fun `Classpath readSibling returns null when resource does not exist`() {
    val source = PackSource.Classpath(resourceDir = "trailblaze-config/packs/test")
    // Resource path is well-formed but the resource isn't on the classpath. Should return
    // null, parallel to Filesystem.readSibling on a non-existent file. Otherwise callers
    // can't distinguish "tried to read a missing file" from "containment violation."
    assertNull(source.readSibling("nonexistent-sibling.yaml"))
  }

  @Test
  fun `Classpath readSibling rejects backslash-rooted paths`() {
    val source = PackSource.Classpath(resourceDir = "trailblaze-config/packs/test")
    assertFailsWith<IllegalArgumentException> {
      source.readSibling("\\windows\\system32")
    }
  }

  @Test
  fun `Classpath readSibling rejects URL-encoded escape vectors`() {
    val source = PackSource.Classpath(resourceDir = "trailblaze-config/packs/test")
    // The Classpath variant doesn't have a canonical-path check (there's no real
    // filesystem to canonicalize against) — its only defense is the textual layer,
    // which the `%` ban backstops.
    assertFailsWith<IllegalArgumentException> { source.readSibling("%2e%2e/escape.yaml") }
    assertFailsWith<IllegalArgumentException> { source.readSibling("ok/%2e%2e/escape.yaml") }
  }

  @Test
  fun `describe surfaces the source type and location`() {
    val fs = PackSource.Filesystem(packDir = File("/tmp/some-pack"))
    val cp = PackSource.Classpath(resourceDir = "trailblaze-config/packs/clock")

    // Filesystem describe: absolute path. We don't pin the exact prefix because
    // /tmp may be a symlink on macOS — just assert it ends with the pack dir name.
    assert(fs.describe().endsWith("some-pack")) { "got: ${fs.describe()}" }
    assertEquals("classpath:trailblaze-config/packs/clock", cp.describe())
  }

  // ==========================================================================
  // listSiblings — used by the pack loader to auto-discover operational tool
  // YAMLs from `<pack>/tools/`. Same path-validation guarantees as readSibling
  // (no `..`, no `%`, no absolute paths) plus a direct-children-only contract.
  // ==========================================================================

  @Test
  fun `Filesystem listSiblings returns only direct children matching the suffix list`() {
    val packDir = newTempDir()
    val toolsDir = File(packDir, "tools").apply { mkdirs() }
    File(toolsDir, "foo.tool.yaml").writeText("id: foo")
    File(toolsDir, "bar.shortcut.yaml").writeText("id: bar")
    File(toolsDir, "baz.trailhead.yaml").writeText("id: baz")
    // Files that should NOT match: wrong suffix, nested deeper than direct children.
    File(toolsDir, "readme.md").writeText("not a tool")
    File(toolsDir, "scratch.draft.yaml").writeText("id: scratch")
    File(toolsDir, "subdir").mkdirs()
    File(toolsDir, "subdir/nested.tool.yaml").writeText("id: nested")

    val source = PackSource.Filesystem(packDir = packDir)
    val results = source.listSiblings(
      relativeDir = "tools",
      suffixes = listOf(".tool.yaml", ".shortcut.yaml", ".trailhead.yaml"),
    )

    assertEquals(
      listOf(
        "tools/bar.shortcut.yaml",
        "tools/baz.trailhead.yaml",
        "tools/foo.tool.yaml",
      ),
      results,
      "Expected direct-children matching the suffix list, sorted; got: $results",
    )
  }

  @Test
  fun `Filesystem listSiblings returns empty list when relativeDir does not exist`() {
    val packDir = newTempDir()
    val source = PackSource.Filesystem(packDir = packDir)

    // No `tools/` directory at all — listSiblings should degrade gracefully to
    // empty rather than throw, so a pack with zero auto-discovered tools is fine.
    assertEquals(
      emptyList(),
      source.listSiblings(relativeDir = "tools", suffixes = listOf(".tool.yaml")),
    )
  }

  @Test
  fun `Filesystem listSiblings rejects path-escape and absolute relativeDir values`() {
    val packDir = newTempDir()
    val source = PackSource.Filesystem(packDir = packDir)

    // Same containment rules as readSibling — these must throw, not silently
    // wander outside the pack directory.
    assertFailsWith<IllegalArgumentException> {
      source.listSiblings(relativeDir = "../escape", suffixes = listOf(".yaml"))
    }
    assertFailsWith<IllegalArgumentException> {
      source.listSiblings(relativeDir = "/etc", suffixes = listOf(".yaml"))
    }
    assertFailsWith<IllegalArgumentException> {
      source.listSiblings(relativeDir = "%2e%2e/escape", suffixes = listOf(".yaml"))
    }
    assertFailsWith<IllegalArgumentException> {
      source.listSiblings(relativeDir = "", suffixes = listOf(".yaml"))
    }
  }

  @Test
  fun `Filesystem listSiblings rejects symlink that points outside the pack dir`() {
    // Symlink creation isn't supported on every CI environment (Windows without
    // developer-mode, certain restricted filesystems). Mirror the skip pattern
    // used by `TrailDiscoveryTest` / `WorkspaceRootTest` so this test stays
    // portable while still exercising the containment guarantee where supported.
    org.junit.Assume.assumeTrue("Symlink support required", supportsSymlinks())

    val packDir = newTempDir()
    val outsideDir = newTempDir() // separate root, definitely not under packDir
    File(outsideDir, "leaked.tool.yaml").writeText("id: leaked")

    // Make `<packDir>/tools` a symlink to `outsideDir`. The textual containment
    // check on `relativeDir` is fine here ("tools" is innocent), but the
    // canonical-path check inside listFilesystemSiblings must catch the escape.
    val toolsLink = java.nio.file.Files.createSymbolicLink(
      java.nio.file.Paths.get(packDir.absolutePath, "tools"),
      outsideDir.toPath(),
    )
    assertTrue(java.nio.file.Files.isSymbolicLink(toolsLink))

    val source = PackSource.Filesystem(packDir = packDir)

    assertFailsWith<IllegalArgumentException> {
      source.listSiblings(relativeDir = "tools", suffixes = listOf(".tool.yaml"))
    }
  }

  /**
   * Probes whether the current filesystem and JVM permissions allow creating
   * symbolic links. Mirrors the helper in `TrailDiscoveryTest` / `WorkspaceRootTest`
   * — kept local here rather than promoting to a shared util since each test class
   * uses it on a different temp-dir lifecycle and the duplication is ~10 lines.
   */
  private fun supportsSymlinks(): Boolean = try {
    val probeRoot = newTempDir()
    val probeTarget = File(probeRoot, "_symlink-probe-target").apply { mkdirs() }
    val probeLink = File(probeRoot, "_symlink-probe-link").toPath()
    java.nio.file.Files.createSymbolicLink(probeLink, probeTarget.toPath())
    java.nio.file.Files.deleteIfExists(probeLink)
    probeTarget.delete()
    true
  } catch (_: Exception) {
    false
  }

  @Test
  fun `Classpath listSiblings returns only direct children matching the suffix list`() {
    // Build a fake classpath layout: a pack at trailblaze-config/packs/sample/ with
    // a tools/ directory containing direct children of varying suffixes plus a
    // nested subdir that should be filtered out.
    val root = newTempDir()
    val toolsDir = File(root, "trailblaze-config/packs/sample/tools").apply { mkdirs() }
    File(toolsDir, "foo.tool.yaml").writeText("id: foo")
    File(toolsDir, "bar.shortcut.yaml").writeText("id: bar")
    File(toolsDir, "readme.md").writeText("not a tool")
    File(toolsDir, "subdir").mkdirs()
    File(toolsDir, "subdir/nested.tool.yaml").writeText("id: nested")

    classpath.withClasspathRoot(root) {
      val source = PackSource.Classpath(resourceDir = "trailblaze-config/packs/sample")
      val results = source.listSiblings(
        relativeDir = "tools",
        suffixes = listOf(".tool.yaml", ".shortcut.yaml"),
      )

      assertEquals(
        listOf(
          "tools/bar.shortcut.yaml",
          "tools/foo.tool.yaml",
        ),
        results,
        "Expected direct-children matching the suffix list, sorted; got: $results",
      )
    }
  }

  @Test
  fun `Classpath listSiblings rejects path-escape relativeDir values`() {
    val source = PackSource.Classpath(resourceDir = "trailblaze-config/packs/sample")

    assertFailsWith<IllegalArgumentException> {
      source.listSiblings(relativeDir = "../escape", suffixes = listOf(".yaml"))
    }
    assertFailsWith<IllegalArgumentException> {
      source.listSiblings(relativeDir = "%2e%2e/escape", suffixes = listOf(".yaml"))
    }
    assertFailsWith<IllegalArgumentException> {
      source.listSiblings(relativeDir = "", suffixes = listOf(".yaml"))
    }
  }

  private fun newTempDir(): File = classpath.newTempDir(prefix = "pack-source-test")
}
