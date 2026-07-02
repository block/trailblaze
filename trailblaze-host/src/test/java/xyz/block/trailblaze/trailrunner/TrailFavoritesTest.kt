package xyz.block.trailblaze.trailrunner

import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrailFavoritesTest {

  private lateinit var stateBefore: List<String>

  private val addedIds = mutableListOf<String>()

  @Before
  fun snapshotState() {
    stateBefore = TrailFavorites.list().toList()
  }

  @After
  fun tearDown() {
    for (id in addedIds) TrailFavorites.remove(id)
    for (id in TrailFavorites.list()) {
      if (id !in stateBefore) TrailFavorites.remove(id)
    }
  }

  private fun addTracked(id: String): List<String> {
    addedIds += id
    return TrailFavorites.add(id)
  }

  @Test
  fun `list returns a non-null list`() {
    assertTrue(TrailFavorites.list() is List<String>)
  }

  @Test
  fun `add inserts a new id and it appears in list`() {
    val id = "0/test/favorite-add/android-phone"
    addTracked(id)
    assertTrue(id in TrailFavorites.list(), "expected $id in list after add")
  }

  @Test
  fun `add is idempotent for the same id`() {
    val id = "0/test/favorite-dedup/ios-iphone"
    addTracked(id)
    val sizeBefore = TrailFavorites.list().size
    addTracked(id)
    assertEquals(sizeBefore, TrailFavorites.list().size, "duplicate add should not grow the list")
  }

  @Test
  fun `add returns the new list containing the added id`() {
    val id = "0/test/favorite-return/web"
    val result = addTracked(id)
    assertTrue(id in result, "add() return value should contain the new id")
  }

  @Test
  fun `add ignores a blank id`() {
    val sizeBefore = TrailFavorites.list().size
    assertEquals(sizeBefore, TrailFavorites.add("   ").size, "blank id should be a no-op")
  }

  @Test
  fun `remove eliminates a previously added id`() {
    val id = "0/test/favorite-remove/android-phone"
    addTracked(id)
    TrailFavorites.remove(id)
    addedIds.remove(id)
    assertFalse(id in TrailFavorites.list(), "id should not appear in list after remove")
  }

  @Test
  fun `remove is a no-op for an id that was never added`() {
    val sizeBefore = TrailFavorites.list().size
    TrailFavorites.remove("0/never/added-xyz/web")
    assertEquals(sizeBefore, TrailFavorites.list().size)
  }

  @Test
  fun `add then remove leaves the list unchanged`() {
    val sizeBefore = stateBefore.size
    val id = "0/test/favorite-roundtrip/ios-iphone"
    addTracked(id)
    TrailFavorites.remove(id)
    addedIds.remove(id)
    assertEquals(sizeBefore, TrailFavorites.list().size, "list size should be restored after add+remove")
  }
}
