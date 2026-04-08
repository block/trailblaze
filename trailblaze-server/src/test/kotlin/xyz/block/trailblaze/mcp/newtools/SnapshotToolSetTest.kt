package xyz.block.trailblaze.mcp.newtools

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.mcp.TrailblazeMcpMode
import xyz.block.trailblaze.mcp.TrailblazeMcpSessionContext
import xyz.block.trailblaze.mcp.ViewHierarchyVerbosity
import xyz.block.trailblaze.mcp.models.McpSessionId
import kotlin.test.assertContains
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [SnapshotToolSet].
 *
 * Verifies that the snapshot tool correctly:
 * - Returns screenshot + hierarchy by default
 * - Returns screenshot only when detail=SCREENSHOT
 * - Returns hierarchy only when detail=HIERARCHY
 * - Handles null screen state (no device connected)
 * - Respects verbosity settings
 */
class SnapshotToolSetTest {

  private val testSessionId = McpSessionId("test-session")

  private fun createSessionContext(
    verbosity: ViewHierarchyVerbosity = ViewHierarchyVerbosity.MINIMAL,
  ) =
    TrailblazeMcpSessionContext(
      mcpServerSession = null,
      mcpSessionId = testSessionId,
      mode = TrailblazeMcpMode.MCP_CLIENT_AS_AGENT,
      viewHierarchyVerbosity = verbosity,
    )

  private val testScreenState =
    object : ScreenState {
      override val screenshotBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) // PNG magic
      override val annotatedScreenshotBytes = screenshotBytes
      override val deviceWidth = 1080
      override val deviceHeight = 2400
      override val viewHierarchy =
        ViewHierarchyTreeNode(
          className = "android.widget.FrameLayout",
          children =
            listOf(
              ViewHierarchyTreeNode(
                className = "android.widget.Button",
                text = "Login",
                clickable = true,
                centerPoint = "540,1200",
              ),
              ViewHierarchyTreeNode(
                className = "android.widget.EditText",
                text = "",
                resourceId = "email_field",
                clickable = true,
                centerPoint = "540,800",
              ),
            ),
        )
      override val trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID
      override val deviceClassifiers = emptyList<TrailblazeDeviceClassifier>()
    }

  // ── Default (ALL) ──────────────────────────────────────────────────────────

  @Test
  fun `snapshot returns screenshot and hierarchy by default`() = runTest {
    val toolSet =
      SnapshotToolSet(
        screenStateProvider = { testScreenState },
        sessionContext = createSessionContext(),
      )

    val result = toolSet.snapshot()
    val json = Json.parseToJsonElement(result).jsonObject

    assertNotNull(json["screenshot"]?.jsonPrimitive?.content)
    assertNotNull(json["viewHierarchy"]?.jsonPrimitive?.content)
    assertTrue(json["screenshot"]!!.jsonPrimitive.content.isNotEmpty())
    assertTrue(json["viewHierarchy"]!!.jsonPrimitive.content.isNotEmpty())
    assertNull(json["error"])
  }

  @Test
  fun `snapshot includes device dimensions and platform`() = runTest {
    val toolSet =
      SnapshotToolSet(
        screenStateProvider = { testScreenState },
        sessionContext = createSessionContext(),
      )

    val result = toolSet.snapshot()
    val json = Json.parseToJsonElement(result).jsonObject

    kotlin.test.assertEquals("1080", json["deviceWidth"]?.jsonPrimitive?.content)
    kotlin.test.assertEquals("2400", json["deviceHeight"]?.jsonPrimitive?.content)
    kotlin.test.assertEquals("ANDROID", json["platform"]?.jsonPrimitive?.content)
  }

  // ── SCREENSHOT only ────────────────────────────────────────────────────────

  @Test
  fun `snapshot with SCREENSHOT detail returns only screenshot`() = runTest {
    val toolSet =
      SnapshotToolSet(
        screenStateProvider = { testScreenState },
        sessionContext = createSessionContext(),
      )

    val result = toolSet.snapshot(detail = SnapshotToolSet.SnapshotDetail.SCREENSHOT)
    val json = Json.parseToJsonElement(result).jsonObject

    assertNotNull(json["screenshot"]?.jsonPrimitive?.content)
    assertNull(json["viewHierarchy"]?.jsonPrimitive)
  }

  // ── HIERARCHY only ─────────────────────────────────────────────────────────

  @Test
  fun `snapshot with HIERARCHY detail returns only hierarchy`() = runTest {
    val toolSet =
      SnapshotToolSet(
        screenStateProvider = { testScreenState },
        sessionContext = createSessionContext(),
      )

    val result = toolSet.snapshot(detail = SnapshotToolSet.SnapshotDetail.HIERARCHY)
    val json = Json.parseToJsonElement(result).jsonObject

    assertNotNull(json["viewHierarchy"]?.jsonPrimitive?.content)
    assertNull(json["screenshot"]?.jsonPrimitive)
  }

  // ── Verbosity ──────────────────────────────────────────────────────────────

  @Test
  fun `snapshot FULL verbosity includes non-interactable elements`() = runTest {
    val toolSet =
      SnapshotToolSet(
        screenStateProvider = { testScreenState },
        sessionContext = createSessionContext(),
      )

    val result =
      toolSet.snapshot(
        detail = SnapshotToolSet.SnapshotDetail.HIERARCHY,
        verbosity = ViewHierarchyVerbosity.FULL,
      )
    val json = Json.parseToJsonElement(result).jsonObject
    val hierarchy = json["viewHierarchy"]!!.jsonPrimitive.content

    // FULL should include the FrameLayout parent (non-interactable)
    assertContains(hierarchy, "FrameLayout")
  }

  // ── No device connected ────────────────────────────────────────────────────

  @Test
  fun `snapshot returns error when no screen state available`() = runTest {
    val toolSet =
      SnapshotToolSet(
        screenStateProvider = { null },
        driverStatusProvider = { "No device connected. Use device(action=ANDROID) first." },
      )

    val result = toolSet.snapshot()
    val json = Json.parseToJsonElement(result).jsonObject

    assertContains(json["error"]!!.jsonPrimitive.content, "No device connected")
  }

  @Test
  fun `snapshot returns default error when no driver status provider`() = runTest {
    val toolSet =
      SnapshotToolSet(
        screenStateProvider = { null },
      )

    val result = toolSet.snapshot()
    val json = Json.parseToJsonElement(result).jsonObject

    assertNotNull(json["error"]?.jsonPrimitive?.content)
  }
}
