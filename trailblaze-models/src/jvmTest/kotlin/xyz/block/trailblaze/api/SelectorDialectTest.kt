package xyz.block.trailblaze.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType

/**
 * [SelectorDialect] is the join between a selector slot, a captured tree's shape, and the driver
 * that produces it. Everything that used to be a hand-maintained table keyed off one of those three
 * now reads this enum, so the contracts under test are: every node-detail variant names a dialect,
 * every driver's dialect round-trips, and "is this selector written in dialect X" sees leaves
 * anywhere in a selector tree, not just at its root.
 */
class SelectorDialectTest {

  private fun node(detail: DriverNodeDetail) = TrailblazeNode(driverDetail = detail)

  @Test
  fun `of names a dialect for every node detail variant`() {
    // Exhaustive `when` with no else: a new DriverNodeDetail variant fails to compile here until
    // it is given a dialect, which is the point — an unnamed variant would silently fall out of
    // every dialect-keyed table in the codebase.
    val expected: Map<DriverNodeDetail, SelectorDialect> = listOf<DriverNodeDetail>(
      DriverNodeDetail.AndroidAccessibility(),
      DriverNodeDetail.AndroidView(),
      DriverNodeDetail.AndroidMaestro(),
      DriverNodeDetail.IosMaestro(),
      DriverNodeDetail.IosAxe(),
      DriverNodeDetail.Web(),
      DriverNodeDetail.Compose(),
    ).associateWith { detail ->
      when (detail) {
        is DriverNodeDetail.AndroidAccessibility -> SelectorDialect.ANDROID_ACCESSIBILITY
        is DriverNodeDetail.AndroidView -> SelectorDialect.ANDROID_VIEW
        is DriverNodeDetail.AndroidMaestro -> SelectorDialect.ANDROID_MAESTRO
        is DriverNodeDetail.IosMaestro -> SelectorDialect.IOS_MAESTRO
        is DriverNodeDetail.IosAxe -> SelectorDialect.IOS_AXE
        is DriverNodeDetail.Web -> SelectorDialect.WEB
        is DriverNodeDetail.Compose -> SelectorDialect.COMPOSE
      }
    }
    // Every dialect is covered by the sample set, so this also proves no dialect is unreachable
    // from a real capture.
    assertEquals(SelectorDialect.entries.toSet(), expected.values.toSet())
    expected.forEach { (detail, dialect) -> assertEquals(dialect, SelectorDialect.of(detail)) }
  }

  @Test
  fun `ofTree reads the dialect off the root, not off a descendant`() {
    assertEquals(
      SelectorDialect.ANDROID_MAESTRO,
      SelectorDialect.ofTree(node(DriverNodeDetail.AndroidMaestro(text = "Orders"))),
    )
    // The root is the whole tree's answer. A capture never mixes dialects, so a reader that
    // sampled a descendant instead would look correct on every real tree and be wrong about what
    // it is actually promising — the pair inference for a whole session hangs off this one read.
    val mixed = node(DriverNodeDetail.IosAxe(label = "Search")).copy(
      children = listOf(node(DriverNodeDetail.IosMaestro(hintText = "Search"))),
    )
    assertEquals(SelectorDialect.IOS_AXE, SelectorDialect.ofTree(mixed))
  }

  @Test
  fun `hasLeaf finds the dialect leaf through nested combinators`() {
    val axeLeaf = TrailblazeNodeSelector(iosAxe = DriverNodeMatch.IosAxe(labelRegex = "Search"))
    // The recorded shape that matters: the leaf is buried under combinators, and a root-only check
    // would report the selector as having no dialect at all.
    val nested = TrailblazeNodeSelector(
      childOf = TrailblazeNodeSelector(containsChild = TrailblazeNodeSelector(below = axeLeaf)),
    )
    assertTrue(SelectorDialect.IOS_AXE.hasLeaf(nested))
    assertFalse(SelectorDialect.IOS_MAESTRO.hasLeaf(nested))

    val inDescendants = TrailblazeNodeSelector(containsDescendants = listOf(axeLeaf))
    assertTrue(SelectorDialect.IOS_AXE.hasLeaf(inDescendants))

    // Every combinator slot is walked, not just the two above.
    listOf<(TrailblazeNodeSelector) -> TrailblazeNodeSelector>(
      { TrailblazeNodeSelector(below = it) },
      { TrailblazeNodeSelector(above = it) },
      { TrailblazeNodeSelector(leftOf = it) },
      { TrailblazeNodeSelector(rightOf = it) },
      { TrailblazeNodeSelector(childOf = it) },
      { TrailblazeNodeSelector(containsChild = it) },
      { TrailblazeNodeSelector(containsDescendants = listOf(it)) },
    ).forEach { wrap ->
      assertTrue(SelectorDialect.IOS_AXE.hasLeaf(wrap(axeLeaf)), "combinator did not recurse")
    }

    assertFalse(SelectorDialect.IOS_AXE.hasLeaf(TrailblazeNodeSelector(index = 3)))
  }

  @Test
  fun `leafOf returns this dialect's leaf and nothing else`() {
    val selector = TrailblazeNodeSelector(
      androidAccessibility = DriverNodeMatch.AndroidAccessibility(textRegex = "Done"),
    )
    assertEquals(selector.androidAccessibility, SelectorDialect.ANDROID_ACCESSIBILITY.leafOf(selector))
    assertNull(SelectorDialect.ANDROID_MAESTRO.leafOf(selector))
    // leafOf is root-only on purpose: it answers "what does this selector match on", which a
    // nested anchor does not.
    assertNull(
      SelectorDialect.ANDROID_ACCESSIBILITY.leafOf(TrailblazeNodeSelector(containsChild = selector)),
    )
  }

  @Test
  fun `forDriver inverts nativeDriver and covers every driver`() {
    SelectorDialect.entries.forEach { dialect ->
      val driver = dialect.nativeDriver ?: return@forEach
      assertEquals(dialect, SelectorDialect.forDriver(driver), "round trip for $dialect")
      assertEquals(dialect.platform, driver.platform, "platform agreement for $dialect")
    }
    // Both web drivers produce the same dialect; only one can be the named producer.
    assertEquals(SelectorDialect.WEB, SelectorDialect.forDriver(TrailblazeDriverType.PLAYWRIGHT_ELECTRON))
    // Cloud runners hand back a vendor-shaped tree, so they name no dialect.
    assertNull(SelectorDialect.forDriver(TrailblazeDriverType.REVYL_ANDROID))
    assertNull(SelectorDialect.forDriver(TrailblazeDriverType.REVYL_IOS))
  }

  @Test
  fun `yaml keys are unique and round-trip`() {
    assertEquals(SelectorDialect.entries.size, SelectorDialect.entries.map { it.yamlKey }.toSet().size)
    SelectorDialect.entries.forEach { assertEquals(it, SelectorDialect.fromYamlKey(it.yamlKey)) }
    assertNull(SelectorDialect.fromYamlKey("androidUiAutomator"))
  }

  @Test
  fun `only the Maestro-derived dialects resolve through the Maestro pipeline`() {
    assertEquals(
      setOf(SelectorDialect.ANDROID_MAESTRO, SelectorDialect.IOS_MAESTRO),
      SelectorDialect.entries.filter { it.resolvesViaMaestroPipeline }.toSet(),
    )
  }

  @Test
  fun `each platform's dialects agree with the platform enum`() {
    assertEquals(
      setOf(
        SelectorDialect.ANDROID_ACCESSIBILITY,
        SelectorDialect.ANDROID_VIEW,
        SelectorDialect.ANDROID_MAESTRO,
      ),
      SelectorDialect.entries.filter { it.platform == TrailblazeDevicePlatform.ANDROID }.toSet(),
    )
    assertEquals(
      setOf(SelectorDialect.IOS_MAESTRO, SelectorDialect.IOS_AXE),
      SelectorDialect.entries.filter { it.platform == TrailblazeDevicePlatform.IOS }.toSet(),
    )
  }
}
