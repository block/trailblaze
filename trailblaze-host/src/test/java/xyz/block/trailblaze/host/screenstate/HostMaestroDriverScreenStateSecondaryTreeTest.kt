package xyz.block.trailblaze.host.screenstate

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import kotlinx.coroutines.CancellationException
import maestro.DeviceInfo
import maestro.Driver
import maestro.TreeNode
import maestro.device.Platform
import okio.Sink
import okio.buffer
import org.junit.Test
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.viewmatcher.matching.ViewHierarchyOnlyDriver
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.assertFailsWith

/**
 * Pins WHEN the driver-migration side capture runs. A migration hit-tests a coordinate resolved
 * in the primary tree against the secondary tree, so the two have to describe the same screen.
 * The screenshot between them takes 200-500 ms on a simulator, which is long enough for an
 * animation or a network-driven update to move the screen — so the secondary read has to land
 * between the primary tree read and the screenshot, not after the screen state is finished.
 */
class HostMaestroDriverScreenStateSecondaryTreeTest {

  private val axeTree = TrailblazeNode(
    nodeId = 11,
    driverDetail = DriverNodeDetail.IosAxe(role = "AXButton", label = "Continue"),
  )

  /** Records the order of the driver reads the screen state build performs. */
  private class RecordingDriver(
    private val delegate: ViewHierarchyOnlyDriver,
    private val calls: MutableList<String>,
  ) : Driver by delegate {

    override fun contentDescriptor(excludeKeyboardElements: Boolean): TreeNode {
      calls.add("contentDescriptor")
      return delegate.contentDescriptor(excludeKeyboardElements)
    }

    override fun takeScreenshot(out: Sink, compressed: Boolean) {
      calls.add("takeScreenshot")
      // A real (1x1) PNG: the screen state decodes whatever is written here.
      val png = ByteArrayOutputStream()
        .also { ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", it) }
        .toByteArray()
      out.buffer().apply {
        write(png)
        flush()
      }
    }
  }

  private fun driver(calls: MutableList<String>) = RecordingDriver(
    delegate = ViewHierarchyOnlyDriver(
      rootTreeNode = TreeNode(
        attributes = mutableMapOf("bounds" to "[0,0][400,800]", "text" to "Continue"),
      ),
      // iOS: the only platform with a host-side secondary-tree producer, and the only one the
      // host still maps a Maestro tree for.
      deviceInfo = DeviceInfo(
        platform = Platform.IOS,
        widthPixels = 400,
        heightPixels = 800,
        widthGrid = 400,
        heightGrid = 800,
      ),
    ),
    calls = calls,
  )

  private fun screenState(
    calls: MutableList<String>,
    skipScreenshot: Boolean,
    secondaryTreeCapture: (() -> SecondaryTreeResult)?,
  ) = HostMaestroDriverScreenState(
    maestroDriver = driver(calls),
    screenshotScalingConfig = null,
    skipScreenshot = skipScreenshot,
    secondaryTreeCapture = secondaryTreeCapture,
  )

  @Test
  fun `the secondary tree is read after the primary tree and before the screenshot`() {
    val calls = mutableListOf<String>()

    val state = screenState(calls, skipScreenshot = false) {
      calls.add("axe")
      SecondaryTreeResult(axeTree)
    }

    assertThat(calls).containsExactly("contentDescriptor", "axe", "takeScreenshot")
    assertThat(state.secondaryTreeCaptureRan).isTrue()
    assertThat(state.driverMigrationTreeNode).isEqualTo(axeTree)
    assertThat(state.secondaryTreeFailure).isNull()
  }

  @Test
  fun `the secondary tree is read after the primary tree when the screenshot is skipped`() {
    val calls = mutableListOf<String>()

    val state = screenState(calls, skipScreenshot = true) {
      calls.add("axe")
      SecondaryTreeResult(axeTree)
    }

    assertThat(calls).containsExactly("contentDescriptor", "axe")
    assertThat(state.driverMigrationTreeNode).isEqualTo(axeTree)
  }

  @Test
  fun `with no capture configured nothing extra is read and the state says so`() {
    val calls = mutableListOf<String>()

    val state = screenState(calls, skipScreenshot = false, secondaryTreeCapture = null)

    assertThat(calls).containsExactly("contentDescriptor", "takeScreenshot")
    assertThat(state.secondaryTreeCaptureRan).isFalse()
    assertThat(state.driverMigrationTreeNode).isNull()
    assertThat(state.secondaryTreeFailure).isNull()
  }

  @Test
  fun `a capture that yields nothing still counts as having run`() {
    val calls = mutableListOf<String>()

    val state = screenState(calls, skipScreenshot = false) {
      calls.add("axe")
      SecondaryTreeResult.failed("axe is not available")
    }

    assertThat(calls).containsExactly("contentDescriptor", "axe", "takeScreenshot")
    assertThat(state.secondaryTreeCaptureRan).isTrue()
    assertThat(state.driverMigrationTreeNode).isNull()
    assertThat(state.secondaryTreeFailure).isEqualTo("axe is not available")
  }

  @Test
  fun `a capture that throws an exception leaves the primary capture intact`() {
    val calls = mutableListOf<String>()

    val state = screenState(calls, skipScreenshot = false) {
      calls.add("axe")
      throw RuntimeException("axe subprocess died")
    }

    // The migration aid rides along on the capture every runtime tool depends on; it must never
    // be able to take that capture down.
    assertThat(calls).containsExactly("contentDescriptor", "axe", "takeScreenshot")
    assertThat(state.trailblazeNodeTree).isNotNull()
    assertThat(state.viewHierarchy).isNotNull()
    assertThat(state.secondaryTreeCaptureRan).isTrue()
    assertThat(state.driverMigrationTreeNode).isNull()
    assertThat(state.secondaryTreeFailure).isEqualTo("axe subprocess died")
  }

  @Test
  fun `a capture that throws an Error leaves the primary capture intact`() {
    val calls = mutableListOf<String>()

    val state = screenState(calls, skipScreenshot = false) {
      calls.add("axe")
      throw StackOverflowError("deep tree walk")
    }

    assertThat(calls).containsExactly("contentDescriptor", "axe", "takeScreenshot")
    assertThat(state.trailblazeNodeTree).isNotNull()
    assertThat(state.secondaryTreeCaptureRan).isTrue()
    assertThat(state.driverMigrationTreeNode).isNull()
  }

  @Test
  fun `cancellation propagates so an aborted trail still unwinds`() {
    assertFailsWith<CancellationException> {
      screenState(mutableListOf(), skipScreenshot = false) {
        throw CancellationException("trail aborted")
      }
    }
  }

  @Test
  fun `the tree stamp is taken before the secondary capture runs`() {
    var axeStartedAtMs = 0L

    val state = screenState(mutableListOf(), skipScreenshot = false) {
      axeStartedAtMs = System.currentTimeMillis()
      Thread.sleep(300)
      SecondaryTreeResult(axeTree)
    }

    // Stream-mode frame matching compares a frame timestamp against this stamp, so it has to
    // mark the tree read — not the end of a build that also paid for axe and the screenshot.
    assertThat(state.treeCapturedAtHostMs).isLessThanOrEqualTo(axeStartedAtMs)
    assertThat(System.currentTimeMillis() - state.treeCapturedAtHostMs)
      .isGreaterThanOrEqualTo(300L)
  }
}
