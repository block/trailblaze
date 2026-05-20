package xyz.block.trailblaze.cli.propose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform

/**
 * Unit tests for the deterministic v1 synthesizer. Confirms:
 * 1. Resource-id-driven synthesis takes the top 2 IDs that actually match the example.
 * 2. Falls back to key_texts when no resource IDs match.
 * 3. Skips clusters whose example screen has no matching candidate.
 * 4. The `auto-` prefix and slug generation are stable across runs.
 * 5. Proposal key is deterministic across runs from the same cluster.
 *
 * The CLI-level gates (self-match, sibling-overlap, cross-waypoint bleed) are exercised
 * via `WaypointSiblingCollisionGuard` (already tested in #3099) plus the integration
 * smoke in a follow-up — this file pins the pure-synthesis core.
 */
class WaypointProposerTest {

  @Test
  fun `synthesize picks top two matching resource ids when available`() {
    val cluster = WaypointProposer.ClusterFingerprint(
      count = 12,
      keyTexts = listOf("Total", "Subtotal", "Checkout"),
      exampleLog = "example/_AgentDriverLog.json",
      exampleSession = "session-42",
      exampleResourceIds = listOf(
        "com.example:id/checkout_button",
        "com.example:id/total_amount",
        "com.example:id/subtotal_amount",
        "com.example:id/checkout_button", // duplicates allowed in fingerprint
      ),
    )
    val screen = screenWithAndroidNodes(
      resourceIds = listOf("com.example:id/checkout_button", "com.example:id/total_amount"),
      texts = emptyList(),
    )

    val result = WaypointProposer.synthesize(cluster, screen, targetId = "myapp")
    val ok = assertIsOk(result)

    assertEquals(2, ok.definition.required.size, "expected top two matching resource IDs as required")
    assertEquals("myapp/auto-checkout-subtotal-total", ok.definition.id, "id slug is sorted-keytexts joined")
    assertTrue(ok.definition.id.contains("/auto-"), "machine proposals carry the auto- prefix")
    assertTrue(ok.definition.forbidden.isEmpty(), "v1 emits no forbidden entries")
  }

  @Test
  fun `synthesize falls back to key_texts when no resource ids match`() {
    val cluster = WaypointProposer.ClusterFingerprint(
      count = 8,
      keyTexts = listOf("Welcome", "Continue"),
      exampleLog = "example/_AgentDriverLog.json",
      exampleSession = "session-7",
      // Resource IDs in the fingerprint don't exist on the example screen — fallback kicks in.
      exampleResourceIds = listOf("com.example:id/gone-id-1", "com.example:id/gone-id-2"),
    )
    val screen = screenWithAndroidNodes(
      resourceIds = emptyList(),
      texts = listOf("Welcome", "Continue"),
    )

    val result = WaypointProposer.synthesize(cluster, screen, targetId = "myapp")
    val ok = assertIsOk(result)

    assertEquals(2, ok.definition.required.size, "fallback should take both key_texts that match")
    val descriptions = ok.definition.required.mapNotNull { it.description }
    assertTrue(descriptions.all { it.startsWith("auto: text") }, "fallback uses text selectors: $descriptions")
  }

  @Test
  fun `synthesize skips when nothing matches the example screen`() {
    val cluster = WaypointProposer.ClusterFingerprint(
      count = 4,
      keyTexts = listOf("Nope"),
      exampleLog = "example/_AgentDriverLog.json",
      exampleSession = "session-9",
      exampleResourceIds = listOf("com.example:id/gone"),
    )
    val screen = screenWithAndroidNodes(
      resourceIds = listOf("com.example:id/something-else"),
      texts = listOf("Unrelated"),
    )

    val result = WaypointProposer.synthesize(cluster, screen, targetId = "myapp")
    assertTrue(result is WaypointProposer.Synthesis.Skipped, "expected skip; got $result")
  }

  @Test
  fun `synthesize is deterministic across runs from the same cluster`() {
    val cluster = WaypointProposer.ClusterFingerprint(
      count = 5,
      keyTexts = listOf("Checkout", "Total"),
      exampleLog = "example/_AgentDriverLog.json",
      exampleSession = "session-1",
      exampleResourceIds = listOf("com.example:id/checkout"),
    )
    val screen = screenWithAndroidNodes(
      resourceIds = listOf("com.example:id/checkout"),
      texts = emptyList(),
    )

    val a = assertIsOk(WaypointProposer.synthesize(cluster, screen, "myapp"))
    val b = assertIsOk(WaypointProposer.synthesize(cluster, screen, "myapp"))
    assertEquals(a.definition.id, b.definition.id, "id stable")
    assertEquals(a.proposalKey, b.proposalKey, "proposalKey stable")
    assertEquals(
      a.definition.required.size,
      b.definition.required.size,
      "required count stable",
    )
  }

  @Test
  fun `proposal key sorts key_texts so order in the JSONL does not change dedupe`() {
    val a = WaypointProposer.proposalKey(
      WaypointProposer.ClusterFingerprint(
        count = 1, keyTexts = listOf("Checkout", "Total", "Subtotal"),
        exampleLog = "x", exampleSession = "x", exampleResourceIds = emptyList(),
      ),
    )
    val b = WaypointProposer.proposalKey(
      WaypointProposer.ClusterFingerprint(
        count = 1, keyTexts = listOf("Total", "Subtotal", "Checkout"),
        exampleLog = "x", exampleSession = "x", exampleResourceIds = emptyList(),
      ),
    )
    assertEquals(a, b, "proposalKey must be invariant under key_texts permutation")
    assertEquals("new|Checkout,Subtotal,Total", a)
  }

  @Test
  fun `parseCluster reads the aggregate JSONL shape`() {
    val line = """{"count":12,"key_texts":["Total","Subtotal","Checkout"],""" +
      """"example_log":"some/path/_AgentDriverLog.json","example_session":"sess",""" +
      """"example_resource_ids":["id1","id2"]}"""
    val c = WaypointProposer.parseCluster(line)
    assertEquals(12, c.count)
    assertEquals(listOf("Total", "Subtotal", "Checkout"), c.keyTexts)
    assertEquals("some/path/_AgentDriverLog.json", c.exampleLog)
    assertEquals(listOf("id1", "id2"), c.exampleResourceIds)
  }

  @Test
  fun `generateId appends disambiguator when slug would be truncated`() {
    // Long key_texts → raw slug > 40 chars → needsSuffix branch fires → disambiguator
    // appended. Pins the round-1 collision fix: two clusters whose long key_texts
    // truncate to the same 40-char prefix get distinct ids via the disambiguator.
    val longTexts = listOf("an-unusually-long-cluster-key-text-that-runs-past-the-cap")
    val id = WaypointProposer.generateId("myapp", longTexts, disambiguator = "abcd")
    assertTrue(id.endsWith("-abcd"), "truncated slug must carry the disambiguator; got $id")
    assertTrue(id.startsWith("myapp/auto-"), "auto- prefix still present; got $id")
  }

  @Test
  fun `generateId appends disambiguator when keyTexts is empty (falls back to untitled)`() {
    val id = WaypointProposer.generateId("myapp", emptyList(), disambiguator = "abcd")
    assertEquals("myapp/auto-untitled-abcd", id)
  }

  @Test
  fun `clusterDisambiguator is stable across calls on the same cluster`() {
    val cluster = WaypointProposer.ClusterFingerprint(
      count = 1,
      keyTexts = listOf("Home"),
      exampleLog = "x",
      exampleSession = "x",
      exampleResourceIds = listOf("com.example:id/a", "com.example:id/b"),
    )
    assertEquals(
      WaypointProposer.clusterDisambiguator(cluster),
      WaypointProposer.clusterDisambiguator(cluster),
      "same cluster → same disambiguator (load-bearing for cross-week id stability)",
    )
  }

  @Test
  fun `synthesize dedupes exampleResourceIds before take(2)`() {
    // Aggregator's top_resource_ids[:5] can repeat ids when one selector is dominant in
    // the cluster. Without `.distinct()` the synthesizer would burn both required slots
    // on the same selector and produce a redundant `required` entry.
    val cluster = WaypointProposer.ClusterFingerprint(
      count = 4,
      keyTexts = listOf("Home", "Total"),
      exampleLog = "x",
      exampleSession = "x",
      exampleResourceIds = listOf(
        "com.example:id/x",
        "com.example:id/x", // duplicate
        "com.example:id/y",
        "com.example:id/x", // duplicate again
      ),
    )
    val screen = screenWithAndroidNodes(
      resourceIds = listOf("com.example:id/x", "com.example:id/y"),
      texts = emptyList(),
    )
    val ok = assertIsOk(WaypointProposer.synthesize(cluster, screen, "myapp"))
    assertEquals(2, ok.definition.required.size)
    val descriptions = ok.definition.required.mapNotNull { it.description }.toSet()
    assertEquals(
      setOf("auto: resource id com.example:id/x", "auto: resource id com.example:id/y"),
      descriptions,
      "duplicate exampleResourceIds must be deduped so both required slots cover distinct selectors",
    )
  }

  @Test
  fun `generateId truncates aggressively and produces only safe slug characters`() {
    val longTexts = listOf(
      "A very long key text with lots of words and special-characters!!! and emojis here too",
      "Another!! key:: text",
    )
    val id = WaypointProposer.generateId(targetId = "myapp", keyTexts = longTexts)
    assertTrue(id.startsWith("myapp/auto-"), "id has target prefix and auto- marker")
    val slug = id.removePrefix("myapp/auto-")
    assertTrue(slug.length <= 40, "slug ≤ 40 chars: got ${slug.length} (`$slug`)")
    assertTrue(slug.matches(Regex("[a-z0-9-]+")), "slug only safe chars: `$slug`")
  }

  // ---------------- fixtures ----------------

  private fun assertIsOk(s: WaypointProposer.Synthesis): WaypointProposer.Synthesis.Ok {
    assertTrue(s is WaypointProposer.Synthesis.Ok, "expected Ok; got $s")
    val ok = s as WaypointProposer.Synthesis.Ok
    assertNotNull(ok.definition)
    return ok
  }

  /**
   * Builds an Android-shape ScreenState with the named resource IDs and texts as
   * direct children of the root. Sufficient for the resolver's flat walk; the
   * matcher uses [DriverNodeDetail.AndroidAccessibility] for both.
   */
  private fun screenWithAndroidNodes(
    resourceIds: List<String>,
    texts: List<String>,
  ): ScreenState {
    val children = mutableListOf<TrailblazeNode>()
    resourceIds.forEachIndexed { i, rid ->
      children += TrailblazeNode(
        nodeId = (1000 + i).toLong(),
        driverDetail = DriverNodeDetail.AndroidAccessibility(resourceId = rid),
      )
    }
    texts.forEachIndexed { i, txt ->
      children += TrailblazeNode(
        nodeId = (2000 + i).toLong(),
        driverDetail = DriverNodeDetail.AndroidAccessibility(text = txt),
      )
    }
    val root = TrailblazeNode(
      nodeId = 1,
      children = children,
      driverDetail = DriverNodeDetail.AndroidAccessibility(),
    )
    return object : ScreenState {
      override val screenshotBytes: ByteArray? = null
      override val annotatedScreenshotBytes: ByteArray? = null
      override val deviceWidth: Int = 1080
      override val deviceHeight: Int = 1920
      override val viewHierarchy: ViewHierarchyTreeNode = ViewHierarchyTreeNode()
      override val trailblazeNodeTree: TrailblazeNode = root
      override val trailblazeDevicePlatform: TrailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID
      override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()
      override val pageContextSummary: String? = null
    }
  }
}
