package xyz.block.trailblaze.host.lease

import xyz.block.trailblaze.devices.TrailblazeDriverType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DeviceLeaseRegistryTest {

  private var now = 0L
  private var ids = 0
  private val usedHere = mutableSetOf<String>()
  private val registry = DeviceLeaseRegistry(
    ttlMs = 1_000,
    clock = { now },
    newId = { "lease-${++ids}" },
    newSecret = { "s$ids" },
    locallyInUse = { it in usedHere },
  )

  // Any platform lends the same way.
  private val a = LeasableDevice("android/emulator-5554", TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY, "Pixel 8")
  private val b = LeasableDevice("ios/8F2C-SIM", TrailblazeDriverType.IOS_HOST)
  private val attached = listOf(b, a)

  private fun request(vararg devices: LeasableDevice) = LeaseRequest(devices = devices.map { it.id }, holder = "me")

  private fun grant(vararg devices: LeasableDevice, available: List<LeasableDevice> = attached): Lease =
    assertIs<LeaseResult.Granted>(registry.lease(available, request(*devices))).lease

  @Test
  fun `a lease takes the named device and it shows as leased`() {
    assertEquals(listOf(a), grant(a).devices)
    assertEquals(
      listOf(
        LeasableDeviceStatus(a.id, a.driverType, "Pixel 8", leased = true, holder = "me", busy = true),
        LeasableDeviceStatus(b.id, b.driverType, leased = false),
      ),
      registry.status(attached),
    )
  }

  @Test
  fun `a device a session on this machine is using is busy to borrowers`() {
    usedHere += a.id
    val busy = assertIs<LeaseResult.Busy>(registry.lease(attached, request(a)))
    assertEquals(DeviceLeaseRegistry.LOCAL_HOLDER, busy.holder)
    usedHere.clear()
    grant(a)
  }

  @Test
  fun `a session on this machine takes a device from an idle borrower, not a busy one`() {
    val lease = grant(a)
    assertEquals("me", registry.takeForLocalUse(a.id))
    registry.renew(lease.leaseId, lease.secret, busy = false)
    assertEquals(null, registry.takeForLocalUse(a.id))
    assertFalse(registry.renew(lease.leaseId, lease.secret))
    // Busy to borrowers before the local claim lands, so none takes it in between.
    assertIs<LeaseResult.Busy>(registry.lease(attached, request(a)))
  }

  @Test
  fun `taking one device of an idle lease leaves its holder the rest`() {
    val theirs = grant(a, b)
    registry.renew(theirs.leaseId, theirs.secret, busy = false)
    grant(a)
    assertTrue(registry.renew(theirs.leaseId, theirs.secret))
    assertTrue(registry.holds(theirs.leaseId, theirs.secret, b.id))
    assertFalse(registry.holds(theirs.leaseId, theirs.secret, a.id))
  }

  @Test
  fun `only the lease that holds a device, with its secret, may reach it`() {
    val first = grant(a)
    assertTrue(registry.holds(first.leaseId, first.secret, a.id))
    assertFalse(registry.holds(first.leaseId, "guess", a.id))
    assertFalse(registry.holds(first.leaseId, first.secret, b.id))
    registry.renew(first.leaseId, first.secret, busy = false)
    val second = grant(a)
    assertFalse(registry.holds(first.leaseId, first.secret, a.id))
    assertTrue(registry.holds(second.leaseId, second.secret, a.id))
    now += 1_000
    assertFalse(registry.holds(second.leaseId, second.secret, a.id))
  }

  @Test
  fun `a group is taken all or none`() {
    grant(a)
    val busy = assertIs<LeaseResult.Busy>(registry.lease(attached, request(b, a)))
    assertTrue("android/emulator-5554 is busy: me is using it" in busy.reason, busy.reason)
    assertEquals(listOf(b), grant(b).devices)
  }

  @Test
  fun `a device just leased is not lent again`() {
    grant(a, b)
    assertIs<LeaseResult.Busy>(registry.lease(attached, request(a)))
  }

  @Test
  fun `an unrenewed lease expires and its devices are free again`() {
    grant(a, b)
    now += 1_000
    assertEquals(2, grant(a, b).devices.size)
  }

  @Test
  fun `renewing keeps a lease past its original ttl`() {
    val lease = grant(a, b)
    now += 900
    assertTrue(registry.renew(lease.leaseId, lease.secret, busy = true))
    now += 900
    assertIs<LeaseResult.Busy>(registry.lease(attached, request(a)))
  }

  @Test
  fun `a returned lease frees its devices once their cleanup is done, and cannot be renewed`() {
    val lease = grant(a, b)
    assertEquals(listOf(a, b), registry.release(lease.leaseId, lease.secret))
    assertFalse(registry.renew(lease.leaseId, lease.secret))
    registry.finishReturn(a.id)
    registry.finishReturn(b.id)
    assertEquals(2, grant(a, b).devices.size)
  }

  @Test
  fun `a returned device nobody can take until its own cleanup is done`() {
    val lease = grant(a, b)
    registry.release(lease.leaseId, lease.secret)
    registry.finishReturn(a.id)
    // b's cleanup is still running, so neither a borrower nor a session here can take it yet.
    val busy = assertIs<LeaseResult.Busy>(registry.lease(attached, request(b)))
    assertEquals("me", busy.holder)
    assertEquals("me", registry.takeForLocalUse(b.id))
    assertEquals(
      LeasableDeviceStatus(b.id, b.driverType, leased = true, holder = "me", busy = true),
      registry.status(attached).single { it.id == b.id },
    )
    grant(a)
    registry.finishReturn(b.id)
    grant(b)
  }

  @Test
  fun `the wrong secret can neither renew nor return a lease`() {
    val lease = grant(a)
    assertFalse(registry.renew(lease.leaseId, "guess"))
    assertEquals(null, registry.release(lease.leaseId, "guess"))
    assertIs<LeaseResult.Busy>(registry.lease(attached, request(a)))
  }

  @Test
  fun `a device no longer connected here is not lent`() {
    val busy = assertIs<LeaseResult.Busy>(registry.lease(listOf(b), request(a)))
    assertTrue("not connected" in busy.reason, busy.reason)
    assertEquals(listOf(b), grant(b, available = listOf(b)).devices)
  }

  @Test
  fun `a device whose holder is idle goes to whoever asks next, and the holder finds out`() {
    val old = grant(a)
    assertTrue(registry.renew(old.leaseId, old.secret, busy = false))
    val taken = assertIs<LeaseResult.Granted>(registry.lease(attached, LeaseRequest(listOf(a.id), holder = "bob"))).lease
    assertFalse(registry.renew(old.leaseId, old.secret))
    assertTrue(registry.renew(taken.leaseId, taken.secret))
    assertEquals("bob", registry.status(attached).single { it.id == a.id }.holder)
  }

  @Test
  fun `a device its holder is using is refused, naming the holder`() {
    val old = grant(a)
    assertTrue(registry.renew(old.leaseId, old.secret, busy = true))
    val busy = assertIs<LeaseResult.Busy>(registry.lease(attached, LeaseRequest(listOf(a.id), holder = "bob")))
    assertEquals("me", busy.holder)
    assertTrue(registry.renew(old.leaseId, old.secret))
  }
}
