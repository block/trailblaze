package xyz.block.trailblaze.toolcalls

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Direct tests for [ResolvedTargetIdempotentWrite]. The helper is the shared write/cleanup
 * primitive used by three callers (workspace `ResolvedTargetReportEmitter`, OSS
 * `DocsGenerator`, internal `TargetToolBaselineGenerator`), and `writeSidecarIfChanged` is
 * a security boundary — without dedicated tests at this layer, a future tweak to the
 * canonical-path containment check (or its inversion) could weaken the path-traversal
 * guard without any test bell. The emitter-level tests exercise the helper indirectly
 * but rely on `InlineScriptToolConfig.TOOL_NAME_PATTERN` validation upstream filtering
 * out the bad-name cases before they ever reach the writer — so a weakened guard would
 * pass those tests while still being exploitable from a future call site.
 */
class ResolvedTargetIdempotentWriteTest {

  private val tempDirs = mutableListOf<File>()

  @AfterTest
  fun cleanup() {
    tempDirs.forEach { it.deleteRecursively() }
    tempDirs.clear()
  }

  private fun newDir(name: String): File {
    val parent = createTempDirectory("idempotent-write-test").toFile()
    tempDirs += parent
    return File(parent, name).apply { mkdirs() }
  }

  // ── writeIfChanged ───────────────────────────────────────────────────────────────────

  @Test
  fun `writeIfChanged writes on first call and skips on identical re-write`() {
    val target = File(newDir("a"), "out.md")
    val firstWrote = ResolvedTargetIdempotentWrite.writeIfChanged(target, "hello\n")
    assertTrue("expected first call to write") { firstWrote }
    assertEquals("hello\n", target.readText())
    val firstMtime = target.lastModified()
    Thread.sleep(10) // ensure mtime resolution would catch a write
    val secondWrote = ResolvedTargetIdempotentWrite.writeIfChanged(target, "hello\n")
    assertFalse("expected second call to be a no-op") { secondWrote }
    assertEquals(firstMtime, target.lastModified(), "mtime must not move on no-op write")
  }

  @Test
  fun `writeIfChanged appends a trailing newline when content lacks one`() {
    val target = File(newDir("nl"), "out.md")
    ResolvedTargetIdempotentWrite.writeIfChanged(target, "no-newline-here")
    // Compare against the appended-newline form so a future tweak that drops the appender
    // (and starts producing files that disagree with editors auto-trim conventions) trips
    // this assertion.
    assertEquals("no-newline-here\n", target.readText())
  }

  @Test
  fun `writeIfChanged creates parent directories on demand`() {
    val deepTarget = File(newDir("deep"), "a/b/c/leaf.md")
    assertFalse("parent dir should not yet exist") { deepTarget.parentFile.exists() }
    ResolvedTargetIdempotentWrite.writeIfChanged(deepTarget, "leaf\n")
    assertTrue("parent dir must be created") { deepTarget.parentFile.isDirectory }
    assertEquals("leaf\n", deepTarget.readText())
  }

  // ── writeSidecarIfChanged path-traversal guard ───────────────────────────────────────

  @Test
  fun `writeSidecarIfChanged refuses a parent-relative path-traversal attempt`() {
    // A tool name like `../escape` resolved against the sidecar dir would otherwise land
    // outside the per-target tools/ subdir and corrupt unrelated workspace state. The
    // canonical-path containment check is the belt-and-suspenders guard against this.
    val container = newDir("tools").canonicalFile
    val escape = File(container, "../escape.md")
    val ex = assertFailsWith<IllegalArgumentException> {
      ResolvedTargetIdempotentWrite.writeSidecarIfChanged(container, escape, "evil\n")
    }
    assertTrue("error must name the container so the operator can diagnose") {
      ex.message?.contains("containingDir") == true
    }
    // And nothing should have been written to disk — either inside or outside the container.
    assertFalse("escape file must not exist") { escape.exists() }
    assertFalse("the resolved canonical escape path must not exist") {
      escape.canonicalFile.exists()
    }
  }

  @Test
  fun `writeSidecarIfChanged refuses an absolute path that escapes the container`() {
    val container = newDir("tools").canonicalFile
    // `/tmp/evil` is unambiguously outside `container` regardless of platform. Pass it
    // directly as the file path so the guard fires on absolute-path payloads too.
    val outside = File(container.parentFile.parentFile, "evil.md")
    assertFailsWith<IllegalArgumentException> {
      ResolvedTargetIdempotentWrite.writeSidecarIfChanged(container, outside, "evil\n")
    }
    assertFalse("outside file must not exist") { outside.exists() }
  }

  @Test
  fun `writeSidecarIfChanged allows a legitimate sidecar inside the container`() {
    val container = newDir("tools").canonicalFile
    val legit = File(container, "tapOnPoint.md")
    val wrote = ResolvedTargetIdempotentWrite.writeSidecarIfChanged(
      containingDir = container,
      file = legit,
      content = "ok\n",
    )
    assertTrue("expected legitimate write") { wrote }
    assertEquals("ok\n", legit.readText())
  }

  @Test
  fun `writeSidecarIfChanged delegates to writeIfChanged so unchanged content stays mtime-stable`() {
    val container = newDir("tools").canonicalFile
    val legit = File(container, "stable.md")
    ResolvedTargetIdempotentWrite.writeSidecarIfChanged(container, legit, "stable\n")
    val firstMtime = legit.lastModified()
    Thread.sleep(10)
    val secondWrote = ResolvedTargetIdempotentWrite.writeSidecarIfChanged(container, legit, "stable\n")
    assertFalse("identical content should be a no-op") { secondWrote }
    assertEquals(firstMtime, legit.lastModified(), "mtime must not move on no-op sidecar write")
  }
}
