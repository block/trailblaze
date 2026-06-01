package xyz.block.trailblaze.mcp.newtools

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.mcp.RecordedStep
import xyz.block.trailblaze.mcp.RecordedStepType

/**
 * Tests for [TrailFileManager] — the callers route trail enumeration through
 * [xyz.block.trailblaze.config.project.TrailDiscovery].
 *
 * The surface under test:
 *  - [TrailFileManager.findTrailByName] now calls `TrailDiscovery.findFirstTrail`,
 *    which short-circuits; these tests pin the unified discovery contract
 *    (`.trail.yaml` + `blaze.yaml` + nested `trailblaze.yaml`) and the build-output
 *    exclusion end-to-end, not just at the discovery layer.
 *  - [TrailFileManager.listTrails] now paginates before reading YAML titles when no
 *    filter is supplied; these tests cover the page-boundary arithmetic and confirm
 *    hardcoded excludes propagate through the listing.
 */
class TrailFileManagerTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private val trailsDir: File get() = tempFolder.root

  private fun newTrail(relativePath: String): File {
    val file = File(trailsDir, relativePath)
    file.parentFile?.mkdirs()
    // Minimal parseable trail so `readTrailTitle` doesn't throw — the title isn't
    // asserted, only that pagination does not open files outside its page slice.
    file.writeText("steps: []\n")
    return file
  }

  private fun manager() = TrailFileManager(trailsDir.absolutePath)

  @Test
  fun `findTrailByName matches a nested blaze yaml by parent-directory name`() {
    // The unified contract (e365c93) discovers blaze.yaml alongside .trail.yaml.
    // A user searching for "checkout" should resolve to `flows/checkout/blaze.yaml`
    // via the parent-dir-name match in `findTrailByName`.
    newTrail("flows/checkout/blaze.yaml")

    val result = manager().findTrailByName("checkout")

    assertNotNull(result, "findTrailByName did not resolve blaze.yaml by parent-dir name")
    assertTrue(result.endsWith("/flows/checkout/blaze.yaml"), "unexpected path: $result")
  }

  @Test
  fun `findTrailByName ignores matches under build directory`() {
    // Sanity that TrailDiscovery's hardcoded excludes propagate through
    // TrailFileManager's name lookup — a stale cached trail under `build/` must not
    // be offered as a resolution for the same trail name outside `build/`.
    newTrail("build/flows/login/login.trail.yaml")
    newTrail("flows/login/login.trail.yaml")

    val result = manager().findTrailByName("login")

    assertNotNull(result)
    assertTrue(
      !result.contains("${File.separator}build${File.separator}"),
      "findTrailByName returned an excluded-dir path: $result",
    )
  }

  @Test
  fun `findTrailByName returns null when nothing matches`() {
    newTrail("flows/login.trail.yaml")

    val result = manager().findTrailByName("totally-unknown")

    assertNull(result)
  }

  @Test
  fun `listTrails paginates across multiple pages in sorted order`() {
    // Create 25 trails — two full pages of 10 and a short third page of 5.
    repeat(25) { newTrail("flows/trail-${it.toString().padStart(2, '0')}.trail.yaml") }

    val page1 = manager().listTrails(page = 1, pageSize = 10)
    val page2 = manager().listTrails(page = 2, pageSize = 10)
    val page3 = manager().listTrails(page = 3, pageSize = 10)

    assertEquals(25, page1.totalCount)
    assertEquals(10, page1.trails.size)
    assertTrue(page1.hasMore)
    assertEquals(10, page2.trails.size)
    assertTrue(page2.hasMore)
    assertEquals(5, page3.trails.size)
    assertTrue(!page3.hasMore, "short final page should not claim hasMore")

    // Pages must be disjoint and reconstruct the total set in absolute-path order.
    val concatenated = page1.trails.map { it.path } +
      page2.trails.map { it.path } +
      page3.trails.map { it.path }
    assertEquals(concatenated, concatenated.sorted(), "page ordering is not stable across pages")
    assertEquals(25, concatenated.toSet().size, "pages overlap: $concatenated")
  }

  @Test
  fun `listTrails returns empty page past the end without crashing`() {
    repeat(3) { newTrail("trail-$it.trail.yaml") }

    val page = manager().listTrails(page = 99, pageSize = 10)

    assertEquals(3, page.totalCount)
    assertEquals(0, page.trails.size)
    assertTrue(!page.hasMore)
  }

  @Test
  fun `listTrails filter matches a trail's title even when the path does not contain it`() {
    // Pins the slow-path branch (filter != null): readTrailTitle reads every YAML to
    // evaluate `info.title.contains(filter)`. A regression that moves the filter
    // before the YAML-read or drops title-matching would surface here.
    val titled = File(trailsDir, "obscure-path/a.trail.yaml").apply {
      parentFile?.mkdirs()
      writeText(
        """
        - config:
            id: "matches-only-by-title"
            title: "Checkout Happy Path"
        """.trimIndent(),
      )
    }
    val unrelated = newTrail("flows/settings.trail.yaml")

    val page = manager().listTrails(filter = "Checkout")

    assertEquals(1, page.totalCount, "title-only filter hit: ${page.trails}")
    assertEquals(titled.relativeTo(trailsDir).path, page.trails.single().path)
    assertEquals("Checkout Happy Path", page.trails.single().title)
    // Sanity — the unrelated trail was discovered but filtered out by the title+path
    // predicate; it must not appear in the page.
    assertTrue(page.trails.none { it.path == unrelated.relativeTo(trailsDir).path })
  }

  @Test
  fun `listTrails excludes build output trails`() {
    newTrail("flows/login.trail.yaml")
    newTrail("build/generated/stale.trail.yaml")
    newTrail(".gradle/cached.trail.yaml")

    val page = manager().listTrails()

    assertEquals(1, page.totalCount, "excluded dirs leaked into listing: ${page.trails}")
    assertEquals("flows/login.trail.yaml", page.trails.single().path)
  }

  @Test
  fun `trailNameToDirSlug collapses internal dash runs from spaced separators`() {
    // The original sanitizer (`name.replace(" ", "-").lowercase()`) mapped each
    // character of " - " to its own '-' and produced "cli-review-test---example.com".
    // Pin the collapsed-run behavior so the run-collapse can't silently regress.
    assertEquals(
      "cli-review-test-example.com",
      trailNameToDirSlug("CLI review test - example.com"),
    )
  }

  @Test
  fun `trailNameToDirSlug trims leading and trailing dashes`() {
    assertEquals("trail", trailNameToDirSlug("  trail  "))
    assertEquals("trail", trailNameToDirSlug("--trail--"))
  }

  @Test
  fun `saveTrail rejects a blank-slug title before writing to disk`() {
    // Regression for codex PR review on #3629: a title of " - " or "---" used to
    // sanitize to "" and then `File(dir, "")` resolved to the trails root, so
    // saveTrail would silently write `<platform>.trail.yaml` at the root,
    // potentially clobbering an unrelated trail file. The empty-slug guard in
    // validateTrailNameSlug closes that hole.
    val sentinel = newTrail("android.trail.yaml") // a pre-existing top-level trail
    val sentinelBytesBefore = sentinel.readBytes()

    val steps = listOf(
      RecordedStep(
        type = RecordedStepType.STEP,
        input = "Tap login",
        result = "ok",
        success = true,
      ),
    )
    val result = manager().saveTrail(
      name = " - ",
      steps = steps,
      platform = TrailblazeDevicePlatform.ANDROID,
    )

    assertTrue(!result.success, "blank-slug saveTrail should fail; got: $result")
    val error = assertNotNull(result.error, "blank-slug saveTrail must surface an error message")
    assertTrue(
      error.contains("alphanumeric") || error.contains("empty slug"),
      "error message should explain the blank-slug cause; got: $error",
    )
    assertEquals(
      sentinelBytesBefore.toList(),
      sentinel.readBytes().toList(),
      "saveTrail must NOT overwrite a root-level sibling when the slug is blank",
    )
  }

  @Test
  fun `saveTrail rejects a dashes-only title that would sanitize to empty`() {
    val steps = listOf(
      RecordedStep(
        type = RecordedStepType.STEP,
        input = "Tap login",
        result = "ok",
        success = true,
      ),
    )
    val result = manager().saveTrail(
      name = "---",
      steps = steps,
      platform = TrailblazeDevicePlatform.ANDROID,
    )

    assertTrue(!result.success, "dashes-only saveTrail should fail; got: $result")
    // No platform-named file should be created at the trails root either.
    assertTrue(
      !File(trailsDir, "android.trail.yaml").exists(),
      "saveTrail must not create a root-level <platform>.trail.yaml on rejection",
    )
  }
}
