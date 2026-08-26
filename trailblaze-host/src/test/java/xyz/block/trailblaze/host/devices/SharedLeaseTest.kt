package xyz.block.trailblaze.host.devices

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The rule these pin down: an owner may let go of a shared resource, but only the last one to let go
 * closes it. Written against the iOS driver two owners are handed when their target wrappers agree -
 * an agent session driving the device and a viewer streaming its screen - where handing both the
 * right to close it meant whichever finished first left the other with a dead XCUITest connection.
 */
class SharedLeaseTest {

  private var closes = 0
  private val lease = SharedLease { closes++ }

  /** An owner taking its hold, asserting the lease didn't refuse one while it was still open. */
  private fun take(): AutoCloseable =
    lease.acquire() ?: error("the lease refused an owner while the resource was still open")

  @Test
  fun `the last owner to let go is the one that closes`() {
    val agent = take()
    val viewer = take()

    agent.close()
    assertEquals(0, closes, "a driver another owner is still using must stay open")

    viewer.close()
    assertEquals(1, closes)
  }

  @Test
  fun `the only owner closes it, so a single holder tears down exactly as before`() {
    take().close()

    assertEquals(1, closes)
  }

  @Test
  fun `closing one owner's handle twice releases it once, not somebody else's hold`() {
    val agent = take()
    val viewer = take()

    agent.close()
    agent.close()

    assertEquals(0, closes, "the second close must not stand in for the viewer letting go")
    viewer.close()
    assertEquals(1, closes)
  }

  @Test
  fun `a forced close happens once and their later releases don't repeat it`() {
    val agent = take()
    val viewer = take()

    lease.closeNow()
    assertEquals(1, closes, "a caller that can't wait for the owners closes it out from under them")

    agent.close()
    viewer.close()
    assertEquals(1, closes)
  }

  @Test
  fun `once the last owner has let go, a new one is refused rather than handed a closed resource`() {
    take().close()

    assertNull(lease.acquire(), "the resource is gone; a handle on it would never close it again")
    assertEquals(1, closes)
  }

  @Test
  fun `a forced close refuses new owners too, so the replacement can't bind to the old resource`() {
    take()
    lease.closeNow()

    assertNull(lease.acquire())
  }
}
