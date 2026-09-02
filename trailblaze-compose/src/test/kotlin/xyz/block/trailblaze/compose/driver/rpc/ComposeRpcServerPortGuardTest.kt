package xyz.block.trailblaze.compose.driver.rpc

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.SemanticsNode
import org.junit.Test
import xyz.block.trailblaze.compose.target.ComposeTestTarget
import xyz.block.trailblaze.devices.TrailblazeDevicePort
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

/**
 * The Compose driver's default RPC port is inside the device-allocation range, and it is only safe
 * there because it is reserved. An override to any other port in that range gets no such
 * protection: a device would be allocated the port and `adb forward` would take it from this
 * server without reporting an error, so the driver would go unreachable instead of failing here.
 */
class ComposeRpcServerPortGuardTest {

  @Test
  fun `an overridden port a device could be allocated is refused before binding`() {
    // Non-reserved, so a device really can be handed it — unlike the reserved default.
    val allocatable = TrailblazeDevicePort.COMPOSE_DEFAULT_RPC_PORT + 1
    val failure = assertFailsWith<IllegalStateException> {
      ComposeRpcServer(target = NoOpComposeTestTarget, port = allocatable).start(wait = false)
    }
    assertContains(failure.message!!, "The Compose driver RPC port is $allocatable")
  }

  /** Never invoked: the guard throws before the server is built. */
  private object NoOpComposeTestTarget : ComposeTestTarget {
    override fun rootSemanticsNode(): SemanticsNode = error("not invoked in guard tests")
    override fun allSemanticsNodes(): List<SemanticsNode> = error("not invoked in guard tests")
    override fun click(node: SemanticsNode) = error("not invoked in guard tests")
    override fun typeText(node: SemanticsNode, text: String) = error("not invoked in guard tests")
    override fun clearText(node: SemanticsNode) = error("not invoked in guard tests")
    override fun scrollToIndex(node: SemanticsNode, index: Int) = error("not invoked in guard tests")
    override fun captureScreenshot(): ImageBitmap? = null
    override fun waitForIdle() = Unit
  }
}
