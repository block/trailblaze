package xyz.block.trailblaze.mcp.newtools

import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ScreenshotScalingConfig
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.mcp.TrailblazeMcpBridge
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateResponse
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.toolcalls.TrailblazeTool

/**
 * MCP protocol coverage for [TrailMcpTool] LIST and RUN (by-name) actions.
 *
 * `TrailFileManager.listTrails` / `findTrailByName` route through
 * [xyz.block.trailblaze.config.project.TrailDiscovery]. Those changes are unit-tested in
 * `TrailFileManagerTest`, but the MCP tool layer wraps them in a JSON envelope that CI did
 * not exercise — this class fills that gap.
 *
 * Covered here:
 *  - `trail(action=LIST)` returns the paginated `TrailListResult` envelope with the
 *    expected shape (trails[], count, totalCount, page, hasMore, message).
 *  - `trail(action=LIST, filter=X)` scopes by path/title; an unknown filter returns
 *    the "No trails found matching 'X'" message rather than a generic empty result.
 *  - `trail(action=RUN, name=<unknown>)` surfaces the `findTrailByName`-returned-null
 *    path as "Trail '<name>' not found", proving the lookup was invoked and plumbed
 *    into the envelope correctly.
 */
class TrailMcpToolListFindTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private val json = Json { ignoreUnknownKeys = true }

  private fun seedTrails(relativePaths: Collection<String>) {
    for (path in relativePaths) {
      val file = File(tempFolder.root, path)
      file.parentFile?.mkdirs()
      // Minimal valid trail body so TrailblazeYaml.decodeTrail doesn't blow up if the
      // handler reads it. The LIST path only reads titles on the page slice, so not
      // every file is opened — but giving them valid shape keeps the test robust to
      // future changes that eagerly parse.
      file.writeText("steps: []\n")
    }
  }

  private fun tool() = TrailMcpTool(
    sessionContext = null,
    mcpBridge = NoopBridge,
    trailsDirectory = tempFolder.root.absolutePath,
  )

  @Test
  fun `LIST with no filter returns the paginated envelope with totals`() = runTest {
    seedTrails(
      listOf(
        "flows/login.trail.yaml",
        "flows/checkout/blaze.yaml",
        "catalog/overlay/blaze.yaml",
      ),
    )

    val response = tool().trail(action = TrailMcpTool.TrailAction.LIST)
    val envelope = json.parseToJsonElement(response).jsonObject

    val trails = envelope["trails"]!!.jsonArray
    assertEquals(3, trails.size, "envelope.trails size")
    assertEquals(3, envelope["count"]!!.jsonPrimitive.int)
    assertEquals(3, envelope["totalCount"]!!.jsonPrimitive.int)
    assertEquals(1, envelope["page"]!!.jsonPrimitive.int)
    assertFalse(envelope["hasMore"]!!.jsonPrimitive.boolean)

    val paths = trails.map { it.jsonObject["path"]!!.jsonPrimitive.content }.toSet()
    assertTrue("flows/login.trail.yaml" in paths, "envelope missing trail: $paths")
    assertTrue("flows/checkout/blaze.yaml" in paths, "envelope missing blaze.yaml: $paths")
    assertTrue("catalog/overlay/blaze.yaml" in paths, "envelope missing nested blaze: $paths")
  }

  @Test
  fun `LIST with a filter that matches nothing returns the per-filter empty message`() = runTest {
    seedTrails(listOf("flows/login.trail.yaml"))

    val response = tool().trail(action = TrailMcpTool.TrailAction.LIST, filter = "totally-unknown-xyz")
    val envelope = json.parseToJsonElement(response).jsonObject

    assertEquals(0, envelope["count"]!!.jsonPrimitive.int)
    assertEquals(0, envelope["totalCount"]!!.jsonPrimitive.int)
    val message = envelope["message"]!!.jsonPrimitive.content
    assertTrue(
      "No trails found matching" in message && "totally-unknown-xyz" in message,
      "expected per-filter empty message; got: $message",
    )
  }

  @Test
  fun `LIST excludes build-dir leakage through the envelope`() = runTest {
    seedTrails(
      listOf(
        "flows/real.trail.yaml",
        // Stale cached trail under an excluded dir — TrailDiscovery prunes it, so the
        // envelope should reflect that end-to-end (not just the unit-level discovery).
        "build/generated/stale.trail.yaml",
        ".gradle/cached.trail.yaml",
      ),
    )

    val response = tool().trail(action = TrailMcpTool.TrailAction.LIST)
    val envelope = json.parseToJsonElement(response).jsonObject

    assertEquals(1, envelope["totalCount"]!!.jsonPrimitive.int, "excluded dirs leaked: $response")
    val paths = envelope["trails"]!!.jsonArray
      .map { it.jsonObject["path"]!!.jsonPrimitive.content }
    assertEquals(listOf("flows/real.trail.yaml"), paths)
  }

  @Test
  fun `RUN by unknown name surfaces the findTrailByName-null error envelope`() = runTest {
    seedTrails(listOf("flows/login.trail.yaml"))

    val response = tool().trail(
      action = TrailMcpTool.TrailAction.RUN,
      name = "totally-unknown-trail-name",
    )
    val envelope = json.parseToJsonElement(response).jsonObject

    // Shape: TrailRunResult with success=false and a user-facing error that names
    // the missing trail and points at LIST — matching TrailMcpTool.handleRun's
    // `findTrailByName == null` branch.
    assertFalse(envelope["success"]!!.jsonPrimitive.boolean)
    val error = envelope["error"]!!.jsonPrimitive.content
    assertTrue(
      "totally-unknown-trail-name" in error && "not found" in error,
      "expected not-found error referencing the requested name; got: $error",
    )
  }

  /**
   * Minimal no-op bridge — TrailMcpTool.handleList / handleRun never touch the
   * device-side methods in the paths under test. Anything that would dispatch a
   * side effect throws [NotImplementedError] so a regression that accidentally
   * routes through the device path fails the test with a clear signal.
   */
  private object NoopBridge : TrailblazeMcpBridge {
    override suspend fun selectDevice(trailblazeDeviceId: TrailblazeDeviceId): TrailblazeConnectedDeviceSummary =
      throw NotImplementedError("bridge should not be touched in list/find tests")

    override suspend fun getAvailableDevices(): Set<TrailblazeConnectedDeviceSummary> = emptySet()

    override suspend fun getInstalledAppIds(): Set<String> = emptySet()

    override fun getAvailableAppTargets(): Set<TrailblazeHostAppTarget> = emptySet()

    override suspend fun runYaml(
      yaml: String,
      startNewSession: Boolean,
      agentImplementation: AgentImplementation,
    ): String = throw NotImplementedError("runYaml must not be called in list/find tests")

    override fun getCurrentlySelectedDeviceId(): TrailblazeDeviceId? = null

    override suspend fun getCurrentScreenState(): ScreenState? = null

    override suspend fun executeTrailblazeTool(tool: TrailblazeTool, blocking: Boolean): String =
      throw NotImplementedError("executeTrailblazeTool must not be called in list/find tests")

    override suspend fun endSession(): Boolean = false

    override fun selectAppTarget(appTargetId: String): String? = null

    override fun getCurrentAppTargetId(): String? = null

    override fun getDriverType(): TrailblazeDriverType? = null

    override suspend fun getScreenStateViaRpc(
      includeScreenshot: Boolean,
      screenshotScalingConfig: ScreenshotScalingConfig,
      includeAnnotatedScreenshot: Boolean,
      includeAllElements: Boolean,
    ): GetScreenStateResponse? = null

    override fun getActiveSessionId(): SessionId? = null

    override suspend fun ensureSessionAndGetId(testName: String?): SessionId? = null
  }
}
