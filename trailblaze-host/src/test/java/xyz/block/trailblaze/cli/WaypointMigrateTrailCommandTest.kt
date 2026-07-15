package xyz.block.trailblaze.cli

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeJson
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.toolcalls.commands.AssertNotVisibleBySelectorTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.AssertVisibleBySelectorTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.TapOnByElementSelector
import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.ToolRecording
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper
import xyz.block.trailblaze.yaml.unified.UnifiedTrail
import xyz.block.trailblaze.yaml.unified.UnifiedTrailConfig
import xyz.block.trailblaze.yaml.unified.UnifiedTrailStep

/**
 * Tests for [WaypointMigrateTrailCommand]'s helper functions — the pieces that drive the
 * deterministic Maestro→accessibility selector migration's tool-to-snapshot pairing.
 *
 * The full `call()` flow needs real session-log JSON files with both view-hierarchy trees
 * populated, which is heavy to fixture; the helpers below cover the cleverest pieces in
 * isolation:
 *
 *  - [WaypointMigrateTrailCommand.collectMaestroSelectors] — walks a parsed trail tree and
 *    pulls every `tapOnElementBySelector` / `assertVisibleBySelector` tool whose
 *    `nodeSelector` is still Maestro-shape (androidMaestro leaves), lowered to the
 *    [TrailblazeElementSelector] the Maestro matcher consumes, in YAML document order.
 *    The order it produces is the pairing key used against session logs in pass 2 —
 *    wrong order = wrong migrations.
 *  - [WaypointMigrateTrailCommand.needsMigration] — the shared gate between the collect
 *    and rewrite walks. Both passes must agree on which tools are migration targets or
 *    the pairing index drifts.
 *  - [WaypointMigrateTrailCommand.classNameFromYamlToolName] — maps a YAML tool name to the
 *    runtime class name the per-tool snapshot hook embeds in `displayName`. Hardcoded
 *    `when` table; this test locks the table in so a rename doesn't silently break pairing.
 *  - [WaypointMigrateTrailCommand.logHasBothTrees] — peek-only filter for usable logs (must
 *    have both `viewHierarchy` and `trailblazeNodeTree` keys).
 *  - [WaypointMigrateTrailCommand.readDisplayName] — reads the snapshot log's `displayName`
 *    via regex; powers the first-tier "preTool: ClassName" pairing.
 *  - [WaypointMigrateTrailCommand.listSnapshotLogs] — combined filter + sort that decides
 *    which files in a session dir are even considered.
 *
 * The full pairing logic (two-tier match + cursor) sits inside `call()` and exercises real
 * matchers against captured trees — that's an integration concern, not a unit test target.
 */
class WaypointMigrateTrailCommandTest {

  private val tempDirs = mutableListOf<File>()

  @AfterTest
  fun cleanup() {
    tempDirs.forEach { it.deleteRecursively() }
  }

  private fun maestroSelector(text: String) = TrailblazeNodeSelector(
    androidMaestro = DriverNodeMatch.AndroidMaestro(textRegex = text),
  )

  private fun accessibilitySelector(text: String) = TrailblazeNodeSelector(
    androidAccessibility = DriverNodeMatch.AndroidAccessibility(textRegex = text),
  )

  // ----- needsMigration -----------------------------------------------------------

  @Test
  fun `needsMigration is true for androidMaestro leaves and false for accessibility or null`() {
    val cmd = WaypointMigrateTrailCommand()
    assertTrue(cmd.needsMigration(maestroSelector("Foo")))
    // The common recorded shape: the maestro leaf nested under a structural combinator.
    assertTrue(
      cmd.needsMigration(TrailblazeNodeSelector(containsChild = maestroSelector("Foo"))),
    )
    assertFalse(cmd.needsMigration(null))
    assertFalse(cmd.needsMigration(accessibilitySelector("Foo")))
    assertFalse(
      cmd.needsMigration(TrailblazeNodeSelector(containsChild = accessibilitySelector("Foo"))),
    )
    // Mixed-shape selector (both leaves present anywhere in the tree) counts as already
    // migrated — rewriting it would clobber accessibility content someone hand-authored.
    assertFalse(
      cmd.needsMigration(
        TrailblazeNodeSelector(
          androidAccessibility = DriverNodeMatch.AndroidAccessibility(textRegex = "Foo"),
          containsChild = maestroSelector("Bar"),
        ),
      ),
    )
  }

  // ----- collectMaestroSelectors --------------------------------------------------

  @Test
  fun `collectMaestroSelectors emits zero entries on a trail with no selector tools`() {
    val cmd = WaypointMigrateTrailCommand()
    val items = listOf(
      TrailYamlItem.PromptsTrailItem(
        promptSteps = listOf(
          DirectionStep(
            step = "do something custom",
            recording = ToolRecording(
              tools = listOf(wrap("customTool", FakeNonSelectorTool)),
            ),
          ),
        ),
      ),
    )
    val result = cmd.collectMaestroSelectors(items)
    assertTrue(result.isEmpty(), "Expected no entries; got: $result")
  }

  @Test
  fun `collectMaestroSelectors emits tapOnElementBySelector entries in YAML order`() {
    val cmd = WaypointMigrateTrailCommand()
    val items = listOf(
      TrailYamlItem.PromptsTrailItem(
        promptSteps = listOf(
          DirectionStep(
            step = "tap foo",
            recording = ToolRecording(
              tools = listOf(
                wrap(
                  "tapOnElementBySelector",
                  TapOnByElementSelector(nodeSelector = maestroSelector("Foo")),
                ),
              ),
            ),
          ),
          DirectionStep(
            step = "tap bar",
            recording = ToolRecording(
              tools = listOf(
                wrap(
                  "tapOnElementBySelector",
                  TapOnByElementSelector(nodeSelector = maestroSelector("Bar")),
                ),
              ),
            ),
          ),
        ),
      ),
    )
    val result = cmd.collectMaestroSelectors(items)
    assertEquals(2, result.size)
    assertEquals("Foo", result[0].maestroSelector.textRegex)
    assertEquals("tapOnElementBySelector", result[0].toolName)
    assertEquals("Bar", result[1].maestroSelector.textRegex)
  }

  @Test
  fun `collectMaestroSelectors lowers nested combinators to the element selector shape`() {
    // The dominant recorded shape in the instrumentation-driver trails: the androidMaestro
    // leaf sits under `containsChild` on the wrapper selector. The lowering must preserve
    // that structure so the Maestro matcher resolves the same node the runtime would.
    val cmd = WaypointMigrateTrailCommand()
    val items = listOf(
      TrailYamlItem.PromptsTrailItem(
        promptSteps = listOf(
          DirectionStep(
            step = "tap create appointment",
            recording = ToolRecording(
              tools = listOf(
                wrap(
                  "tapOnElementBySelector",
                  TapOnByElementSelector(
                    nodeSelector = TrailblazeNodeSelector(
                      containsChild = TrailblazeNodeSelector(
                        androidMaestro = DriverNodeMatch.AndroidMaestro(
                          textRegex = "Create appointment",
                          resourceIdRegex = "some.package:id/button",
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    )
    val result = cmd.collectMaestroSelectors(items)
    assertEquals(1, result.size)
    val lowered = result[0].maestroSelector
    assertNull(lowered.textRegex)
    assertEquals("Create appointment", lowered.containsChild?.textRegex)
    assertEquals("some.package:id/button", lowered.containsChild?.idRegex)
  }

  @Test
  fun `collectMaestroSelectors mixes tapOnElementBySelector and assertVisibleBySelector in document order`() {
    val cmd = WaypointMigrateTrailCommand()
    val items = listOf(
      TrailYamlItem.PromptsTrailItem(
        promptSteps = listOf(
          DirectionStep(
            step = "tap then assert",
            recording = ToolRecording(
              tools = listOf(
                wrap(
                  "tapOnElementBySelector",
                  TapOnByElementSelector(nodeSelector = maestroSelector("T1")),
                ),
                wrap(
                  "assertVisibleBySelector",
                  AssertVisibleBySelectorTrailblazeTool(nodeSelector = maestroSelector("A1")),
                ),
                wrap(
                  "tapOnElementBySelector",
                  TapOnByElementSelector(nodeSelector = maestroSelector("T2")),
                ),
              ),
            ),
          ),
        ),
      ),
    )
    val result = cmd.collectMaestroSelectors(items)
    assertEquals(3, result.size)
    assertEquals(listOf("T1", "A1", "T2"), result.map { it.maestroSelector.textRegex })
    assertEquals(
      listOf("tapOnElementBySelector", "assertVisibleBySelector", "tapOnElementBySelector"),
      result.map { it.toolName },
    )
  }

  @Test
  fun `collectMaestroSelectors skips already-migrated and selector-less tools`() {
    // Already-migrated tools carry an androidAccessibility-shape nodeSelector; tools with
    // no nodeSelector at all have nothing to migrate. Both are no-ops for the migration —
    // confirm collect drops them so the pairing index stays in sync with what's actually
    // migrate-able.
    val cmd = WaypointMigrateTrailCommand()
    val items = listOf(
      TrailYamlItem.PromptsTrailItem(
        promptSteps = listOf(
          DirectionStep(
            step = "three taps, one already migrated, one selector-less",
            recording = ToolRecording(
              tools = listOf(
                wrap(
                  "tapOnElementBySelector",
                  TapOnByElementSelector(nodeSelector = accessibilitySelector("Done")),
                ),
                wrap(
                  "tapOnElementBySelector",
                  TapOnByElementSelector(nodeSelector = null),
                ),
                wrap(
                  "tapOnElementBySelector",
                  TapOnByElementSelector(nodeSelector = maestroSelector("Pending")),
                ),
              ),
            ),
          ),
        ),
      ),
    )
    val result = cmd.collectMaestroSelectors(items)
    assertEquals(1, result.size)
    assertEquals("Pending", result[0].maestroSelector.textRegex)
  }

  // ----- countUnmigratableNotVisible ----------------------------------------------

  @Test
  fun `countUnmigratableNotVisible counts Maestro-shape not-visible asserts and ignores migrated ones`() {
    // Not-visible asserts can't be coordinate-resolved (the element is absent from the
    // capture), so they're reported for hand-authoring rather than collected as migration
    // targets — and they must NOT perturb the collect/rewrite pairing index.
    val cmd = WaypointMigrateTrailCommand()
    val items = listOf(
      TrailYamlItem.PromptsTrailItem(
        promptSteps = listOf(
          DirectionStep(
            step = "assert gone",
            recording = ToolRecording(
              tools = listOf(
                wrap(
                  "assertNotVisibleBySelector",
                  AssertNotVisibleBySelectorTrailblazeTool(nodeSelector = maestroSelector("Gone")),
                ),
                wrap(
                  "assertNotVisibleBySelector",
                  AssertNotVisibleBySelectorTrailblazeTool(nodeSelector = accessibilitySelector("AlreadyMigrated")),
                ),
                wrap(
                  "tapOnElementBySelector",
                  TapOnByElementSelector(nodeSelector = maestroSelector("Tap")),
                ),
              ),
            ),
          ),
        ),
      ),
    )
    assertEquals(1, cmd.countUnmigratableNotVisible(items))
    // The not-visible assert is not a collect target — pairing index only sees the tap.
    assertEquals(listOf("Tap"), cmd.collectMaestroSelectors(items).map { it.maestroSelector.textRegex })
  }

  // ----- unified-format helpers -----------------------------------------------------

  @Test
  fun `collectMaestroSelectorsUnified only walks the target classifier's recordings`() {
    val cmd = WaypointMigrateTrailCommand()
    val trail = UnifiedTrail(
      config = UnifiedTrailConfig(),
      trail = listOf(
        UnifiedTrailStep(
          step = "tap foo",
          recordings = mapOf(
            "android-phone" to listOf(
              wrap("tapOnElementBySelector", TapOnByElementSelector(nodeSelector = maestroSelector("Foo"))),
            ),
            "android-tablet" to listOf(
              wrap("tapOnElementBySelector", TapOnByElementSelector(nodeSelector = maestroSelector("Bar"))),
            ),
          ),
        ),
      ),
    )
    assertEquals(listOf("Foo"), cmd.collectMaestroSelectorsUnified(trail, "android-phone").map { it.maestroSelector.textRegex })
    assertEquals(listOf("Bar"), cmd.collectMaestroSelectorsUnified(trail, "android-tablet").map { it.maestroSelector.textRegex })
    assertTrue(cmd.collectMaestroSelectorsUnified(trail, "ios-tablet").isEmpty())
  }

  @Test
  fun `countUnmigratableNotVisibleUnified scopes to the target classifier`() {
    val cmd = WaypointMigrateTrailCommand()
    val trail = UnifiedTrail(
      config = UnifiedTrailConfig(),
      trail = listOf(
        UnifiedTrailStep(
          step = "assert gone",
          recordings = mapOf(
            "android-phone" to listOf(
              wrap("assertNotVisibleBySelector", AssertNotVisibleBySelectorTrailblazeTool(nodeSelector = maestroSelector("Gone"))),
            ),
            "android-tablet" to listOf(
              wrap("assertNotVisibleBySelector", AssertNotVisibleBySelectorTrailblazeTool(nodeSelector = accessibilitySelector("AlreadyMigrated"))),
            ),
          ),
        ),
      ),
    )
    assertEquals(1, cmd.countUnmigratableNotVisibleUnified(trail, "android-phone"))
    assertEquals(0, cmd.countUnmigratableNotVisibleUnified(trail, "android-tablet"))
  }

  @Test
  fun `availableClassifiers collects keys from the trailhead and every step`() {
    val cmd = WaypointMigrateTrailCommand()
    val trail = UnifiedTrail(
      config = UnifiedTrailConfig(),
      trailhead = UnifiedTrailStep(step = "launch", recordings = mapOf("android-phone" to emptyList())),
      trail = listOf(
        UnifiedTrailStep(step = "tap foo", recordings = mapOf("android-tablet" to emptyList())),
      ),
    )
    assertEquals(setOf("android-phone", "android-tablet"), cmd.availableClassifiers(trail))
  }

  @Test
  fun `migrateUnifiedTrail rewrites only the target classifier's tools`() {
    val cmd = WaypointMigrateTrailCommand()
    val trail = UnifiedTrail(
      config = UnifiedTrailConfig(),
      trailhead = UnifiedTrailStep(
        step = "launch",
        recordings = mapOf(
          "android-phone" to listOf(
            wrap("tapOnElementBySelector", TapOnByElementSelector(nodeSelector = maestroSelector("Foo"))),
          ),
          "android-tablet" to listOf(
            wrap("tapOnElementBySelector", TapOnByElementSelector(nodeSelector = maestroSelector("Foo"))),
          ),
        ),
      ),
      trail = listOf(
        UnifiedTrailStep(
          step = "tap bar",
          recordings = mapOf(
            "android-phone" to listOf(
              wrap("tapOnElementBySelector", TapOnByElementSelector(nodeSelector = maestroSelector("Bar"))),
            ),
          ),
        ),
      ),
    )
    val migratedTrailheadSelector = accessibilitySelector("Foo")
    val migratedStepSelector = accessibilitySelector("Bar")
    val migrations = mapOf(0 to migratedTrailheadSelector, 1 to migratedStepSelector)
    val result = cmd.migrateUnifiedTrail(
      trail, "android-phone", migrations, WaypointMigrateTrailCommand.IndexedCursor(),
    )

    val phoneTrailheadTool = result.trailhead!!.recordings["android-phone"]!![0].trailblazeTool as TapOnByElementSelector
    assertEquals(migratedTrailheadSelector, phoneTrailheadTool.nodeSelector)
    val tabletTrailheadTool = result.trailhead!!.recordings["android-tablet"]!![0].trailblazeTool as TapOnByElementSelector
    assertEquals("Foo", tabletTrailheadTool.nodeSelector?.androidMaestro?.textRegex)
    val phoneStepTool = result.trail[0].recordings["android-phone"]!![0].trailblazeTool as TapOnByElementSelector
    assertEquals(migratedStepSelector, phoneStepTool.nodeSelector)
  }

  /**
   * Writes a `_TrailblazeSessionStatusChangeLog.json` fixture carrying the given classifiers —
   * the actual log type + field ([SessionStatus.Started.trailblazeDeviceInfo]) a real captured
   * session populates, encoded through the same [TrailblazeJson.defaultWithoutToolsInstance] the
   * command reads with, so the fixture stays honest about the real wire shape.
   */
  private fun writeSessionStatusLog(dir: File, fileNumber: Int, classifiers: List<String>) {
    val log: TrailblazeLog = TrailblazeLog.TrailblazeSessionStatusChangeLog(
      sessionStatus = SessionStatus.Started(
        trailConfig = null,
        trailFilePath = null,
        hasRecordedSteps = false,
        testMethodName = "someTest",
        testClassName = "SomeTestClass",
        trailblazeDeviceInfo = TrailblazeDeviceInfo(
          trailblazeDeviceId = TrailblazeDeviceId(
            instanceId = "emulator-5554",
            trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
          ),
          trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
          widthPixels = 1080,
          heightPixels = 2400,
          classifiers = classifiers.map { TrailblazeDeviceClassifier(it) },
        ),
      ),
      session = SessionId("test-session"),
      timestamp = Instant.fromEpochMilliseconds(0),
    )
    File(dir, "${fileNumber}_TrailblazeSessionStatusChangeLog.json").writeText(
      TrailblazeJson.defaultWithoutToolsInstance.encodeToString(log),
    )
  }

  @Test
  fun `inferClassifier auto-selects the single classifier the session logs agree on`() {
    val cmd = WaypointMigrateTrailCommand()
    val dir = createTempDirectory("migrate-trail-test").toFile().also { tempDirs += it }
    writeSessionStatusLog(dir, 1, listOf("android-phone"))
    assertEquals("android-phone", cmd.inferClassifier(setOf("android-phone", "android-tablet"), dir))
  }

  @Test
  fun `inferClassifier returns null when no classifier or more than one is present`() {
    val cmd = WaypointMigrateTrailCommand()
    val emptyDir = createTempDirectory("migrate-trail-test").toFile().also { tempDirs += it }
    assertNull(cmd.inferClassifier(setOf("android-phone"), emptyDir))

    val ambiguousDir = createTempDirectory("migrate-trail-test").toFile().also { tempDirs += it }
    writeSessionStatusLog(ambiguousDir, 1, listOf("android-phone"))
    writeSessionStatusLog(ambiguousDir, 2, listOf("android-tablet"))
    assertNull(cmd.inferClassifier(setOf("android-phone", "android-tablet"), ambiguousDir))
  }

  // ----- classNameFromYamlToolName ------------------------------------------------

  @Test
  fun `classNameFromYamlToolName maps known tool names to runtime class names`() {
    val cmd = WaypointMigrateTrailCommand()
    // The hook in [AndroidTrailblazeRule] / [TrailblazeHostYamlRunner] writes the runtime
    // class name into the snapshot's `displayName` as `preTool: <ClassName>`. The mapping
    // must round-trip with [TapOnByElementSelector::class.simpleName] etc. exactly,
    // otherwise first-tier pairing silently misses every snapshot.
    assertEquals(
      TapOnByElementSelector::class.simpleName,
      cmd.classNameFromYamlToolName("tapOnElementBySelector"),
    )
    assertEquals(
      AssertVisibleBySelectorTrailblazeTool::class.simpleName,
      cmd.classNameFromYamlToolName("assertVisibleBySelector"),
    )
  }

  @Test
  fun `classNameFromYamlToolName falls through to input for unknown tool names`() {
    // Defensive default — doesn't crash on a tool the migration doesn't know about.
    // Pairing won't find a matching `preTool: <unknown>` snapshot, so fallback cursor
    // takes over. Confirm the no-crash contract.
    val cmd = WaypointMigrateTrailCommand()
    assertEquals("someOtherTool", cmd.classNameFromYamlToolName("someOtherTool"))
  }

  // ----- logHasBothTrees ----------------------------------------------------------

  @Test
  fun `logHasBothTrees requires viewHierarchy + trailblazeNodeTree + driverMigrationTreeNode`() {
    val cmd = WaypointMigrateTrailCommand()
    val dir = createTempDirectory("migrate-trail-test").toFile().also { tempDirs += it }

    // The "all three" file represents a migration-mode capture — viewHierarchy is the
    // UiAutomator XML, trailblazeNodeTree is the canonical driver tree, and
    // driverMigrationTreeNode is the side-channel accessibility tree. Only this shape
    // is safe for migrate-trail to feed into TapSelectorV2.
    val all = File(dir, "all.json").apply {
      writeText(
        """{"viewHierarchy": {}, "trailblazeNodeTree": {}, "driverMigrationTreeNode": {}}""",
      )
    }
    // Pre-migration captures (or non-migration-mode runs) lack driverMigrationTreeNode.
    // They can't be safely consumed because their viewHierarchy could be the
    // accessibility-projected shape — gating prevents wrong-coordinate matches.
    val noMigTree = File(dir, "no-mig.json").apply {
      writeText("""{"viewHierarchy": {}, "trailblazeNodeTree": {}}""")
    }
    val onlyVh = File(dir, "vh-only.json").apply {
      writeText("""{"viewHierarchy": {}}""")
    }
    val onlyTnt = File(dir, "tnt-only.json").apply {
      writeText("""{"trailblazeNodeTree": {}}""")
    }
    val empty = File(dir, "empty.json").apply { writeText("{}") }

    assertTrue(cmd.logHasBothTrees(all))
    assertFalse(cmd.logHasBothTrees(noMigTree))
    assertFalse(cmd.logHasBothTrees(onlyVh))
    assertFalse(cmd.logHasBothTrees(onlyTnt))
    assertFalse(cmd.logHasBothTrees(empty))
  }

  // ----- readDisplayName ----------------------------------------------------------

  @Test
  fun `readDisplayName extracts displayName from a snapshot log`() {
    val cmd = WaypointMigrateTrailCommand()
    val dir = createTempDirectory("migrate-trail-test").toFile().also { tempDirs += it }
    val log = File(dir, "010_TrailblazeSnapshotLog.json").apply {
      writeText(
        """{"class":"...","displayName":"preTool: TapOnByElementSelector","viewHierarchy":{}}""",
      )
    }
    assertEquals("preTool: TapOnByElementSelector", cmd.readDisplayName(log))
  }

  @Test
  fun `readDisplayName returns null when displayName is absent`() {
    val cmd = WaypointMigrateTrailCommand()
    val dir = createTempDirectory("migrate-trail-test").toFile().also { tempDirs += it }
    val log = File(dir, "001_TrailblazeLlmRequestLog.json").apply {
      writeText("""{"class":"...","viewHierarchy":{}}""")
    }
    assertNull(cmd.readDisplayName(log))
  }

  // ----- readActionCoordinate -----------------------------------------------------
  //
  // On-device instrumentation captures (AgentDriverLog) have no `displayName`, so precise
  // pairing degrades to a forward-cursor fallback that can drift past a selector's screen
  // — and even on the right frame the Maestro-tree resolver can miss a concatenated summary
  // row (e.g. "-$5.00" or a payment-method label that only exists as a substring of a
  // container's text in the UiAutomator tree). The recorded `action` coordinate is the seed
  // that lets [tryResolveInLog] recover in both cases. These pin the parse contract.

  @Test
  fun `readActionCoordinate parses a TapPoint action coordinate`() {
    val cmd = WaypointMigrateTrailCommand()
    val dir = createTempDirectory("migrate-trail-test").toFile().also { tempDirs += it }
    val log = File(dir, "abc_AgentDriverLog.json").apply {
      writeText(
        """{"class":"...","action":{"class":"xyz.block.trailblaze.api.AgentDriverAction.TapPoint","x":326,"y":2277},"driverMigrationTreeNode":{}}""",
      )
    }
    assertEquals(326 to 2277, cmd.readActionCoordinate(log))
  }

  @Test
  fun `readActionCoordinate parses an AssertCondition coordinate past a brace-bearing description`() {
    val cmd = WaypointMigrateTrailCommand()
    val dir = createTempDirectory("migrate-trail-test").toFile().also { tempDirs += it }
    // The description precedes x/y AND contains `{`/`}` — a brace-bounded regex slice of the
    // action object would truncate at the stray brace and miss x/y. Structural parsing must
    // still pick x/y from inside the action object, not from an outer field.
    val log = File(dir, "def_AgentDriverLog.json").apply {
      writeText(
        """{"deviceWidth":1080,"action":{"class":"xyz.block.trailblaze.api.AgentDriverAction.AssertCondition","conditionDescription":"\"Refund {amount}\" is visible","x":540,"y":1170,"isVisible":true}}""",
      )
    }
    assertEquals(540 to 1170, cmd.readActionCoordinate(log))
  }

  @Test
  fun `readActionCoordinate returns null for a coordinate-less action`() {
    val cmd = WaypointMigrateTrailCommand()
    val dir = createTempDirectory("migrate-trail-test").toFile().also { tempDirs += it }
    // EnterText carries no x/y — the fallback must decline so the forward scan continues.
    val log = File(dir, "ghi_AgentDriverLog.json").apply {
      writeText(
        """{"action":{"class":"xyz.block.trailblaze.api.AgentDriverAction.EnterText","text":"5.00"}}""",
      )
    }
    assertNull(cmd.readActionCoordinate(log))
  }

  @Test
  fun `readActionCoordinate returns null when there is no action block`() {
    val cmd = WaypointMigrateTrailCommand()
    val dir = createTempDirectory("migrate-trail-test").toFile().also { tempDirs += it }
    val log = File(dir, "jkl_TrailblazeSnapshotLog.json").apply {
      writeText("""{"displayName":"preTool: TapOnByElementSelector","viewHierarchy":{}}""")
    }
    assertNull(cmd.readActionCoordinate(log))
  }

  @Test
  fun `readActionCoordinate returns null when the action has x but no y`() {
    val cmd = WaypointMigrateTrailCommand()
    val dir = createTempDirectory("migrate-trail-test").toFile().also { tempDirs += it }
    // A partial coordinate can't seed a hit-test — the fallback must decline, not guess.
    val log = File(dir, "mno_AgentDriverLog.json").apply {
      writeText("""{"action":{"class":"...TapPoint","x":326}}""")
    }
    assertNull(cmd.readActionCoordinate(log))
  }

  @Test
  fun `readActionCoordinate rejects a not-visible assertion coordinate`() {
    val cmd = WaypointMigrateTrailCommand()
    val dir = createTempDirectory("migrate-trail-test").toFile().also { tempDirs += it }
    // A not-visible assert records screen-center coordinates, not a real target — a later
    // selector must not bind off this log during the forward-cursor scan.
    val log = File(dir, "nv_AgentDriverLog.json").apply {
      writeText(
        """{"action":{"class":"...AssertCondition","conditionDescription":"\"Search\" is not visible","x":540,"y":1170,"isVisible":false,"succeeded":true}}""",
      )
    }
    assertNull(cmd.readActionCoordinate(log))
  }

  @Test
  fun `readActionCoordinate rejects a failed visible assertion coordinate`() {
    val cmd = WaypointMigrateTrailCommand()
    val dir = createTempDirectory("migrate-trail-test").toFile().also { tempDirs += it }
    // A failed visible assert records (0,0) — reject so the fallback never seeds a hit-test
    // at the origin.
    val log = File(dir, "fa_AgentDriverLog.json").apply {
      writeText(
        """{"action":{"class":"...AssertCondition","conditionDescription":"\"Total\" is visible","x":0,"y":0,"isVisible":true,"succeeded":false}}""",
      )
    }
    assertNull(cmd.readActionCoordinate(log))
  }

  @Test
  fun `readActionCoordinate returns null for unparseable JSON`() {
    val cmd = WaypointMigrateTrailCommand()
    val dir = createTempDirectory("migrate-trail-test").toFile().also { tempDirs += it }
    // A truncated/corrupt log must be swallowed as "no coordinate here" so the forward scan
    // continues, never abort the batch migration.
    val log = File(dir, "pqr_AgentDriverLog.json").apply {
      writeText("""{"action":{"class":"...TapPoint","x":326,""")
    }
    assertNull(cmd.readActionCoordinate(log))
  }

  // ----- listSnapshotLogs ---------------------------------------------------------

  @Test
  fun `listSnapshotLogs returns LlmRequestLog and SnapshotLog files in name-sorted order, filtered to dual-tree`() {
    val cmd = WaypointMigrateTrailCommand()
    val dir = createTempDirectory("migrate-trail-test").toFile().also { tempDirs += it }

    // `good` represents migration-mode captures — all three required keys present.
    // `noMigTree` represents non-migration captures — gated out by [logHasBothTrees].
    // `bad` is missing trailblazeNodeTree entirely (also gated out).
    val good = """{"viewHierarchy":{},"trailblazeNodeTree":{},"driverMigrationTreeNode":{}}"""
    val noMigTree = """{"viewHierarchy":{},"trailblazeNodeTree":{}}"""
    val bad = """{"viewHierarchy":{}}"""
    val unrelatedShape = good

    File(dir, "001_TrailblazeSessionStatusChangeLog.json").writeText(unrelatedShape) // wrong type
    val s1 = File(dir, "008_TrailblazeLlmRequestLog.json").apply { writeText(good) }
    val s2 = File(dir, "010_TrailblazeSnapshotLog.json").apply { writeText(good) }
    File(dir, "012_TrailblazeLlmRequestLog.json").writeText(bad) // missing trailblazeNodeTree
    File(dir, "013_TrailblazeLlmRequestLog.json").writeText(noMigTree) // pre-migration capture, gated out
    val s3 = File(dir, "015_TrailblazeSnapshotLog.json").apply { writeText(good) }

    val result = cmd.listSnapshotLogs(dir)
    // Only the two file types that match the suffix AND have all three required keys, sorted by name.
    assertEquals(listOf(s1.name, s2.name, s3.name), result.map { it.name })
  }

  // ----- helpers ------------------------------------------------------------------

  private fun wrap(name: String, tool: xyz.block.trailblaze.toolcalls.TrailblazeTool) =
    TrailblazeToolYamlWrapper(name = name, trailblazeTool = tool)

  private object FakeNonSelectorTool : xyz.block.trailblaze.toolcalls.TrailblazeTool
}
