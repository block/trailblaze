package xyz.block.trailblaze.mcp.newtools

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.mcp.TrailblazeMcpMode
import xyz.block.trailblaze.mcp.TrailblazeMcpSessionContext
import xyz.block.trailblaze.mcp.models.McpSessionId
import xyz.block.trailblaze.recordings.TrailRecordings
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.toolcalls.SessionDeviceBindings
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.SwitchDeviceTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.TapOnPointTrailblazeTool
import xyz.block.trailblaze.toolcalls.toLogPayload
import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.yaml.unified.UnifiedTrail
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Behavioral contract for `session save` on an interactive multi-device session — the roster a
 * `session start --bind` / `device(action=BIND)` builds must come out of the save as a REPLAYABLE
 * multi-device trail:
 *
 *  - `config.devices:` declares one configuration, one member per bound name, in bind order, with
 *    each member's classifier derived from its probe (platform name when identity-only).
 *  - Every recorded leg is keyed by the configuration name (never the launch device's classifier),
 *    with `switchDevice` handovers preserved in recorded order.
 *  - The name defaults to the bound names joined with `-`; the SAVE action's `configuration`
 *    argument overrides it.
 *  - A session that bound a TRAIL-DECLARED configuration never gets a synthesized cast (declared
 *    wins), and a session with no roster at all saves exactly as before.
 */
class SessionToolSetRosterSaveTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private val sessionId = SessionId("2026_08_27_10_00_00_roster_abc123")

  private val sellerId = TrailblazeDeviceId(
    instanceId = "emulator-5554",
    trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
  )
  private val buyerId = TrailblazeDeviceId(
    instanceId = "emulator-5556",
    trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
  )

  /** The start device's probe — its classifiers must become the member's `classifier:`. */
  private val sellerInfo = TrailblazeDeviceInfo(
    trailblazeDeviceId = sellerId,
    trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
    widthPixels = 1080,
    heightPixels = 2400,
    classifiers = listOf(
      TrailblazeDeviceClassifier("android"),
      TrailblazeDeviceClassifier("phone"),
    ),
  )

  /**
   * seller probed, buyer identity-only (`trailblazeDeviceInfo = null`) — the honest state an
   * interactive BIND leaves the roster in, so classifier synthesis must degrade to platform.
   */
  private fun sellerBuyerRoster(): Map<String, SessionDeviceBindings.BoundDevice> = linkedMapOf(
    "seller" to SessionDeviceBindings.BoundDevice(
      trailblazeDeviceId = sellerId,
      trailblazeDeviceInfo = sellerInfo,
      description = "Seller register",
      targetId = null,
    ),
    "buyer" to SessionDeviceBindings.BoundDevice(
      trailblazeDeviceId = buyerId,
      trailblazeDeviceInfo = null,
      description = null,
      targetId = null,
    ),
  )

  // ── log seeding ─────────────────────────────────────────────────────────────

  private var tick = 0
  private val base: Instant = Clock.System.now()
  private fun nextTimestamp(): Instant = base.plus((tick++).milliseconds)

  private fun seedSessionStarted(
    logsRepo: LogsRepo,
    selectedDeviceConfiguration: String? = null,
    session: SessionId = sessionId,
  ) {
    logsRepo.saveLogToDisk(
      TrailblazeLog.TrailblazeSessionStatusChangeLog(
        sessionStatus = SessionStatus.Started(
          trailConfig = null,
          trailFilePath = null,
          hasRecordedSteps = false,
          testMethodName = "roster session",
          testClassName = "MCP",
          trailblazeDeviceInfo = sellerInfo,
          trailblazeDeviceId = sellerId,
          rawYaml = null,
          selectedDeviceConfiguration = selectedDeviceConfiguration,
        ),
        session = session,
        timestamp = nextTimestamp(),
      ),
    )
  }

  /** One objective window: ObjectiveStart → the given recordable tools in order → Complete. */
  private fun seedObjective(
    logsRepo: LogsRepo,
    prompt: String,
    tools: List<Pair<String, TrailblazeTool>>,
    session: SessionId = sessionId,
  ) {
    val step = DirectionStep(step = prompt)
    logsRepo.saveLogToDisk(
      TrailblazeLog.ObjectiveStartLog(
        promptStep = step,
        session = session,
        timestamp = nextTimestamp(),
      ),
    )
    tools.forEach { (toolName, tool) ->
      logsRepo.saveLogToDisk(
        TrailblazeLog.TrailblazeToolLog(
          trailblazeTool = tool.toLogPayload(),
          toolName = toolName,
          successful = true,
          traceId = null,
          durationMs = 1L,
          session = session,
          timestamp = nextTimestamp(),
          isRecordable = true,
        ),
      )
    }
    logsRepo.saveLogToDisk(
      TrailblazeLog.ObjectiveCompleteLog(
        promptStep = step,
        objectiveResult = xyz.block.trailblaze.agent.model.AgentTaskStatus.Success.ObjectiveComplete(
          llmExplanation = "done",
          statusData = xyz.block.trailblaze.agent.model.AgentTaskStatusData(
            taskId = xyz.block.trailblaze.logs.model.TaskId.generate(),
            prompt = step.prompt,
            callCount = 1,
            taskStartTime = nextTimestamp(),
            totalDurationMs = 50,
          ),
        ),
        session = session,
        timestamp = nextTimestamp(),
      ),
    )
  }

  /** Seller leg (tap) then a handover leg (switchDevice → tap on the buyer). */
  private fun seedTwoLegObjectives(logsRepo: LogsRepo) {
    seedObjective(
      logsRepo,
      prompt = "Ring up the sale",
      tools = listOf("tapOnPoint" to TapOnPointTrailblazeTool(x = 10, y = 20)),
    )
    seedObjective(
      logsRepo,
      prompt = "Approve on the buyer display",
      tools = listOf(
        "switchDevice" to SwitchDeviceTrailblazeTool(name = "buyer"),
        "tapOnPoint" to TapOnPointTrailblazeTool(x = 30, y = 40),
      ),
    )
  }

  // ── harness ────────────────────────────────────────────────────────────────

  private class Harness(
    val toolSet: SessionToolSet,
    val trailsDir: File,
    val logsRepo: LogsRepo,
  )

  private fun harness(
    roster: Map<String, SessionDeviceBindings.BoundDevice>?,
    withLogsRepo: Boolean = true,
  ): Harness {
    val logsDir = tempFolder.newFolder("logs-${tick}")
    val trailsDir = tempFolder.newFolder("trails-${tick}")
    val logsRepo = LogsRepo(logsDir = logsDir, watchFileSystem = false)
    val sessionContext = TrailblazeMcpSessionContext(
      mcpServerSession = null,
      mcpSessionId = McpSessionId("roster-save-test"),
      mode = TrailblazeMcpMode.MCP_CLIENT_AS_AGENT,
    )
    // Bind through the production API — the same path device(action=BIND) takes.
    roster?.forEach { (name, device) -> sessionContext.bindNamedDevice(name, device) }
    val toolSet = SessionToolSet(
      sessionContext = sessionContext,
      mcpBridge = SessionTestBridge(activeSessionId = sessionId),
      logsRepo = if (withLogsRepo) logsRepo else null,
      sessionIdProvider = { sessionId },
      trailsDirectory = trailsDir.absolutePath,
    )
    return Harness(toolSet, trailsDir, logsRepo)
  }

  private suspend fun save(
    harness: Harness,
    title: String,
    configuration: String? = null,
    id: String? = null,
  ): JsonObject {
    val result = harness.toolSet.session(
      action = SessionToolSet.SessionAction.SAVE,
      title = title,
      id = id,
      configuration = configuration,
    )
    return Json.parseToJsonElement(result).jsonObject
  }

  private fun savedTrail(json: JsonObject): UnifiedTrail {
    val errMsg = json["error"]?.jsonPrimitive?.content
    assertNull(errMsg, "save should succeed, got error: $errMsg")
    val file = json["file"]?.jsonPrimitive?.content
    assertNotNull(file, "save result should include a file path")
    return createTrailblazeYaml().decodeUnifiedTrail(File(file).readText())
  }

  // ── roster synthesis ───────────────────────────────────────────────────────

  @Test
  fun `roster save declares the cast in bind order with probe-derived classifiers`() = runTest {
    val h = harness(roster = sellerBuyerRoster())
    seedSessionStarted(h.logsRepo)
    seedTwoLegObjectives(h.logsRepo)

    val saved = savedTrail(save(h, title = "flow"))

    assertEquals(
      setOf("seller-buyer"),
      saved.config.devices?.keys,
      "the cast is the ONLY devices entry — no stray launch-device pin beside it",
    )
    val cast = saved.config.devices?.get("seller-buyer")
    assertNotNull(cast, "the roster must be declared as `config.devices.seller-buyer`")
    assertTrue(cast.isConfiguration, "the synthesized entry must be a configuration (inner devices map)")
    assertEquals(
      listOf("seller", "buyer"),
      cast.devices?.keys?.toList(),
      "members must keep bind order — the first name is the device replays start on",
    )
    assertEquals(
      "android-phone",
      cast.devices?.get("seller")?.classifier,
      "a probed member's classifier comes from its probe's classifier chain",
    )
    assertEquals(
      "Seller register",
      cast.devices?.get("seller")?.description,
      "the bind's role description must carry into the cast",
    )
    assertEquals(
      "android",
      cast.devices?.get("buyer")?.classifier,
      "an identity-only bind (no probe) degrades to the platform name, never blank",
    )
  }

  @Test
  fun `roster save keys every leg by the configuration name with handovers in recorded order`() = runTest {
    val h = harness(roster = sellerBuyerRoster())
    seedSessionStarted(h.logsRepo)
    seedTwoLegObjectives(h.logsRepo)

    val saved = savedTrail(save(h, title = "flow"))

    assertEquals(2, saved.trail.size, "both objectives must save as steps")
    saved.trail.forEachIndexed { idx, step ->
      assertEquals(
        setOf("seller-buyer"),
        step.recordings.keys,
        "step ${idx + 1} must be keyed by the configuration name, not a device classifier",
      )
    }
    assertEquals(
      listOf("switchDevice", "tapOnPoint"),
      saved.trail[1].recordings["seller-buyer"]?.map { it.name },
      "the handover leg must start with its switchDevice, in recorded order",
    )
  }

  @Test
  fun `configuration argument overrides the default roster-name slug`() = runTest {
    val h = harness(roster = sellerBuyerRoster())
    seedSessionStarted(h.logsRepo)
    seedTwoLegObjectives(h.logsRepo)

    val saved = savedTrail(save(h, title = "flow", configuration = "pos-pair"))

    assertEquals(
      setOf("pos-pair"),
      saved.config.devices?.keys,
      "an explicit configuration name replaces the seller-buyer slug",
    )
    assertTrue(
      saved.trail.all { it.recordings.keys == setOf("pos-pair") },
      "legs must follow the chosen name",
    )
  }

  // ── no roster: unchanged behavior ──────────────────────────────────────────

  @Test
  fun `save without a roster stays a single-device platform-keyed trail`() = runTest {
    val h = harness(roster = null)
    seedSessionStarted(h.logsRepo)
    seedObjective(
      h.logsRepo,
      prompt = "Ring up the sale",
      tools = listOf("tapOnPoint" to TapOnPointTrailblazeTool(x = 10, y = 20)),
    )

    val json = save(h, title = "flow")
    val saved = savedTrail(json)

    // Today's single-device save declares only the device's own driver-pin entry (a plain
    // `config.devices.android:` — NOT a configuration). The roster feature must not add anything.
    assertEquals(
      setOf("android"),
      saved.config.devices?.keys,
      "a roster-less save keeps only the single-device driver-pin entry",
    )
    assertFalse(
      saved.config.devices!!.values.any { it.isConfiguration },
      "no configuration may be synthesized for a roster-less session: ${saved.config.devices}",
    )
    assertEquals(
      setOf("android"),
      saved.trail.single().recordings.keys,
      "a roster-less save keys its recording by the device classifier, exactly as before",
    )
  }

  @Test
  fun `configuration argument without any configuration to name is refused`() = runTest {
    val h = harness(roster = null)
    seedSessionStarted(h.logsRepo)
    seedObjective(
      h.logsRepo,
      prompt = "Ring up the sale",
      tools = listOf("tapOnPoint" to TapOnPointTrailblazeTool(x = 10, y = 20)),
    )

    val json = save(h, title = "flow", configuration = "pos-pair")

    val error = json["error"]?.jsonPrimitive?.content
    assertNotNull(error, "naming a configuration on a single-device session must fail loudly")
    assertContains(error, "pos-pair")
    assertFalse(File(h.trailsDir, "flow").exists(), "nothing may be written for a refused save")
  }

  // ── trail-declared configuration wins ──────────────────────────────────────

  /** A destination trail that already declares its own cast — the authored cast is canon. */
  private fun declaredCastTrailYaml() =
    """
    config:
      id: flow
      devices:
        pos-pair:
          devices:
            seller:
              classifier: lab-a
            buyer:
              classifier: lab-b
    trail:
      - step: Ring up the sale
        recording:
          pos-pair:
            - seedTap: {}
    """.trimIndent() + "\n"

  @Test
  fun `a trail-declared configuration is never overwritten by the roster cast`() = runTest {
    val h = harness(roster = sellerBuyerRoster())
    // The session bound the trail's own configuration at start — declared wins over the roster.
    seedSessionStarted(h.logsRepo, selectedDeviceConfiguration = "pos-pair")
    seedObjective(
      h.logsRepo,
      prompt = "Ring up the sale",
      tools = listOf("tapOnPoint" to TapOnPointTrailblazeTool(x = 10, y = 20)),
    )
    val trailDir = File(h.trailsDir, "flow").apply { mkdirs() }
    File(trailDir, TrailRecordings.UNIFIED_TRAIL_FILENAME).writeText(declaredCastTrailYaml())

    val saved = savedTrail(save(h, title = "flow"))

    assertEquals(setOf("pos-pair"), saved.config.devices?.keys, "only the authored configuration may remain")
    assertEquals(
      "lab-a",
      saved.config.devices?.get("pos-pair")?.devices?.get("seller")?.classifier,
      "the authored cast members must survive untouched — no roster-derived classifiers",
    )
    assertEquals(
      setOf("pos-pair"),
      saved.trail.single().recordings.keys,
      "the leg must be keyed by the trail-declared configuration",
    )
    assertEquals(
      listOf("tapOnPoint"),
      saved.trail.single().recordings["pos-pair"]?.map { it.name },
      "the new recording must replace the seed under the declared configuration's leg",
    )
  }

  @Test
  fun `configuration argument may not rename a trail-declared configuration`() = runTest {
    val h = harness(roster = sellerBuyerRoster())
    seedSessionStarted(h.logsRepo, selectedDeviceConfiguration = "pos-pair")
    seedObjective(
      h.logsRepo,
      prompt = "Ring up the sale",
      tools = listOf("tapOnPoint" to TapOnPointTrailblazeTool(x = 10, y = 20)),
    )
    val trailDir = File(h.trailsDir, "flow").apply { mkdirs() }
    val trailFile = File(trailDir, TrailRecordings.UNIFIED_TRAIL_FILENAME)
    trailFile.writeText(declaredCastTrailYaml())
    val before = trailFile.readText()

    val json = save(h, title = "flow", configuration = "other-name")

    val error = json["error"]?.jsonPrimitive?.content
    assertNotNull(error, "renaming the bound trail-declared configuration must fail loudly")
    assertContains(error, "pos-pair")
    assertEquals(before, trailFile.readText(), "a refused save must leave the trail untouched")
  }

  @Test
  fun `restating the bound trail-declared configuration is allowed`() = runTest {
    val h = harness(roster = sellerBuyerRoster())
    seedSessionStarted(h.logsRepo, selectedDeviceConfiguration = "pos-pair")
    seedObjective(
      h.logsRepo,
      prompt = "Ring up the sale",
      tools = listOf("tapOnPoint" to TapOnPointTrailblazeTool(x = 10, y = 20)),
    )
    File(h.trailsDir, "flow").apply { mkdirs() }
      .let { File(it, TrailRecordings.UNIFIED_TRAIL_FILENAME).writeText(declaredCastTrailYaml()) }

    val saved = savedTrail(save(h, title = "flow", configuration = "pos-pair"))

    assertEquals(
      "lab-a",
      saved.config.devices?.get("pos-pair")?.devices?.get("seller")?.classifier,
      "naming the configuration the session already bound must save, not refuse",
    )
  }

  // ── an authored SINGLE-DEVICE layout is canon too ──────────────────────────

  @Test
  fun `a cast is never planted beside an authored single-device entry`() = runTest {
    val h = harness(roster = sellerBuyerRoster())
    seedSessionStarted(h.logsRepo)
    seedTwoLegObjectives(h.logsRepo)
    // A perfectly ordinary single-device trail: one driver pin, one classifier-keyed leg. Adding a
    // cast beside it would leave the file declaring exactly ONE configuration, which the run-time
    // resolver then auto-selects on every later replay — orphaning the leg that worked before.
    val trailFile = File(h.trailsDir, "flow").apply { mkdirs() }
      .let { File(it, TrailRecordings.UNIFIED_TRAIL_FILENAME) }
    trailFile.writeText(
      """
      config:
        id: flow
        devices:
          android:
            driver: ANDROID_ONDEVICE_INSTRUMENTATION
      trail:
        - step: Ring up the sale
          recording:
            android:
              - seedTap: {}
      """.trimIndent() + "\n",
    )
    val before = trailFile.readText()

    val error = save(h, title = "flow")["error"]?.jsonPrimitive?.content

    assertNotNull(error, "a roster save into an authored single-device trail must refuse")
    assertEquals(before, trailFile.readText(), "a refused save must leave the trail untouched")
  }

  @Test
  fun `a cast never replaces a same-named authored device entry`() = runTest {
    val h = harness(roster = sellerBuyerRoster())
    seedSessionStarted(h.logsRepo)
    seedTwoLegObjectives(h.logsRepo)
    // `--configuration android` collides with the authored single-device key: a plain map `+` would
    // REPLACE it, dropping its driver pin, while the merge has already stripped its legs.
    val trailFile = File(h.trailsDir, "flow").apply { mkdirs() }
      .let { File(it, TrailRecordings.UNIFIED_TRAIL_FILENAME) }
    trailFile.writeText(
      """
      config:
        id: flow
        devices:
          android:
            driver: ANDROID_ONDEVICE_INSTRUMENTATION
      trail:
        - step: Ring up the sale
          recording:
            android:
              - seedTap: {}
      """.trimIndent() + "\n",
    )
    val before = trailFile.readText()

    val error = save(h, title = "flow", configuration = "android")["error"]?.jsonPrimitive?.content

    assertNotNull(error, "a cast whose name collides with an authored device entry must refuse")
    assertEquals(before, trailFile.readText(), "the authored driver pin must survive untouched")
  }

  @Test
  fun `a cast is never planted into a trail that only declares classifier legs`() = runTest {
    val h = harness(roster = sellerBuyerRoster())
    seedSessionStarted(h.logsRepo)
    seedTwoLegObjectives(h.logsRepo)
    // No `config.devices:` at all — a recorded-only trail. Its legs still declare a single-device
    // layout, so it is just as authored as one with an explicit pin.
    val trailFile = File(h.trailsDir, "flow").apply { mkdirs() }
      .let { File(it, TrailRecordings.UNIFIED_TRAIL_FILENAME) }
    trailFile.writeText(
      """
      config:
        id: flow
      trail:
        - step: Ring up the sale
          recording:
            android:
              - seedTap: {}
      """.trimIndent() + "\n",
    )
    val before = trailFile.readText()

    val error = save(h, title = "flow")["error"]?.jsonPrimitive?.content

    assertNotNull(error, "a roster save into a legs-only single-device trail must refuse")
    assertEquals(before, trailFile.readText(), "a refused save must leave the trail untouched")
  }

  // ── name safety ────────────────────────────────────────────────────────────

  @Test
  fun `a configuration name that is not a safe YAML key is refused`() = runTest {
    val h = harness(roster = sellerBuyerRoster())
    seedSessionStarted(h.logsRepo)
    seedTwoLegObjectives(h.logsRepo)

    // The name becomes a bare YAML key twice — the `config.devices:` entry and every leg — and the
    // unified emitter writes a leg key unquoted, so punctuation emits a trail that can't be read.
    val error = save(h, title = "flow", configuration = "pos pair: main")["error"]?.jsonPrimitive?.content

    assertNotNull(error, "a name carrying YAML punctuation must be refused, not emitted")
    assertContains(error, "pos pair: main")
    assertFalse(File(h.trailsDir, "flow").exists(), "nothing may be written for a refused save")
  }

  @Test
  fun `a default slug built from an unsafe bound name is refused`() = runTest {
    // Nothing on the BIND path constrains a name to key-safe characters, so the DEFAULT slug can be
    // unsafe with no flag typed — the refusal has to key on the resolved name, not on the argument.
    val h = harness(
      roster = linkedMapOf(
        "seller#1" to SessionDeviceBindings.BoundDevice(
          trailblazeDeviceId = sellerId,
          trailblazeDeviceInfo = sellerInfo,
          description = null,
          targetId = null,
        ),
        "buyer" to SessionDeviceBindings.BoundDevice(
          trailblazeDeviceId = buyerId,
          trailblazeDeviceInfo = null,
          description = null,
          targetId = null,
        ),
      ),
    )
    seedSessionStarted(h.logsRepo)
    seedTwoLegObjectives(h.logsRepo)

    val error = save(h, title = "flow")["error"]?.jsonPrimitive?.content

    assertNotNull(error, "an unsafe DEFAULT configuration name must be refused too")
    assertContains(error, "#")
    assertFalse(File(h.trailsDir, "flow").exists(), "nothing may be written for a refused save")
  }

  // ── per-device targets ─────────────────────────────────────────────────────

  private fun rosterWithTargets(sellerTarget: String?, buyerTarget: String?) = linkedMapOf(
    "seller" to SessionDeviceBindings.BoundDevice(
      trailblazeDeviceId = sellerId,
      trailblazeDeviceInfo = sellerInfo,
      description = null,
      targetId = sellerTarget,
    ),
    "buyer" to SessionDeviceBindings.BoundDevice(
      trailblazeDeviceId = buyerId,
      trailblazeDeviceInfo = null,
      description = null,
      targetId = buyerTarget,
    ),
  )

  @Test
  fun `members that ran different targets keep them as per-device overrides`() = runTest {
    val h = harness(roster = rosterWithTargets(sellerTarget = "storefront", buyerTarget = "kitchen"))
    seedSessionStarted(h.logsRepo)
    seedTwoLegObjectives(h.logsRepo)

    val cast = savedTrail(save(h, title = "flow")).config.devices?.get("seller-buyer")

    assertEquals(
      "storefront",
      cast?.devices?.get("seller")?.target,
      "a roster whose members ran DIFFERENT apps must keep each member's target, or the replay " +
        "runs both against one app and calls it a pass",
    )
    assertEquals("kitchen", cast?.devices?.get("buyer")?.target)
  }

  @Test
  fun `members that shared one target declare no per-device override`() = runTest {
    val h = harness(roster = rosterWithTargets(sellerTarget = "storefront", buyerTarget = "storefront"))
    seedSessionStarted(h.logsRepo)
    seedTwoLegObjectives(h.logsRepo)

    val cast = savedTrail(save(h, title = "flow")).config.devices?.get("seller-buyer")

    // A bound device's target is its EFFECTIVE one — its override, else the daemon-wide target every
    // member resolved to. Writing that on each member turns a shared target into N overrides, and
    // the resolver HARD-ERRORS on an override naming a target the replaying install doesn't carry
    // (where an absent one inherits). So an agreeing roster must stay override-free.
    assertNull(cast?.devices?.get("seller")?.target)
    assertNull(cast?.devices?.get("buyer")?.target)
  }

  // ── scope of the roster ────────────────────────────────────────────────────

  @Test
  fun `a single-member roster still saves as a one-member configuration`() = runTest {
    val h = harness(
      roster = linkedMapOf(
        "seller" to SessionDeviceBindings.BoundDevice(
          trailblazeDeviceId = sellerId,
          trailblazeDeviceInfo = sellerInfo,
          description = null,
          targetId = null,
        ),
      ),
    )
    seedSessionStarted(h.logsRepo)
    seedObjective(
      h.logsRepo,
      prompt = "Ring up the sale",
      tools = listOf("tapOnPoint" to TapOnPointTrailblazeTool(x = 10, y = 20)),
    )

    val saved = savedTrail(save(h, title = "flow"))

    assertEquals(setOf("seller"), saved.config.devices?.keys, "the slug of a one-name roster is that name")
    assertEquals(
      listOf("seller"),
      saved.config.devices?.get("seller")?.devices?.keys?.toList(),
      "a one-member roster is still a configuration, not a single-device entry",
    )
  }

  @Test
  fun `saving another session by id never inherits this session's roster`() = runTest {
    val h = harness(roster = sellerBuyerRoster())
    seedSessionStarted(h.logsRepo)
    seedTwoLegObjectives(h.logsRepo)
    // A second, single-device session in the same daemon. Its save must be keyed by its own device
    // classifier — the roster describes the CURRENT session's devices, not this one's.
    val other = SessionId("2026_08_27_09_00_00_other_zzz999")
    seedSessionStarted(h.logsRepo, session = other)
    seedObjective(
      h.logsRepo,
      prompt = "Do something else",
      tools = listOf("tapOnPoint" to TapOnPointTrailblazeTool(x = 1, y = 2)),
      session = other,
    )

    val saved = savedTrail(save(h, title = "other-flow", id = other.value))

    assertEquals(
      setOf("android"),
      saved.config.devices?.keys,
      "an --id save must not borrow the live session's cast",
    )
    assertEquals(setOf("android"), saved.trail.single().recordings.keys)
  }

  // ── the in-memory fallback cannot represent a cast ─────────────────────────

  @Test
  fun `a roster save refuses the in-memory fallback rather than dropping the cast`() = runTest {
    // No logs configured, so the log-backed path (the only one that can key legs by a configuration
    // name) is unavailable. Falling through would save a cast-less single-device trail as a pass —
    // and it needs no flag to happen, because the configuration name defaults.
    val h = harness(roster = sellerBuyerRoster(), withLogsRepo = false)

    val error = save(h, title = "flow")["error"]?.jsonPrimitive?.content

    assertNotNull(error, "a roster save with no log-backed path must refuse")
    assertContains(error, "config.devices")
    assertFalse(File(h.trailsDir, "flow").exists(), "nothing may be written for a refused save")
  }

  // ── observability ──────────────────────────────────────────────────────────

  @Test
  fun `a synthesized cast is reported with its name and members`() = runTest {
    val h = harness(roster = sellerBuyerRoster())
    seedSessionStarted(h.logsRepo)
    seedTwoLegObjectives(h.logsRepo)

    val message = save(h, title = "flow")["message"]?.jsonPrimitive?.content

    // The configuration name is a defaulted, renameable value the caller never typed — omitting it
    // hides the one thing the user needs to replay or rename the trail.
    assertNotNull(message)
    assertContains(message, "seller-buyer")
    assertContains(message, "buyer")
  }
}
