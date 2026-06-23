package xyz.block.trailblaze.mcp.agent

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotSameInstanceAs
import assertk.assertions.isSameInstanceAs
import xyz.block.trailblaze.api.AnnotationElement
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import kotlin.test.Test

/** Unit tests for [SharedScreenStateCapture] — capture-once-per-request + reuse. */
class SharedScreenStateCaptureTest {

  private class FakeScreenState : ScreenState {
    override val screenshotBytes: ByteArray? = null
    override val deviceWidth: Int = 1080
    override val deviceHeight: Int = 1920
    override val viewHierarchy: ViewHierarchyTreeNode = ViewHierarchyTreeNode()
    override val trailblazeDevicePlatform: TrailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID
    override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()
    override val trailblazeNodeTree: TrailblazeNode? = null
    override val annotationElements: List<AnnotationElement>? = null
  }

  @Test
  fun `captures once per request and reuses the snapshot within that request`() {
    var captures = 0
    val capture = SharedScreenStateCapture { captures++; FakeScreenState() }
    val provider = capture.asProvider()

    val first = provider()
    val second = provider()

    assertThat(captures).isEqualTo(1) // only one underlying capture for this request...
    assertThat(first).isSameInstanceAs(second) // ...and both callers see the same snapshot
  }

  @Test
  fun `clear forces a fresh capture for the next request`() {
    var captures = 0
    val capture = SharedScreenStateCapture { captures++; FakeScreenState() }
    val provider = capture.asProvider()

    val firstRequest = provider()
    capture.clear() // end of request 1 — release the snapshot
    val secondRequest = provider()

    assertThat(captures).isEqualTo(2)
    assertThat(firstRequest).isNotSameInstanceAs(secondRequest)
  }
}
