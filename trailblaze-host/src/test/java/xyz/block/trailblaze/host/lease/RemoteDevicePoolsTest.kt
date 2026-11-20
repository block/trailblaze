package xyz.block.trailblaze.host.lease

import kotlinx.coroutines.runBlocking
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Drives the borrowing side against a fake lender, on a clock the test moves by hand: [advance] runs
 * each renewal as it falls due, on the test thread, so nothing waits on real time.
 */
class RemoteDevicePoolsTest {

  private val poolUrl = "http://pool:31900"

  /** Virtual time, in ms; only [advance] moves it. */
  private var now = 0L

  private class Repeating(val intervalMs: Long, var dueAtMs: Long, val task: () -> Boolean)

  private val repeating = mutableListOf<Repeating>()

  private val repeater = RemoteDevicePools.Repeater { _, intervalMs, task -> repeating += Repeating(intervalMs, now + intervalMs, task) }

  /** Moves the clock [ms] ahead, running every renewal that falls due on the way, in order. */
  private fun advance(ms: Long) {
    val until = now + ms
    while (true) {
      val next = repeating.filter { it.dueAtMs <= until }.minByOrNull { it.dueAtMs } ?: break
      now = next.dueAtMs
      if (next.task()) next.dueAtMs += next.intervalMs else repeating.remove(next)
    }
    now = until
  }

  /** How often the pool renews the fake lender's 30 ms lease. */
  private val renewalMs = 10L

  /** A lender with an Android phone and an iOS simulator, answering calls the way its endpoint does. */
  private inner class FakeLender : DeviceLeaseClient(poolUrl) {
    val registry = DeviceLeaseRegistry(ttlMs = 30, clock = { now })
    val attached = listOf(
      LeasableDevice("android/emulator-5554", TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY, "Pixel 8"),
      LeasableDevice("ios/8F2C-SIM", TrailblazeDriverType.IOS_HOST),
    )
    val leaseCalls = CopyOnWriteArrayList<LeaseRequest>()
    val granted = CopyOnWriteArrayList<Lease>()
    val returned = CopyOnWriteArrayList<String>()
    val calls = CopyOnWriteArrayList<RemoteDeviceCall>()

    @Volatile var renewals = RenewOutcome.RENEWED

    /** Thrown by the next call, as an unreachable lender would. */
    @Volatile var callThrows: IOException? = null

    /** Runs inside a call, while the lender is still working on it. */
    @Volatile var duringCall: () -> Unit = {}

    override fun devices() = registry.status(attached)

    override fun lease(request: LeaseRequest): LeaseOutcome {
      leaseCalls += request
      return when (val result = registry.lease(attached, request)) {
        is LeaseResult.Granted -> LeaseOutcome.Granted(result.lease).also { granted += result.lease }
        is LeaseResult.Busy -> LeaseOutcome.Busy(result.reason, result.holder)
      }
    }

    override fun renew(lease: Lease, busy: Boolean) = when {
      renewals != RenewOutcome.RENEWED -> renewals
      registry.renew(lease.leaseId, lease.secret, busy) -> RenewOutcome.RENEWED
      else -> RenewOutcome.LOST
    }

    override fun call(lease: Lease, request: RemoteDeviceCall): RemoteDeviceReply {
      callThrows?.let { callThrows = null; throw it }
      if (!registry.holds(lease.leaseId, lease.secret, request.device)) throw LeaseLostException("lease ${lease.leaseId} is gone")
      calls += request
      duringCall()
      return RemoteDeviceReply(toolResult = RemoteToolResult(success = true, message = "ran on ${request.device}"))
    }

    val installs = CopyOnWriteArrayList<Pair<String, String>>()

    override fun install(lease: Lease, device: String, appFile: File): RemoteInstallReply {
      if (!registry.holds(lease.leaseId, lease.secret, device)) throw LeaseLostException("lease ${lease.leaseId} is gone")
      installs += device to appFile.readText()
      duringCall()
      return RemoteInstallReply(success = true, message = "installed on $device")
    }

    override fun release(lease: Lease) {
      registry.release(lease.leaseId, lease.secret)?.forEach { registry.finishReturn(it.id) }
      returned += lease.leaseId
    }
  }

  private val lender = FakeLender()

  /** The lenders [pools] asks; a test empties it to unpair. */
  @Volatile private var poolUrls = listOf(poolUrl)

  private var clientsMade = 0

  private fun pools(
    lingerMs: Long = 60_000,
    busyWindowMs: Long = 60_000,
    lending: DeviceLeaseRegistry? = null,
  ) = RemoteDevicePools(
    poolUrls = { poolUrls },
    holder = "me@laptop",
    lingerMs = lingerMs,
    clientFor = { clientsMade++; lender },
    now = { now },
    busyWindowMs = busyWindowMs,
    lender = { lending },
    repeater = repeater,
  ).also { created += it }

  private val created = mutableListOf<RemoteDevicePools>()

  @AfterTest
  fun returnLeases() = created.forEach { it.returnAll() }

  /** The lender's phone, as this machine names it. */
  private val phone = TrailblazeDeviceId("emulator-5554@pool:31900", TrailblazeDevicePlatform.ANDROID)
  private val simulator = TrailblazeDeviceId("8F2C-SIM@pool:31900", TrailblazeDevicePlatform.IOS)

  /** One of this machine's own devices. */
  private val ownPhone = TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID)

  private fun lenderRow() = lender.devices().single { it.id == "android/emulator-5554" }

  private fun discovered(pools: RemoteDevicePools) = pools.also { it.remoteDevices() }

  private fun tap(pools: RemoteDevicePools, device: TrailblazeDeviceId = phone) =
    pools.call(device, RemoteDeviceCall(device = "", op = RemoteDeviceCall.Op.RUN_TOOL, toolYaml = "- pressBack: {}"))

  /** What the claim registry does: [RemoteDevicePools.beforeClaim], then the claim lands. */
  private fun claim(pools: RemoteDevicePools, device: TrailblazeDeviceId) {
    pools.beforeClaim(device)
    pools.onClaimed(device)
  }

  /** Someone on another machine leases the phone; [busy] is what their renewal says. */
  private fun someoneHolds(holder: String = "someone", busy: Boolean): Lease {
    val lease = (lender.registry.lease(lender.attached, LeaseRequest(listOf("android/emulator-5554"), holder)) as LeaseResult.Granted).lease
    lender.registry.renew(lease.leaseId, lease.secret, busy)
    return lease
  }

  @Test
  fun `discovery lists every platform the lender lends, by the machine it is on`() {
    val listed = pools().remoteDevices()
    assertEquals(listOf(phone, simulator), listed.map { it.trailblazeDeviceId })
    assertEquals(listOf("Pixel 8 on pool:31900", "on pool:31900"), listed.map { it.description })
    assertEquals(
      listOf(TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY, TrailblazeDriverType.IOS_HOST),
      listed.map { it.trailblazeDriverType },
    )
  }

  @Test
  fun `a device someone else holds is still listed, as a local device another session holds is`() {
    someoneHolds(busy = true)
    assertEquals(listOf(phone, simulator), pools().remoteDevices().map { it.trailblazeDeviceId })
  }

  @Test
  fun `a call on a listed device leases it and reaches the lender under the lender's name for it`() {
    val pools = discovered(pools())
    val reply = tap(pools, simulator)
    assertEquals("ran on ios/8F2C-SIM", reply.toolResult?.message)
    assertEquals(listOf(LeaseRequest(listOf("ios/8F2C-SIM"), holder = "me@laptop")), lender.leaseCalls)
    assertEquals(setOf(simulator), pools.leasedDevices())
  }

  @Test
  fun `an install on a listed device leases it and sends the app to the lender under its name for it`() {
    val pools = discovered(pools(busyWindowMs = 0))
    val apk = File.createTempFile("app", ".apk").apply { writeText("apk bytes"); deleteOnExit() }
    var busyDuringInstall = false
    lender.duringCall = {
      advance(200) // Far past the fake lender's 30 ms lease, as a large install would be.
      busyDuringInstall = lenderRow().busy
    }
    val reply = pools.install(phone, apk)
    assertEquals(RemoteInstallReply(success = true, message = "installed on android/emulator-5554"), reply)
    assertEquals(listOf("android/emulator-5554" to "apk bytes"), lender.installs)
    assertEquals(setOf(phone), pools.leasedDevices())
    assertTrue(busyDuringInstall, "the device is busy to others while the app installs")
  }

  @Test
  fun `calls within the linger share one lease`() {
    val pools = discovered(pools())
    tap(pools)
    tap(pools)
    assertEquals(1, lender.leaseCalls.size)
    assertEquals(2, lender.calls.size)
  }

  @Test
  fun `a device someone is using refuses, naming them, and sends nothing`() {
    someoneHolds(busy = true)
    val pools = discovered(pools())
    val error = assertFailsWith<IllegalStateException> { tap(pools) }
    assertEquals(
      "Device android/emulator-5554@pool:31900 is busy.\n  Held by: someone (on http://pool:31900)\n" +
        "Wait for it to finish, or stop the holder before retrying.",
      error.message,
    )
    assertTrue(lender.calls.isEmpty())
  }

  @Test
  fun `a device whose holder is idle is taken from them`() {
    val theirs = someoneHolds(busy = false)
    val pools = discovered(pools())
    tap(pools)
    assertEquals(setOf(phone), pools.leasedDevices())
    assertFalse(lender.registry.renew(theirs.leaseId, theirs.secret))
    assertEquals("me@laptop", lenderRow().holder)
  }

  @Test
  fun `a call is busy to others until the lender answers it, however long it takes`() {
    val pools = discovered(pools(busyWindowMs = 0))
    lender.duringCall = {
      advance(200) // Far past the fake lender's 30 ms lease; each renewal on the way says busy.
      assertTrue(lenderRow().busy)
      assertTrue(lender.registry.lease(lender.attached, LeaseRequest(listOf("android/emulator-5554"), "bob")) is LeaseResult.Busy)
    }
    tap(pools)
    lender.duringCall = {}
    advance(renewalMs)
    assertFalse(lenderRow().busy, "idle once the call is answered")
  }

  @Test
  fun `a call after the lender gave the device away while it was idle here leases it again`() {
    val pools = discovered(pools(busyWindowMs = 0))
    tap(pools)
    advance(renewalMs)
    assertFalse(lenderRow().busy, "this daemon's renewal reports it idle")
    someoneHolds("bob", busy = false)
    tap(pools)
    assertEquals(2, lender.leaseCalls.size)
    assertEquals("me@laptop", lenderRow().holder)
  }

  @Test
  fun `using a device again after it was reported idle tells the lender it is busy first`() {
    val pools = discovered(pools(busyWindowMs = 300))
    pools.ensureLeased(phone)
    advance(299)
    assertTrue(lenderRow().busy, "busy within the window")
    advance(1)
    assertFalse(lenderRow().busy, "this daemon's renewal reports it idle")
    pools.ensureLeased(phone)
    // The lender knew before the call returned, so nobody could take it as this use began.
    assertTrue(lenderRow().busy)
  }

  @Test
  fun `a call the lender refuses because the lease is gone says so, and the next call leases again`() {
    val pools = discovered(pools())
    tap(pools)
    // The lender ended the lease (it restarted, say) before this daemon's next renewal noticed.
    val lease = lender.granted.single()
    lender.registry.release(lease.leaseId, lease.secret)?.forEach { lender.registry.finishReturn(it.id) }
    val error = assertFailsWith<IllegalStateException> { tap(pools) }
    assertTrue(error.message!!.contains("is no longer lent to this machine"), error.message)
    assertTrue(pools.leasedDevices().isEmpty())
    tap(pools)
    assertEquals(2, lender.leaseCalls.size)
  }

  @Test
  fun `an unreachable lender fails the call and keeps the lease for the next one`() {
    val pools = discovered(pools())
    tap(pools)
    lender.callThrows = IOException("connection refused")
    val error = assertFailsWith<IllegalStateException> { tap(pools) }
    assertTrue(error.message!!.contains("connection refused"), error.message)
    assertEquals(setOf(phone), pools.leasedDevices())
    tap(pools)
    assertEquals(1, lender.leaseCalls.size)
  }

  @Test
  fun `a session's tool call keeps its device busy however quiet the device is`() {
    val pools = discovered(pools(busyWindowMs = 0))
    val inCall = AtomicBoolean(true)
    pools.watchToolCalls { inCall.get() }
    claim(pools, phone)
    advance(100)
    assertTrue(lenderRow().busy)
    inCall.set(false)
    advance(renewalMs)
    assertFalse(lenderRow().busy, "this daemon's renewal reports it idle")
  }

  @Test
  fun `a device taken while idle here is forgotten at once`() {
    val pools = discovered(pools(busyWindowMs = 0))
    pools.ensureLeased(phone)
    advance(renewalMs)
    assertFalse(lenderRow().busy, "this daemon's renewal reports it idle")
    lender.registry.lease(lender.attached, LeaseRequest(listOf("android/emulator-5554"), holder = "bob"))
    advance(renewalMs)
    assertTrue(pools.leasedDevices().isEmpty())
  }

  @Test
  fun `a device in use here is not taken`() {
    val pools = discovered(pools())
    pools.runStarted(phone)
    advance(100)
    assertTrue(lender.registry.lease(lender.attached, LeaseRequest(listOf("android/emulator-5554"), holder = "bob")) is LeaseResult.Busy)
    assertEquals(setOf(phone), pools.leasedDevices())
  }

  @Test
  fun `a released device is returned after the linger`() {
    val pools = discovered(pools(lingerMs = 50))
    claim(pools, phone)
    pools.onReleased(phone)
    advance(40)
    assertTrue(lender.returned.isEmpty(), "kept within the linger")
    advance(10)
    assertEquals(1, lender.returned.size)
    assertTrue(pools.leasedDevices().isEmpty())
  }

  @Test
  fun `claiming again within the linger keeps the same lease`() {
    val pools = discovered(pools())
    claim(pools, phone)
    pools.onReleased(phone)
    claim(pools, phone)
    assertEquals(1, lender.leaseCalls.size)
  }

  @Test
  fun `a claimed device is kept however long the claim lasts`() {
    val pools = discovered(pools(lingerMs = 20))
    claim(pools, phone)
    advance(200)
    assertEquals(setOf(phone), pools.leasedDevices())
    assertTrue(lender.returned.isEmpty())
  }

  @Test
  fun `a lease whose claim never lands is still returned`() {
    val pools = discovered(pools(lingerMs = 50))
    pools.beforeClaim(phone)
    advance(50)
    assertEquals(1, lender.returned.size)
  }

  @Test
  fun `a device is kept while a run is on it and returned after the linger once it ends`() {
    val pools = discovered(pools(lingerMs = 50))
    pools.runStarted(phone)
    advance(200)
    assertEquals(setOf(phone), pools.leasedDevices())
    pools.runEnded(phone)
    advance(50)
    assertEquals(1, lender.returned.size)
  }

  @Test
  fun `a run's device is kept while a second run on it is still going`() {
    val pools = discovered(pools(lingerMs = 20))
    pools.runStarted(phone)
    pools.runStarted(phone)
    pools.runEnded(phone)
    advance(200)
    assertEquals(setOf(phone), pools.leasedDevices())
  }

  @Test
  fun `a lost lease is forgotten at once, and the next use leases again`() {
    val pools = discovered(pools())
    claim(pools, phone)
    lender.renewals = DeviceLeaseClient.RenewOutcome.LOST
    advance(renewalMs)
    assertTrue(pools.leasedDevices().isEmpty())
    lender.renewals = DeviceLeaseClient.RenewOutcome.RENEWED
    advance(lender.registry.ttlMs)
    claim(pools, phone)
    assertEquals(2, lender.leaseCalls.size)
  }

  @Test
  fun `a held device stays listed after unpairing, and the rest of the lender's do not`() {
    val pools = discovered(pools())
    claim(pools, phone)
    poolUrls = emptyList()
    assertEquals(listOf(phone), pools.remoteDevices().map { it.trailblazeDeviceId })
    assertTrue(pools.isRemote(phone))
    assertFalse(pools.isRemote(simulator))
  }

  @Test
  fun `one client is made per lender`() {
    val pools = discovered(pools())
    pools.remoteDevices()
    claim(pools, phone)
    tap(pools)
    assertEquals(1, clientsMade)
  }

  @Test
  fun `a borrowed device can't be opened in the device viewer yet, and is not leased for it`() {
    val pools = discovered(pools())
    assertTrue(pools.beforeInteractiveUse(phone)!!.contains("viewing a borrowed device isn't supported yet"))
    val result = runBlocking { pools.whileConnecting(phone) { "connected" } }
    assertTrue(result.isFailure)
    assertTrue(lender.leaseCalls.isEmpty())
  }

  @Test
  fun `a lending daemon's own session keeps borrowers off the device it claims`() {
    lateinit var here: RemoteDevicePools
    val lending = DeviceLeaseRegistry(ttlMs = 60_000, locallyInUse = { here.isLocallyInUse(it) })
    here = pools(lending = lending)
    val pools = here
    claim(pools, ownPhone)
    assertTrue(pools.isLocallyInUse("android/emulator-5554"))
    assertTrue(lending.lease(lender.attached, LeaseRequest(listOf("android/emulator-5554"), "bob")) is LeaseResult.Busy)
    pools.onReleased(ownPhone)
    assertFalse(pools.isLocallyInUse("android/emulator-5554"))
  }

  @Test
  fun `a lending daemon's own session waits for a borrower using the device, and takes it from an idle one`() {
    val lending = DeviceLeaseRegistry(ttlMs = 60_000)
    val pools = pools(lending = lending)
    val bobs = (lending.lease(lender.attached, LeaseRequest(listOf("android/emulator-5554"), "bob")) as LeaseResult.Granted).lease
    val error = assertFailsWith<IllegalStateException> { pools.beforeClaim(ownPhone) }
    assertEquals(
      "Device android/emulator-5554 is busy.\n  Held by: bob (borrowed from this machine)\n" +
        "Wait for it to finish, or stop the holder before retrying.",
      error.message,
    )
    lending.renew(bobs.leaseId, bobs.secret, busy = false)
    pools.runStarted(ownPhone)
    assertFalse(lending.renew(bobs.leaseId, bobs.secret))
    assertTrue(pools.isLocallyInUse("android/emulator-5554"))
  }

  @Test
  fun `a borrower's call runs on the device it holds here, and is not this machine's use of it`() {
    lateinit var here: RemoteDevicePools
    val lending = DeviceLeaseRegistry(ttlMs = 60_000, locallyInUse = { here.isLocallyInUse(it) })
    here = pools(lending = lending)
    val pools = here
    val bobs = (lending.lease(lender.attached, LeaseRequest(listOf("android/emulator-5554"), "bob")) as LeaseResult.Granted).lease
    runBlocking {
      RemoteDevicePools.asBorrowersCall(ownPhone) {
        pools.runStarted(ownPhone)
        assertFalse(pools.isLocallyInUse("android/emulator-5554"))
        pools.runEnded(ownPhone)
      }
    }
    assertTrue(lending.renew(bobs.leaseId, bobs.secret, busy = true), "bob still holds it")
    assertFailsWith<IllegalStateException> { pools.runStarted(ownPhone) }
  }

  @Test
  fun `a lending daemon's own viewer or recorder shares the device with borrowers by the same rule`() {
    lateinit var here: RemoteDevicePools
    val lending = DeviceLeaseRegistry(ttlMs = 60_000, locallyInUse = { here.isLocallyInUse(it) })
    here = pools(lending = lending)
    val pools = here
    val bobs = (lending.lease(lender.attached, LeaseRequest(listOf("android/emulator-5554"), "bob")) as LeaseResult.Granted).lease
    // Bob is using it: the viewer can't connect, and can't send it a gesture.
    assertTrue(pools.beforeInteractiveUse(ownPhone)!!.startsWith("Device android/emulator-5554 is busy.\n  Held by: bob (borrowed from this machine)"))
    // Bob went idle: the viewer takes it, and borrowers are kept off while it is open.
    lending.renew(bobs.leaseId, bobs.secret, busy = false)
    assertEquals(null, pools.beforeInteractiveUse(ownPhone))
    pools.viewerOpened(ownPhone)
    assertFalse(lending.renew(bobs.leaseId, bobs.secret))
    assertTrue(lending.lease(lender.attached, LeaseRequest(listOf("android/emulator-5554"), "carol")) is LeaseResult.Busy)
    pools.viewerClosed(ownPhone)
    assertFalse(pools.isLocallyInUse("android/emulator-5554"))
  }

  @Test
  fun `a viewer on this machine's own device connects while no borrower is using it`() {
    val pools = pools(lending = DeviceLeaseRegistry(ttlMs = 60_000))
    val result = runBlocking { pools.whileConnecting(ownPhone) { "connected" } }
    assertEquals("connected", result.getOrThrow())
    assertFalse(pools.isLocallyInUse("android/emulator-5554"), "not in use once connecting ends")
  }

  @Test
  fun `devices that are not the lender's are left alone`() {
    val pools = discovered(pools())
    pools.beforeClaim(ownPhone)
    pools.ensureLeased(ownPhone)
    pools.runStarted(ownPhone)
    assertFalse(pools.isRemote(ownPhone))
    assertTrue(lender.leaseCalls.isEmpty())
    assertFailsWith<IllegalStateException> { tap(pools, ownPhone) }
  }

  @Test
  fun `without pools nothing is borrowed`() {
    assertTrue(RemoteDevicePools(poolUrls = { emptyList() }).remoteDevices().isEmpty())
    assertEquals(listOf("http://a:1", "http://b:2"), RemoteDevicePools.parseUrls(" http://a:1/, ,http://b:2"))
  }
}
