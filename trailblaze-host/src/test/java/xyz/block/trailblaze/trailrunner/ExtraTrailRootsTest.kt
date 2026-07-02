package xyz.block.trailblaze.trailrunner

import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExtraTrailRootsTest {

  @get:Rule
  val tmp = TemporaryFolder()

  private lateinit var stateBefore: List<String>

  private val addedPaths = mutableListOf<String>()

  @Before
  fun snapshotState() {
    stateBefore = ExtraTrailRoots.list().toList()
  }

  @After
  fun tearDown() {
    for (path in addedPaths) {
      ExtraTrailRoots.remove(path)
    }
    val after = ExtraTrailRoots.list()
    for (path in after) {
      if (path !in stateBefore) ExtraTrailRoots.remove(path)
    }
  }

  private fun addTracked(path: String): List<String> {
    addedPaths += path
    return ExtraTrailRoots.add(path)
  }

  @Test
  fun `list returns a list (may be empty or non-empty)`() {
    val result = ExtraTrailRoots.list()
    assertTrue(result is List<String>)
  }

  @Test
  fun `add inserts a new path and it appears in list`() {
    val dir = tmp.newFolder("extra-trails")
    addTracked(dir.absolutePath)

    val canonical = dir.canonicalPath
    assertTrue(
      canonical in ExtraTrailRoots.list(),
      "expected $canonical in list after add",
    )
  }

  @Test
  fun `add is idempotent for the same path`() {
    val dir = tmp.newFolder("dedup-trails")
    addTracked(dir.absolutePath)
    val sizeBefore = ExtraTrailRoots.list().size

    addTracked(dir.absolutePath)
    val sizeAfter = ExtraTrailRoots.list().size

    assertEquals(sizeBefore, sizeAfter, "duplicate add should not increase list size")
  }

  @Test
  fun `add canonicalizes path`() {
    val dir = tmp.newFolder("canon-trails")
    val nonCanonical = dir.absolutePath + "/."
    addTracked(nonCanonical)

    val canonical = dir.canonicalPath
    assertTrue(
      canonical in ExtraTrailRoots.list(),
      "expected canonical form $canonical in list",
    )
  }

  @Test
  fun `add returns new list containing the added path`() {
    val dir = tmp.newFolder("return-check-trails")
    val result = addTracked(dir.absolutePath)

    assertTrue(dir.canonicalPath in result, "add() return value should contain the new path")
  }

  @Test
  fun `remove eliminates a previously added path`() {
    val dir = tmp.newFolder("remove-trails")
    addTracked(dir.absolutePath)

    val canonical = dir.canonicalPath
    ExtraTrailRoots.remove(canonical)
    addedPaths.remove(dir.absolutePath)

    assertFalse(
      canonical in ExtraTrailRoots.list(),
      "path should not appear in list after remove",
    )
  }

  @Test
  fun `remove is a no-op for a path that was never added`() {
    val sizeBefore = ExtraTrailRoots.list().size
    ExtraTrailRoots.remove("/tmp/no-such-path-xyz-trailblaze-test")
    assertEquals(sizeBefore, ExtraTrailRoots.list().size)
  }

  @Test
  fun `remove returns the updated list`() {
    val dir = tmp.newFolder("remove-return-trails")
    val canonical = dir.canonicalPath
    addTracked(dir.absolutePath)

    val result = ExtraTrailRoots.remove(canonical)
    addedPaths.remove(dir.absolutePath)

    assertFalse(canonical in result, "remove() return value should not contain the removed path")
  }

  @Test
  fun `add then remove leaves the list unchanged`() {
    val sizeBefore = stateBefore.size
    val dir = tmp.newFolder("round-trip-trails")
    addTracked(dir.absolutePath)
    val canonical = dir.canonicalPath
    ExtraTrailRoots.remove(canonical)
    addedPaths.remove(dir.absolutePath)

    assertEquals(sizeBefore, ExtraTrailRoots.list().size, "list size should be restored after add+remove")
  }

  @Test
  fun `adding path and its canonical form are treated as duplicates`() {
    val dir = tmp.newFolder("dedup-canon")
    val canonical = dir.canonicalPath
    addTracked(canonical)
    val sizeAfterFirst = ExtraTrailRoots.list().size

    addTracked(canonical + "/.")
    val sizeAfterSecond = ExtraTrailRoots.list().size

    assertEquals(sizeAfterFirst, sizeAfterSecond, "equivalent paths should not both be stored")
  }
}
