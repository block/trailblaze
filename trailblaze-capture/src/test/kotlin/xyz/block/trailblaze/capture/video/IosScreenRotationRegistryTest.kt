package xyz.block.trailblaze.capture.video

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for [IosScreenRotationRegistry].
 *
 * Two properties carry the weight, and both are about a subscriber that is a video recorder:
 *
 *  - **Repeats don't notify.** Every screen capture publishes, which in a long MCP session is
 *    every tool call. A recorder that was told about each of those would cut the session into a
 *    new video segment per call.
 *  - **Subscribing delivers what is already known.** A recording that starts on a device already
 *    in landscape has to come out of the gate rotated; waiting for the *next* change would record
 *    the whole session sideways whenever nothing rotates during it.
 */
class IosScreenRotationRegistryTest {

  private val device = "SIM-UDID-1"

  @BeforeTest
  fun setUp() = IosScreenRotationRegistry.reset()

  @AfterTest
  fun tearDown() = IosScreenRotationRegistry.reset()

  @Test
  fun `a subscriber is told the rotation that was already observed`() {
    IosScreenRotationRegistry.observe(device, IosScreenRotation.COUNTER_CLOCKWISE_90)

    val seen = mutableListOf<IosScreenRotation>()
    IosScreenRotationRegistry.subscribe(device) { seen += it }

    assertEquals(
      listOf(IosScreenRotation.COUNTER_CLOCKWISE_90),
      seen,
      "a recorder starting on an already-landscape device must learn that immediately, not on the " +
        "next rotation that may never come",
    )
  }

  @Test
  fun `a subscriber hears nothing when no rotation has been observed yet`() {
    val seen = mutableListOf<IosScreenRotation>()
    IosScreenRotationRegistry.subscribe(device) { seen += it }
    assertEquals(emptyList(), seen)
    assertNull(IosScreenRotationRegistry.current(device))
  }

  @Test
  fun `repeated observations of the same rotation notify once`() {
    val seen = mutableListOf<IosScreenRotation>()
    IosScreenRotationRegistry.subscribe(device) { seen += it }

    repeat(5) { IosScreenRotationRegistry.observe(device, IosScreenRotation.NONE) }
    repeat(5) { IosScreenRotationRegistry.observe(device, IosScreenRotation.CLOCKWISE_90) }

    assertEquals(
      listOf(IosScreenRotation.NONE, IosScreenRotation.CLOCKWISE_90),
      seen,
      "only changes are events — every screen capture publishes, and a segment per capture would " +
        "shred the recording",
    )
  }

  @Test
  fun `observations are per device`() {
    val other = "SIM-UDID-2"
    val seen = mutableListOf<IosScreenRotation>()
    IosScreenRotationRegistry.subscribe(device) { seen += it }

    IosScreenRotationRegistry.observe(other, IosScreenRotation.HALF_TURN)

    assertEquals(emptyList(), seen, "another simulator's orientation must not rotate this recording")
    assertEquals(IosScreenRotation.HALF_TURN, IosScreenRotationRegistry.current(other))
  }

  @Test
  fun `closing a subscription stops the updates`() {
    val seen = mutableListOf<IosScreenRotation>()
    val subscription = IosScreenRotationRegistry.subscribe(device) { seen += it }

    subscription.close()
    IosScreenRotationRegistry.observe(device, IosScreenRotation.CLOCKWISE_90)

    assertEquals(
      emptyList(),
      seen,
      "a finished recorder must not be handed rotations — it would roll a segment onto a session " +
        "that has already been written out",
    )
  }

  @Test
  fun `a change observed while another is being delivered reaches subscribers after it`() {
    // Two captures on one device can publish at once. If the second could record and deliver its
    // rotation in the middle of the first's delivery, the first would land last: the subscriber
    // would finish on a rotation the device has left, and every later observation of the real one
    // would be a repeat that never notifies.
    val events = CopyOnWriteArrayList<String>()
    var otherCapture: Thread? = null
    IosScreenRotationRegistry.subscribe(device) { rotation ->
      events += "start $rotation"
      if (rotation == IosScreenRotation.CLOCKWISE_90) {
        otherCapture = thread { IosScreenRotationRegistry.observe(device, IosScreenRotation.HALF_TURN) }
        // Give the other capture every chance to deliver while this delivery is still running.
        otherCapture!!.join(300)
      }
      events += "end $rotation"
    }

    IosScreenRotationRegistry.observe(device, IosScreenRotation.CLOCKWISE_90)
    otherCapture!!.join(5_000)

    assertEquals(
      listOf("start CLOCKWISE_90", "end CLOCKWISE_90", "start HALF_TURN", "end HALF_TURN"),
      events.toList(),
      "each device's changes must reach subscribers one at a time, in the order they were observed",
    )
    assertEquals(IosScreenRotation.HALF_TURN, IosScreenRotationRegistry.current(device))
  }
}
