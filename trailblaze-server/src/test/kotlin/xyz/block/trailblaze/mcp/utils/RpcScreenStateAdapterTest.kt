package xyz.block.trailblaze.mcp.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateResponse

/**
 * The host end of the capture-completeness signal.
 *
 * On a host-orchestrated Android run the screen the matcher and the query tools evaluate is this
 * adapter over an RPC response, not the on-device capture itself. If the count stops at the wire,
 * every host-side consumer silently reverts to treating a capture that lost nodes as a complete
 * one — the exact false-absence this signal exists to prevent, with nothing to show it happened.
 */
class RpcScreenStateAdapterTest {

  @Test
  fun `a capture the device reported as having lost nodes reads as partial host-side`() {
    val screenState = RpcScreenStateAdapter(response(droppedNodeFetches = 5))

    assertEquals(5, screenState.droppedNodeFetches)
    assertTrue(screenState.isCaptureKnownPartial)
  }

  @Test
  fun `a capture the device reported as complete reads as complete`() {
    val screenState = RpcScreenStateAdapter(response(droppedNodeFetches = 0))

    assertEquals(0, screenState.droppedNodeFetches)
    assertFalse(screenState.isCaptureKnownPartial)
  }

  @Test
  fun `a device that reported nothing reads as unknown, which is not partial`() {
    // An on-device server that predates the field, or a request that asked for no tree. Unknown
    // must not be read as partial: that would make every forbidden-bearing waypoint and every
    // absence-shaped findMatches unanswerable against an older device.
    val screenState = RpcScreenStateAdapter(response(droppedNodeFetches = null))

    assertNull(screenState.droppedNodeFetches)
    assertFalse(screenState.isCaptureKnownPartial)
  }

  private fun response(droppedNodeFetches: Int?) = GetScreenStateResponse(
    viewHierarchy = ViewHierarchyTreeNode(),
    screenshotBase64 = null,
    deviceWidth = 1080,
    deviceHeight = 1920,
    droppedNodeFetches = droppedNodeFetches,
  )
}
