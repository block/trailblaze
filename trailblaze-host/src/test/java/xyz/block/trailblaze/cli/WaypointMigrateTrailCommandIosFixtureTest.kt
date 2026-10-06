package xyz.block.trailblaze.cli

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.MigrationScreenState
import xyz.block.trailblaze.api.SelectorDialect
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.api.TrailblazeNodeSelectorResolver
import xyz.block.trailblaze.toolcalls.commands.AssertVisibleBySelectorTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.TapOnByElementSelector
import xyz.block.trailblaze.waypoint.SessionLogScreenState
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.yaml.unified.TrailDocument

/**
 * End-to-end migration of a real iOS capture: Apple Contacts on a simulator, recorded on the
 * `IOS_HOST` driver with the secondary-tree switch on, so every usable log carries the XCTest
 * (`iosMaestro`) tree the trail's selectors were recorded against AND the `axe describe-ui`
 * (`iosAxe`) tree the AXe driver will match against.
 *
 * This is the test that proves the command is no longer an Android tool. Nothing here names a
 * dialect to the command: the pair is read from the capture, the selectors are resolved through
 * whichever matcher that source dialect uses, and the rewritten YAML is asserted to identify the
 * same on-screen elements — the search field the tap landed on, and the "No Results" label the
 * assert checked.
 *
 * Fixtures are the session's own logs with whitespace stripped (byte-for-byte the same data), plus
 * the trail as recorded. The session-status log is deliberately NOT copied, so the classifier is
 * passed explicitly rather than inferred.
 */
class WaypointMigrateTrailCommandIosFixtureTest {

  private val tempDirs = mutableListOf<File>()

  @AfterTest
  fun cleanup() {
    tempDirs.forEach { it.deleteRecursively() }
  }

  private fun fixtureDir(): File {
    val url = requireNotNull(javaClass.classLoader.getResource("migrate-trail-fixtures/ios-contacts")) {
      "Missing fixture dir migrate-trail-fixtures/ios-contacts"
    }
    return File(url.toURI())
  }

  /** A writable copy of the fixture session + trail, since `--write` edits the trail in place. */
  private fun scratch(): Pair<File, File> {
    val dir = createTempDirectory("ios-migrate").toFile().also { tempDirs += it }
    val session = File(dir, "session").also { it.mkdirs() }
    fixtureDir().listFiles { f -> f.name.endsWith(".json") }!!.forEach { it.copyTo(File(session, it.name)) }
    val trail = File(dir, "trail.yaml")
    File(fixtureDir(), "trail.yaml").copyTo(trail)
    return trail to session
  }

  private fun migrate(trail: File, session: File, configure: WaypointMigrateTrailCommand.() -> Unit = {}): Int =
    WaypointMigrateTrailCommand().apply {
      trailFile = trail
      sessionDir = session
      classifier = "ios-iphone"
      write = true
      configure()
    }.call()

  @Test
  fun `the pair is inferred from the capture as iosMaestro to iosAxe`() {
    val screen = SessionLogScreenState.loadStep(File(fixtureDir(), "008_TrailblazeSnapshotLog.json"))
    val secondary = (screen as MigrationScreenState).driverMigrationTreeNode
    val pair = MigrationPair.infer(screen.trailblazeNodeTree, secondary)
    assertEquals(SelectorDialect.IOS_MAESTRO, pair.source)
    assertEquals(SelectorDialect.IOS_AXE, pair.target)
  }

  /** Every selector-bearing tool in the migrated trail, in document order. */
  private fun migratedSelectors(trail: File): List<Pair<String, TrailblazeNodeSelector?>> {
    val doc = createTrailblazeYaml().decodeTrailDocument(trail.readText()) as TrailDocument.Unified
    return doc.trail.trail
      .flatMap { it.recordings["ios-iphone"].orEmpty() }
      .mapNotNull { wrapper ->
        when (val tool = wrapper.trailblazeTool) {
          is TapOnByElementSelector -> wrapper.name to tool.nodeSelector
          is AssertVisibleBySelectorTrailblazeTool -> wrapper.name to tool.nodeSelector
          else -> null
        }
      }
  }

  /** The single node [selector] identifies in the capture's Axe tree, or null if it isn't unique. */
  private fun resolveInAxeTree(logName: String, selector: TrailblazeNodeSelector?): DriverNodeDetail.IosAxe? {
    val screen = SessionLogScreenState.loadStep(File(fixtureDir(), logName))
    val axeTree = (screen as MigrationScreenState).driverMigrationTreeNode!!
    val result = TrailblazeNodeSelectorResolver.resolve(axeTree, selector!!)
    return (result as? TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch)
      ?.node?.driverDetail as? DriverNodeDetail.IosAxe
  }

  @Test
  fun `migrating the Contacts search trail rewrites both selectors into iosAxe`() {
    val (trail, session) = scratch()
    val before = trail.readText()
    assertTrue("iosMaestro" in before && "iosAxe" !in before, "fixture trail should start out iosMaestro-only")

    assertEquals(TrailblazeExitCode.SUCCESS.code, migrate(trail, session))

    val after = trail.readText()
    assertTrue(
      "iosMaestro" !in after,
      "migration must replace the source dialect wholesale, not leave a mixed selector:\n$after",
    )
    val selectors = migratedSelectors(trail)
    assertEquals(listOf("tapOnElementBySelector", "assertVisibleBySelector"), selectors.map { it.first })
    selectors.forEach { (name, selector) ->
      assertTrue(
        SelectorDialect.IOS_AXE.hasLeaf(selector!!),
        "$name kept a non-iosAxe selector: $selector",
      )
    }

    // The real assertion is about the ELEMENT, not the wording: resolve each emitted selector back
    // against the same Axe tree the migration read and check it names the element the recorded
    // selector named. The recorded tap said `hintTextRegex: Search`, which on this screen is the
    // search field — NOT the magnifying-glass image sitting inside it, whose label is also
    // "Search" and which a text-only match picks instead.
    val tapped = resolveInAxeTree("008_TrailblazeSnapshotLog.json", selectors[0].second)
    assertEquals("AXTextField", tapped?.role, "migrated tap resolves to $tapped")
    assertEquals("AXSearchField", tapped?.subrole, "migrated tap resolves to $tapped")
    assertEquals("Search", tapped?.label, "migrated tap resolves to $tapped")

    val asserted = resolveInAxeTree("020_TrailblazeSnapshotLog.json", selectors[1].second)
    assertEquals("AXStaticText", asserted?.role, "migrated assert resolves to $asserted")
    assertTrue(
      asserted?.label?.startsWith("No Results for") == true,
      "migrated assert resolves to $asserted",
    )
  }

  @Test
  fun `a lower-cased Maestro anchor still lands on the element it named`() {
    // Maestro matching is case-insensitive, so `hintTextRegex: search` is a legitimate recording
    // for a field whose hint is `Search`, and it resolves on the source tree exactly as the
    // capitalised form does. The anchor search over the TARGET tree has to be just as lenient, or
    // the node scores zero and the tool is skipped for losing an intent it still carries.
    val (trail, session) = scratch()
    trail.writeText(trail.readText().replace("hintTextRegex: Search", "hintTextRegex: search"))

    assertEquals(TrailblazeExitCode.SUCCESS.code, migrate(trail, session))

    val selectors = migratedSelectors(trail)
    val tapped = resolveInAxeTree("008_TrailblazeSnapshotLog.json", selectors[0].second)
    assertEquals("AXTextField", tapped?.role, "migrated tap resolves to $tapped")
    assertEquals("AXSearchField", tapped?.subrole, "migrated tap resolves to $tapped")
  }

  @Test
  fun `a --from that disagrees with the capture fails instead of migrating`() {
    val (trail, session) = scratch()
    val before = trail.readText()
    val exit = migrate(trail, session) { fromDialectKey = "androidMaestro" }
    assertEquals(TrailblazeExitCode.MISUSE.code, exit)
    assertEquals(before, trail.readText(), "a rejected run must not touch the trail file")
  }

  @Test
  fun `a --to that agrees with the capture migrates normally`() {
    val (trail, session) = scratch()
    val exit = migrate(trail, session) {
      fromDialectKey = "iosMaestro"
      toDialectKey = "iosAxe"
    }
    assertEquals(TrailblazeExitCode.SUCCESS.code, exit)
    assertTrue("iosAxe" in trail.readText())
  }

  @Test
  fun `an unknown --to dialect is rejected`() {
    val (trail, session) = scratch()
    assertEquals(TrailblazeExitCode.MISUSE.code, migrate(trail, session) { toDialectKey = "iosAxeV2" })
  }
}
