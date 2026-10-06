package xyz.block.trailblaze.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.SelectorDialect
import xyz.block.trailblaze.api.TrailblazeNode

/**
 * [MigrationPair] is the one place that decides what a migration run is rewriting. Its whole job is
 * to refuse the pairs that can't work — a dialect onto itself, or across platforms — and to read the
 * real pair off a dual-tree capture instead of assuming one.
 */
class MigrationPairTest {

  private fun tree(detail: DriverNodeDetail) = TrailblazeNode(driverDetail = detail)

  private val iosMaestroTree = tree(DriverNodeDetail.IosMaestro(hintText = "Search"))
  private val iosAxeTree = tree(DriverNodeDetail.IosAxe(label = "Search"))
  private val androidMaestroTree = tree(DriverNodeDetail.AndroidMaestro(text = "Orders"))
  private val androidAccessibilityTree = tree(DriverNodeDetail.AndroidAccessibility(text = "Orders"))

  @Test
  fun `a pair of different dialects on one platform is valid`() {
    val pair = MigrationPair(SelectorDialect.IOS_MAESTRO, SelectorDialect.IOS_AXE)
    assertEquals(SelectorDialect.IOS_MAESTRO, pair.source)
    assertEquals(SelectorDialect.IOS_AXE, pair.target)
    assertEquals("iosMaestro → iosAxe", pair.toString())
  }

  @Test
  fun `a dialect cannot be migrated onto itself`() {
    val failure = assertFailsWith<IllegalArgumentException> {
      MigrationPair(SelectorDialect.IOS_AXE, SelectorDialect.IOS_AXE)
    }
    // The message has to name the dialect, because the caller that hits this is a batch run
    // pointed at a capture whose two trees came out the same shape.
    assertEquals(true, failure.message?.contains("iosAxe"), failure.message)
  }

  @Test
  fun `a pair cannot cross platforms`() {
    val failure = assertFailsWith<IllegalArgumentException> {
      MigrationPair(SelectorDialect.ANDROID_MAESTRO, SelectorDialect.IOS_AXE)
    }
    assertEquals(true, failure.message?.contains("androidMaestro"), failure.message)
    assertEquals(true, failure.message?.contains("iosAxe"), failure.message)
  }

  @Test
  fun `infer reads the pair off a capture's two trees`() {
    assertEquals(
      MigrationPair(SelectorDialect.IOS_MAESTRO, SelectorDialect.IOS_AXE),
      MigrationPair.infer(iosMaestroTree, iosAxeTree),
    )
    // Direction is not symmetric: the primary tree is always the dialect being migrated FROM.
    assertEquals(
      MigrationPair(SelectorDialect.IOS_AXE, SelectorDialect.IOS_MAESTRO),
      MigrationPair.infer(iosAxeTree, iosMaestroTree),
    )
    assertEquals(
      MigrationPair(SelectorDialect.ANDROID_MAESTRO, SelectorDialect.ANDROID_ACCESSIBILITY),
      MigrationPair.infer(androidMaestroTree, androidAccessibilityTree),
    )
  }

  @Test
  fun `inferOrNull declines a capture that is not a pair`() {
    assertNull(MigrationPair.inferOrNull(iosMaestroTree, null))
    assertNull(MigrationPair.inferOrNull(null, iosAxeTree))
    // Both trees the same shape: a single-tree capture logged twice, not a migration.
    assertNull(MigrationPair.inferOrNull(iosAxeTree, iosAxeTree))
    assertNull(MigrationPair.inferOrNull(androidMaestroTree, iosAxeTree))
  }

  @Test
  fun `infer names what was missing`() {
    assertEquals(
      true,
      assertFailsWith<IllegalArgumentException> {
        MigrationPair.infer(iosMaestroTree, null)
      }.message?.contains("side-channel"),
    )
    assertEquals(
      true,
      assertFailsWith<IllegalArgumentException> {
        MigrationPair.infer(null, iosAxeTree)
      }.message?.contains("primary"),
    )
  }
}
