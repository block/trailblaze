package xyz.block.trailblaze.ui.tabs.trails

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Tests for [TrailsDirectoryScanner], which the desktop UI's trail browser uses to group
 * discovered files into [Trail] objects with platform variants.
 *
 * The scanner sources its flat file set from
 * [xyz.block.trailblaze.config.project.TrailDiscovery] and groups by parent directory.
 * This test pins the grouping rules end-to-end:
 *  - trail id is the scan-root-relative directory path; root-level trails fall back
 *    to the scan dir's own name;
 *  - variants within a single directory are sorted default-first (NL definitions),
 *    then by platform, then by first classifier;
 *  - excluded dirs never appear in the result;
 *  - non-existent / non-directory scan roots return empty without throwing.
 *
 * Without this test the scanner's grouping logic could silently drift (e.g., if a
 * future refactor changes variant sort ordering) and the regression would only be
 * caught when the desktop UI's Trails tab renders wrong.
 */
class TrailsDirectoryScannerTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private val root: File get() = tempFolder.root

  private fun newFile(relativePath: String): File {
    val file = File(root, relativePath)
    file.parentFile?.mkdirs()
    file.writeText("")
    return file
  }

  @Test
  fun `files in different directories become separate trails with relative-path ids`() {
    newFile("flows/login/login.trail.yaml")
    newFile("flows/checkout/checkout.trail.yaml")
    newFile("catalog/overlay/blaze.yaml")

    val trails = TrailsDirectoryScanner.scanForTrails(root)

    val idsToVariantCount = trails.associate { it.id to it.variants.size }
    assertEquals(
      mapOf(
        "flows/login" to 1,
        "flows/checkout" to 1,
        "catalog/overlay" to 1,
      ),
      idsToVariantCount,
    )
  }

  @Test
  fun `multiple variants in one directory are collapsed into a single trail`() {
    // NL definition + two platform-specific recordings in one directory → one Trail
    // with three variants.
    newFile("flows/login/blaze.yaml")
    newFile("flows/login/android-phone.trail.yaml")
    newFile("flows/login/ios-iphone.trail.yaml")

    val trails = TrailsDirectoryScanner.scanForTrails(root)

    assertEquals(1, trails.size, "variants should collapse into one trail: $trails")
    val trail = trails.single()
    assertEquals("flows/login", trail.id)
    assertEquals(3, trail.variants.size)
  }

  @Test
  fun `variants sort default-first then by platform`() {
    // Variants come back in a deterministic order: the NL definition (isDefault=true)
    // leads, then the platform recordings sorted by platform name ascending. A
    // regression that reverses or alphabetizes this differently would surface in the
    // Trails tab's variant chip row; this assertion pins the contract.
    newFile("flows/login/ios-iphone.trail.yaml")
    newFile("flows/login/blaze.yaml")
    newFile("flows/login/android-phone.trail.yaml")

    val trails = TrailsDirectoryScanner.scanForTrails(root)
    val variantFiles = trails.single().variants.map { File(it.absolutePath).name }

    // First entry is the NL definition; subsequent entries are platform recordings
    // in ascending platform order (android before ios).
    assertEquals("blaze.yaml", variantFiles.first(), "default NL variant must lead")
    assertEquals("android-phone.trail.yaml", variantFiles[1], "android before ios")
    assertEquals("ios-iphone.trail.yaml", variantFiles[2])
  }

  @Test
  fun `trails at the scan root use the scan dir's own name as the id`() {
    newFile("login.trail.yaml")

    val trails = TrailsDirectoryScanner.scanForTrails(root)

    assertEquals(1, trails.size)
    // The temp folder's own name is JUnit-assigned (e.g. "junit12345...") — just
    // assert that the fallback fired (id is non-empty, non-path).
    val id = trails.single().id
    assertTrue(id.isNotEmpty(), "expected scan-root fallback id; got empty")
    assertTrue("/" !in id, "expected scan-root fallback to use dir name, not a path: $id")
  }

  @Test
  fun `build dir contents are never surfaced`() {
    // TrailDiscovery prunes build/, .gradle/, etc. — the scanner must propagate that.
    newFile("flows/keeper.trail.yaml")
    newFile("build/generated/stale.trail.yaml")
    newFile(".gradle/cached.trail.yaml")

    val trails = TrailsDirectoryScanner.scanForTrails(root)

    assertEquals(
      setOf("flows"),
      trails.map { it.id }.toSet(),
      "excluded dirs leaked into grouping: $trails",
    )
  }

  @Test
  fun `non-existent scan root returns an empty list`() {
    val missing = File(root, "does-not-exist")

    assertEquals(emptyList(), TrailsDirectoryScanner.scanForTrails(missing))
  }

  @Test
  fun `file as scan root returns an empty list`() {
    val asFile = newFile("stray.trail.yaml")

    assertEquals(emptyList(), TrailsDirectoryScanner.scanForTrails(asFile))
  }

  @Test
  fun `empty scan root returns an empty list`() {
    assertEquals(emptyList(), TrailsDirectoryScanner.scanForTrails(root))
  }

  @Test
  fun `scan results are sorted alphabetically by id`() {
    // Create in non-alphabetic order — the scanner sorts by id (lowercase).
    newFile("zebra/blaze.yaml")
    newFile("alpha/blaze.yaml")
    newFile("mango/blaze.yaml")

    val ids = TrailsDirectoryScanner.scanForTrails(root).map { it.id }

    assertEquals(listOf("alpha", "mango", "zebra"), ids)
  }
}
