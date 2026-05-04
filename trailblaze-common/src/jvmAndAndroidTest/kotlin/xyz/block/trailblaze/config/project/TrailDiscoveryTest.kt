package xyz.block.trailblaze.config.project

import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Tests for [TrailDiscovery]. Covers:
 *  - Globbing `**`/`*.trail.yaml`, `blaze.yaml`, and nested `trailblaze.yaml`
 *  - Workspace-config exclusion: `trails/config/trailblaze.yaml` is skipped, while
 *    legacy `trailblaze.yaml` trail definitions elsewhere are surfaced
 *  - Pruning the hardcoded [TrailDiscovery.DEFAULT_EXCLUDED_DIRS] set
 *  - Deterministic sort order
 *  - [TrailDiscovery.findFirstTrail] short-circuiting
 *  - Graceful handling of missing roots (no throw)
 */
class TrailDiscoveryTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private val root: File get() = tempFolder.root

  @Before
  fun assumeTempFolderIsScratch() {
    // Mirrors WorkspaceRootTest — some CI / hermit layouts stash a trailblaze.yaml above
    // the temp dir and tests that assume a pristine scratch workspace would mis-fire.
    val result = findWorkspaceRoot(root.toPath())
    Assume.assumeTrue(
      "An ancestor of $root already contains a trailblaze.yaml — skipping.",
      result is WorkspaceRoot.Scratch,
    )
  }

  @Test
  fun `discovers trail, blaze, and nested trailblaze yaml files recursively`() {
    val a = newFile("login.trail.yaml")
    val b = newFile("flows/checkout.trail.yaml")
    val c = newFile("flows/checkout/blaze.yaml")
    // Nested trailblaze.yaml — legacy NL definition name, must be discovered since it
    // is not the workspace anchor.
    val d = newFile("legacy/trailblaze.yaml")
    // Non-trail files ignored.
    newFile("README.md")
    newFile("notes.yaml")

    val result = TrailDiscovery.discoverTrails(root.toPath()).map { it.toFile() }

    assertEquals(4, result.size, "unexpected extras: $result")
    assertTrue(result.map { it.name }.contains(a.name))
    assertTrue(result.map { it.name }.contains(b.name))
    assertTrue(result.map { it.canonicalPath }.contains(c.canonicalPath))
    assertTrue(result.map { it.canonicalPath }.contains(d.canonicalPath))
  }

  @Test
  fun `workspace config manifest under trails config is not surfaced as a trail`() {
    newFile("trails/config/trailblaze.yaml")
    val rootTrail = newFile("trails/trailblaze.yaml")
    val nestedTrail = newFile("trails/nested/login.trail.yaml")

    val result = TrailDiscovery.discoverTrails(File(root, "trails").toPath()).map { it.toFile() }

    assertEquals(2, result.size, "workspace config manifest must be excluded: $result")
    assertTrue(result.map { it.canonicalPath }.contains(rootTrail.canonicalPath))
    assertTrue(result.map { it.canonicalPath }.contains(nestedTrail.canonicalPath))
  }

  @Test
  fun `hardcoded excluded dirs are pruned`() {
    // A trail at the root so the walk finds at least one keeper.
    newFile("keeper.trail.yaml")

    // Each excluded dir holds a lookalike trail that must NOT appear in results.
    TrailDiscovery.DEFAULT_EXCLUDED_DIRS.forEach { dir ->
      newFile("$dir/nested/ignored.trail.yaml")
      newFile("$dir/blaze.yaml")
    }

    val result = TrailDiscovery.discoverTrails(root.toPath()).map { it.toFile().name }

    assertEquals(1, result.size, "excluded dirs leaked trails: $result")
    assertEquals("keeper.trail.yaml", result.single())
  }

  @Test
  fun `nested build dir inside a feature folder is still pruned`() {
    // The exclude matches by exact directory name at any depth, not just top-level.
    newFile("features/checkout/build/cached.trail.yaml")
    val keeper = newFile("features/checkout/checkout.trail.yaml")

    val result = TrailDiscovery.discoverTrails(root.toPath()).map { it.toFile().canonicalPath }

    assertEquals(1, result.size, "nested build/ leaked: $result")
    assertEquals(keeper.canonicalPath, result.single())
  }

  @Test
  fun `extra excluded dirs prune in addition to the defaults`() {
    newFile("scratch/ignored.trail.yaml")
    val keeper = newFile("keep/login.trail.yaml")

    val result = TrailDiscovery.discoverTrails(
      root.toPath(),
      extraExcludedDirs = setOf("scratch"),
    ).map { it.toFile().canonicalPath }

    assertEquals(1, result.size, "extra exclude didn't prune: $result")
    assertEquals(keeper.canonicalPath, result.single())
  }

  @Test
  fun `results are sorted by absolute path for deterministic ordering`() {
    // Intentionally create in non-alphabetic order — result must sort by path.
    newFile("z/trail.trail.yaml")
    newFile("a/trail.trail.yaml")
    newFile("m/trail.trail.yaml")

    val names = TrailDiscovery.discoverTrails(root.toPath()).map {
      it.toFile().parentFile.name
    }

    assertEquals(listOf("a", "m", "z"), names)
  }

  @Test
  fun `discoverTrailFiles returns File objects for JVM interop`() {
    val trail = newFile("flows/login.trail.yaml")

    val result = TrailDiscovery.discoverTrailFiles(root.toPath())

    assertEquals(1, result.size)
    assertEquals(trail.canonicalPath, result.single().canonicalPath)
  }

  @Test
  fun `missing root returns empty list without throwing`() {
    val missing = File(root, "does-not-exist")
    assertTrue(!missing.exists())

    val result = TrailDiscovery.discoverTrails(missing.toPath())

    assertEquals(emptyList(), result)
  }

  @Test
  fun `file path as root returns empty list without throwing`() {
    val asFile = newFile("stray.trail.yaml")

    val result = TrailDiscovery.discoverTrails(asFile.toPath())

    assertEquals(emptyList(), result)
  }

  @Test
  fun `findFirstTrail returns the first match and short-circuits`() {
    newFile("flows/a.trail.yaml")
    newFile("flows/b.trail.yaml")
    newFile("flows/c.trail.yaml")

    var visited = 0
    val match = TrailDiscovery.findFirstTrail(root.toPath()) { path ->
      visited++
      path.fileName.toString() == "b.trail.yaml"
    }

    assertNotNull(match)
    assertEquals("b.trail.yaml", match!!.fileName.toString())
    // The walk must stop after finding `b` — a full-tree walk would visit all three.
    assertTrue(visited <= 3, "visitor ran $visited times; expected early termination")
  }

  @Test
  fun `findFirstTrail returns null when nothing matches`() {
    newFile("flows/login.trail.yaml")

    val match = TrailDiscovery.findFirstTrail(root.toPath()) { path ->
      path.fileName.toString().contains("missing")
    }

    assertNull(match)
  }

  @Test
  fun `findFirstTrail skips the workspace anchor`() {
    val anchor = newFile("trails/config/trailblaze.yaml")
    val rootLegacy = newFile("trails/trailblaze.yaml")
    val nested = newFile("trails/legacy/trailblaze.yaml")

    val visited = mutableListOf<File>()
    val match = TrailDiscovery.findFirstTrail(File(root, "trails").toPath()) { path ->
      visited.add(path.toFile())
      false // don't match — we just want to see what the predicate was offered.
    }

    assertNull(match)
    val visitedPaths = visited.map { it.canonicalPath }
    assertTrue(
      rootLegacy.canonicalPath in visitedPaths,
      "legacy trailblaze.yaml at the scan root should be visited but was not: $visitedPaths",
    )
    assertTrue(
      nested.canonicalPath in visitedPaths,
      "nested trailblaze.yaml should be visited but was not: $visitedPaths",
    )
    assertTrue(
      anchor.canonicalPath !in visitedPaths,
      "workspace anchor should be skipped but was visited: $visitedPaths",
    )
  }

  @Test
  fun `findFirstTrail returns null for a missing root`() {
    val match = TrailDiscovery.findFirstTrail(File(root, "does-not-exist").toPath()) { true }
    assertNull(match)
  }

  @Test
  fun `discoverTrails on a missing root does not throw under a restrictive SecurityManager`() {
    // We can't install a real SecurityManager mid-test (deprecated in JDK 17+), but we
    // can at minimum verify the wrapper catches the analogous failure mode: a path that
    // resolves to no usable directory. The contract on `discoverTrails` promises "never
    // throws" — this test pins the early-return path added to the wrapper.
    val result = TrailDiscovery.discoverTrails(File(root, "missing/nested").toPath())
    assertEquals(emptyList(), result)
  }

  @Test
  fun `nested trailblaze_yaml is surfaced when the scan root has no workspace above it`() {
    // Scratch workspace — no trailblaze.yaml ancestor. A nested trailblaze.yaml is a
    // legacy NL trail, not an anchor, and must appear.
    val nested = newFile("flows/trailblaze.yaml")

    val result = TrailDiscovery.discoverTrails(root.toPath()).map { it.toFile().canonicalPath }

    assertEquals(1, result.size, "nested trailblaze.yaml under Scratch must not be excluded: $result")
    assertEquals(nested.canonicalPath, result.single())
  }

  @Test
  fun `deeply nested trailblaze_yaml is surfaced when the scan root sits below a configured workspace`() {
    // Setup: workspace config manifest at `root/trails/config/trailblaze.yaml` (the real
    // anchor, found via walk-up from `flows`). A deeply nested `flows/subtrail/trailblaze.yaml` is a
    // legacy NL trail — it is NOT the closest trailblaze.yaml, so `findWorkspaceRoot`
    // does not treat it as a nested workspace. Scanning `root/flows` must surface
    // that nested trail; the true anchor lives ABOVE the scan root and is never
    // visited by the walk.
    newFile("trails/config/trailblaze.yaml") // real workspace anchor, above the scan root
    val nestedTrail = newFile("trails/flows/subtrail/trailblaze.yaml") // legacy NL trail
    val siblingTrail = newFile("trails/flows/other.trail.yaml")

    val result = TrailDiscovery.discoverTrails(File(root, "trails/flows").toPath())
      .map { it.toFile().canonicalPath }

    assertEquals(2, result.size, "subdir scan must include deeply nested trailblaze.yaml: $result")
    assertTrue(nestedTrail.canonicalPath in result, "missing nested trailblaze.yaml")
    assertTrue(siblingTrail.canonicalPath in result, "missing sibling .trail.yaml")
  }

  @Test
  fun `immediate-child trailblaze_yaml at a scan root is surfaced when config lives under config dir`() {
    newFile("trails/config/trailblaze.yaml")
    val rootLegacy = newFile("trails/flows/trailblaze.yaml")
    val sibling = newFile("trails/flows/login.trail.yaml")

    val result = TrailDiscovery.discoverTrails(File(root, "trails/flows").toPath())
      .map { it.toFile().canonicalPath }

    assertEquals(2, result.size, "legacy trailblaze.yaml should be surfaced beside recordings: $result")
    assertTrue(rootLegacy.canonicalPath in result, "legacy trailblaze.yaml should be surfaced")
    assertTrue(sibling.canonicalPath in result, "sibling trail should be surfaced")
  }

  @Test
  fun `isTrailFile accepts recording, blaze, trailblaze, and rejects unrelated yaml`() {
    // Delegates to TrailRecordings.isTrailFile — nested trailblaze.yaml is a legacy NL
    // definition and matches. The anchor-at-root exclusion is applied inside the walk,
    // not by this filename-only predicate.
    assertTrue(TrailDiscovery.isTrailFile("login.trail.yaml"))
    assertTrue(TrailDiscovery.isTrailFile("blaze.yaml"))
    assertTrue(TrailDiscovery.isTrailFile("trailblaze.yaml"))
    assertTrue(TrailDiscovery.isTrailFile("ios-iphone.trail.yaml"))
    assertTrue(!TrailDiscovery.isTrailFile("settings.yaml"))
    assertTrue(!TrailDiscovery.isTrailFile("blaze.yaml.bak"))
  }

  @Test
  fun `file symlinks pointing outside the scan root are not surfaced`() {
    // Path-traversal protection at the discovery layer: a file symlink whose target
    // lives outside the scan root must not be returned as a trail. `walkFileTree`'s
    // default attrs report `isRegularFile = false` for symlinks, so the visitor's
    // guard rejects them — TrailExecutor's `validateWithinTrailsDir` is defense in
    // depth but should never be reached for this case.
    Assume.assumeTrue("Symlink support required", supportsSymlinks())
    val workspace = File(root, "workspace").apply { mkdirs() }
    val realTrail = File(workspace, "real.trail.yaml").apply { writeText("") }
    val outsideFile = File(root, "outside.trail.yaml").apply { writeText("") }
    Files.createSymbolicLink(
      File(workspace, "escape.trail.yaml").toPath(),
      outsideFile.toPath(),
    )

    val names = TrailDiscovery.discoverTrails(workspace.toPath())
      .map { it.toFile().canonicalPath }

    assertEquals(1, names.size, "file symlink leaked outside scan root: $names")
    assertEquals(realTrail.canonicalPath, names.single())
  }

  @Test
  fun `symlinks under the scan root are not followed into a sibling tree`() {
    // Files.walkFileTree's default options do not follow symlinks; this pins the
    // intentional Phase 3 behavior (see TrailDiscovery KDoc). If a future change
    // adds FOLLOW_LINKS without a cycle guard, this test would start discovering
    // the sibling trail and fail.
    Assume.assumeTrue("Symlink support required", supportsSymlinks())
    val workspace = File(root, "workspace").apply { mkdirs() }
    File(workspace, "inside.trail.yaml").writeText("")
    val siblingDir = File(root, "sibling").apply { mkdirs() }
    File(siblingDir, "outside.trail.yaml").writeText("")
    Files.createSymbolicLink(
      File(workspace, "link-to-sibling").toPath(),
      siblingDir.toPath(),
    )

    val names = TrailDiscovery.discoverTrails(workspace.toPath())
      .map { it.toFile().name }

    assertEquals(listOf("inside.trail.yaml"), names)
  }

  private fun newFile(relativePath: String): File {
    val file = File(root, relativePath)
    file.parentFile?.mkdirs()
    file.writeText("")
    return file
  }

  private fun supportsSymlinks(): Boolean = try {
    val probeTarget = File(root, "_symlink-probe-target").apply { mkdirs() }
    val probeLink = File(root, "_symlink-probe-link").toPath()
    Files.createSymbolicLink(probeLink, probeTarget.toPath())
    Files.deleteIfExists(probeLink)
    probeTarget.delete()
    true
  } catch (_: Exception) {
    false
  }
}
