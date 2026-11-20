package xyz.block.trailblaze.host.lease

import xyz.block.trailblaze.host.lease.DeviceLeaseClient.RenewOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LeaseRenewalsTest {

  private val lease = Lease(leaseId = "l", secret = "s", ttlMs = 30, devices = emptyList())
  private var now = 0L
  private val renewals = LeaseRenewals(lease) { now }

  /** Records [outcome] for a renewal 10 ms after the last. */
  private fun renewal(outcome: RenewOutcome): String? {
    now += 10
    return renewals.record(outcome)
  }

  @Test
  fun `a lease the pool keeps renewing stays held`() {
    repeat(5) { assertNull(renewal(RenewOutcome.RENEWED)) }
  }

  @Test
  fun `a lease is lost at once when the pool says it is gone`() {
    assertNull(renewal(RenewOutcome.RENEWED))
    assertEquals("the pool no longer holds this lease", renewal(RenewOutcome.LOST))
  }

  @Test
  fun `an outage shorter than the ttl is ridden out`() {
    assertNull(renewal(RenewOutcome.UNREACHABLE))
    assertNull(renewal(RenewOutcome.UNREACHABLE))
    assertNull(renewal(RenewOutcome.RENEWED))
    assertNull(renewal(RenewOutcome.UNREACHABLE))
    assertNull(renewal(RenewOutcome.UNREACHABLE))
  }

  @Test
  fun `a lease is lost once no renewal succeeded for the whole ttl`() {
    assertNull(renewal(RenewOutcome.UNREACHABLE))
    assertNull(renewal(RenewOutcome.UNREACHABLE))
    assertEquals("the pool was unreachable for the lease's whole 30 ms", renewal(RenewOutcome.UNREACHABLE))
  }
}
