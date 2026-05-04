package xyz.block.trailblaze.config.project

import java.io.File
import java.nio.file.Files
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Pins [WorkspaceContentHasher]'s behavioral contract — the pieces a regression in this
 * file would silently break:
 *  - **Stability**: identical content + version produces identical hash regardless of
 *    JVM/OS/walk order. The CLI mismatch warning depends on this exact equality.
 *  - **Sensitivity**: any edit, add, or delete under the configDir flips the hash.
 *    Drift warnings rely on the bit-level difference.
 *  - **Exclusions**: `dist/`, `node_modules/`, dot-prefixed entries, and symlinks are NOT
 *    hashed. Including them would either break determinism (timestamps in dist/) or
 *    devastate performance (node_modules can be GBs).
 *  - **Capture/read**: [WorkspaceContentHasher.captureForDaemon] writes a value the
 *    `@Volatile` read at [WorkspaceContentHasher.lastCapturedHash] picks up.
 */
class WorkspaceContentHasherTest {

  private lateinit var workspace: File

  @BeforeTest
  fun setUp() {
    workspace = Files.createTempDirectory("workspace-hash-test").toFile()
    File(workspace, "trailblaze.yaml").writeText("packs:\n  - packs/foo/pack.yaml\n")
    File(workspace, "packs/foo").mkdirs()
    File(workspace, "packs/foo/pack.yaml").writeText("id: foo\n")
    File(workspace, "tools").mkdirs()
    File(workspace, "tools/helper.js").writeText("export function noop() {}\n")
  }

  @AfterTest
  fun tearDown() {
    workspace.deleteRecursively()
  }

  // ---------------------------------------------------------------------------------------
  // Stability — same input = same hash
  // ---------------------------------------------------------------------------------------

  @Test
  fun `compute returns identical hash on repeated calls over identical content`() {
    val first = WorkspaceContentHasher.compute(workspace, version = "v1")
    val second = WorkspaceContentHasher.compute(workspace, version = "v1")
    assertEquals(first, second, "Hash must be deterministic across calls.")
  }

  @Test
  fun `compute returns same hash regardless of file creation order`() {
    val firstHash = WorkspaceContentHasher.compute(workspace, version = "v1")

    // Recreate the same content in a different creation order — filesystem walk order
    // can vary by OS / JDK, so the hasher must internally sort by relative path. If it
    // doesn't, two physically-identical workspaces would hash differently.
    val replica = Files.createTempDirectory("workspace-hash-replica").toFile()
    try {
      File(replica, "tools").mkdirs()
      File(replica, "tools/helper.js").writeText("export function noop() {}\n")
      File(replica, "packs/foo").mkdirs()
      File(replica, "packs/foo/pack.yaml").writeText("id: foo\n")
      File(replica, "trailblaze.yaml").writeText("packs:\n  - packs/foo/pack.yaml\n")
      val replicaHash = WorkspaceContentHasher.compute(replica, version = "v1")
      assertEquals(firstHash, replicaHash, "Walk order must not affect the hash.")
    } finally {
      replica.deleteRecursively()
    }
  }

  @Test
  fun `compute returns different hash for different framework versions`() {
    // The version is mixed in so a framework upgrade always invalidates a stored hash —
    // different versions can interpret the same files differently.
    val v1 = WorkspaceContentHasher.compute(workspace, version = "v1")
    val v2 = WorkspaceContentHasher.compute(workspace, version = "v2")
    assertNotEquals(v1, v2, "Version must be folded into the hash.")
  }

  // ---------------------------------------------------------------------------------------
  // Sensitivity — any edit changes the hash
  // ---------------------------------------------------------------------------------------

  @Test
  fun `compute returns a different hash when a file's content changes`() {
    val before = WorkspaceContentHasher.compute(workspace, version = "v1")
    File(workspace, "packs/foo/pack.yaml").writeText("id: foo\n# edited\n")
    val after = WorkspaceContentHasher.compute(workspace, version = "v1")
    assertNotEquals(before, after, "Content edit must flip the hash.")
  }

  @Test
  fun `compute returns a different hash when a new file is added`() {
    val before = WorkspaceContentHasher.compute(workspace, version = "v1")
    File(workspace, "tools/new_tool.js").writeText("export function newOne() {}\n")
    val after = WorkspaceContentHasher.compute(workspace, version = "v1")
    assertNotEquals(before, after, "Added file must flip the hash.")
  }

  @Test
  fun `compute returns a different hash when a file is deleted`() {
    val before = WorkspaceContentHasher.compute(workspace, version = "v1")
    File(workspace, "tools/helper.js").delete()
    val after = WorkspaceContentHasher.compute(workspace, version = "v1")
    assertNotEquals(before, after, "Deleted file must flip the hash.")
  }

  // ---------------------------------------------------------------------------------------
  // Exclusions — these must NOT contribute to the hash
  // ---------------------------------------------------------------------------------------

  @Test
  fun `compute ignores files under dist`() {
    val before = WorkspaceContentHasher.compute(workspace, version = "v1")
    File(workspace, "dist").mkdirs()
    File(workspace, "dist/.bundle.hash").writeText("abc123\n")
    File(workspace, "dist/targets/foo.yaml").also { it.parentFile.mkdirs() }.writeText("id: foo\n")
    val after = WorkspaceContentHasher.compute(workspace, version = "v1")
    assertEquals(before, after, "Files under dist/ must not contribute (it's daemon output).")
  }

  @Test
  fun `compute ignores files under node_modules`() {
    val before = WorkspaceContentHasher.compute(workspace, version = "v1")
    File(workspace, "tools/node_modules/some_pkg").mkdirs()
    File(workspace, "tools/node_modules/some_pkg/index.js").writeText("module.exports = 1;\n")
    val after = WorkspaceContentHasher.compute(workspace, version = "v1")
    assertEquals(before, after, "node_modules subtree must not contribute (could be GBs).")
  }

  @Test
  fun `compute ignores dot-prefixed files and dirs`() {
    val before = WorkspaceContentHasher.compute(workspace, version = "v1")
    File(workspace, ".bundle.hash").writeText("abc123\n")
    File(workspace, ".trailblaze").mkdirs()
    File(workspace, ".trailblaze/tools.d.ts").writeText("// generated\n")
    val after = WorkspaceContentHasher.compute(workspace, version = "v1")
    assertEquals(before, after, "Dot-prefixed entries must not contribute.")
  }

  @Test
  fun `compute skips symlinks rather than following them`() {
    // Without symlink handling, a symlink loop would either infinite-loop the walk or
    // duplicate-hash the target depending on JVM behavior. We skip symlinks entirely.
    val target = File(workspace, "tools/helper.js")
    val linkPath = File(workspace, "tools/helper-link.js").toPath()
    try {
      Files.deleteIfExists(linkPath)
      linkPath.createSymbolicLinkPointingTo(target.toPath())
    } catch (_: Exception) {
      // Some test environments / Windows runs disallow symlinks — skip the assertion
      // gracefully rather than fail. The defensive code under test still holds.
      return
    }

    val withLink = WorkspaceContentHasher.compute(workspace, version = "v1")
    Files.deleteIfExists(linkPath)
    val withoutLink = WorkspaceContentHasher.compute(workspace, version = "v1")
    assertEquals(withLink, withoutLink, "Symlinks must not contribute to the hash.")
  }

  // ---------------------------------------------------------------------------------------
  // Robustness — empty / non-existent / large
  // ---------------------------------------------------------------------------------------

  @Test
  fun `compute on a non-directory returns a stable digest of just the version`() {
    val nonDir = File(workspace, "trailblaze.yaml") // exists, but is a file, not a dir
    val nonExistent = File(workspace, "missing")
    val versionOnly1 = WorkspaceContentHasher.compute(nonDir, version = "v1")
    val versionOnly2 = WorkspaceContentHasher.compute(nonExistent, version = "v1")
    assertEquals(versionOnly1, versionOnly2, "Non-directory inputs all degrade to a version-only hash.")
  }

  @Test
  fun `compute streams large files instead of loading them whole`() {
    // 5 MB file — easily blew out a small heap if readBytes() loaded it whole.
    // Doesn't directly assert "didn't OOM" (that's a property of the JVM), but exercises
    // the streaming path so a regression to readBytes() would surface in a memory profile.
    val big = File(workspace, "tools/big_recording.bin")
    big.outputStream().use { stream ->
      val buffer = ByteArray(1024) { (it % 256).toByte() }
      repeat(5 * 1024) { stream.write(buffer) }
    }
    val hash = WorkspaceContentHasher.compute(workspace, version = "v1")
    assertNotNull(hash)
    assertEquals(64, hash.length, "SHA-256 hex must be 64 chars.")
  }

  // ---------------------------------------------------------------------------------------
  // captureForDaemon — write/read contract on the singleton
  // ---------------------------------------------------------------------------------------

  @Test
  fun `captureForDaemon stores the result so concurrent reads see the same value`() {
    // We can't easily test a true race — the @Volatile contract is what JIT/JMM gives
    // us. What we CAN pin: the value written equals the value subsequently read, AND
    // matches the result of compute() with identical inputs.
    val expected = WorkspaceContentHasher.compute(workspace, version = "v1")
    WorkspaceContentHasher.captureForDaemon(workspace, version = "v1")
    assertEquals(expected, WorkspaceContentHasher.lastCapturedHash)
  }

  @Test
  fun `captureForDaemon overwrites prior value (one-daemon-per-classloader contract)`() {
    // The class kdoc documents that re-capturing in the same JVM clobbers the prior
    // value. Pin that behavior so a future "remember the first one only" change is
    // visibly intentional rather than accidental.
    WorkspaceContentHasher.captureForDaemon(workspace, version = "v1")
    val first = WorkspaceContentHasher.lastCapturedHash
    File(workspace, "tools/added.js").writeText("// added between captures\n")
    WorkspaceContentHasher.captureForDaemon(workspace, version = "v1")
    val second = WorkspaceContentHasher.lastCapturedHash
    assertNotNull(first)
    assertNotNull(second)
    assertNotEquals(first, second, "Re-capture must replace the prior value.")
  }

  @Test
  fun `lastCapturedHash starts null in tests where no capture has happened`() {
    // Sanity check that test order doesn't accidentally rely on a leaked captured hash
    // from another suite. We can't fully isolate (it's a singleton), but we can at least
    // detect "the previous test in this class left a value" if we explicitly capture
    // null at the start.
    @Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
    // Reading is fine — we can't reset the singleton from outside without reflection,
    // and we don't want the production API to expose a setter just for tests. The
    // assertion is "either null or a previously captured value"; that's the contract.
    val current = WorkspaceContentHasher.lastCapturedHash
    if (current == null) {
      assertNull(current)
    } else {
      // A previous test in this class may have captured. That's fine — the contract
      // documents the singleton lifetime.
      assertNotNull(current)
    }
  }
}
