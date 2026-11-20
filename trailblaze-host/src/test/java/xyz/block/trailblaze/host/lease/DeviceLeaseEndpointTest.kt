package xyz.block.trailblaze.host.lease

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Drives the lease routes through a real Ktor test app with a fixed set of this machine's devices. */
class DeviceLeaseEndpointTest {

  private val json = Json { ignoreUnknownKeys = true }
  private val phone = "android/emulator-5554"
  private val simulator = "ios/8F2C-SIM"
  private val devices = listOf(
    LeasableDevice(phone, TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY),
    LeasableDevice(simulator, TrailblazeDriverType.IOS_HOST),
  )

  /** What reached each device, as the lender's driver would see it. */
  private val received: MutableList<Pair<TrailblazeDeviceId, RemoteDeviceCall>> = Collections.synchronizedList(mutableListOf())
  /** Holds each call open until it completes, standing in for a slow tool. */
  @Volatile private var gate: CompletableDeferred<Unit>? = null
  /** Holds each call on the named device open until it completes. */
  @Volatile private var gates: Map<String, CompletableDeferred<Unit>> = emptyMap()
  private val onDevice = AtomicInteger()
  private val overlapped = AtomicBoolean()
  /** Each call as it reaches its device, for a test to wait on. */
  private val arrived = Channel<RemoteDeviceCall>(Channel.UNLIMITED)
  private val calls = LeasedDeviceCalls { device, call ->
    if (onDevice.incrementAndGet() > 1) overlapped.set(true)
    received += device to call
    arrived.trySend(call)
    try {
      gate?.await()
      gates[call.device]?.await()
      when (call.op) {
        RemoteDeviceCall.Op.RUN_TOOL -> RemoteDeviceReply(toolResult = RemoteToolResult(success = true, message = "ran ${call.toolYaml}"))
        RemoteDeviceCall.Op.INSTALLED_APPS -> RemoteDeviceReply(installedAppIds = setOf("com.example"))
        else -> RemoteDeviceReply()
      }
    } finally {
      onDevice.decrementAndGet()
    }
  }

  /** Each device whose upload began receiving, in order. */
  private val uploadsStarted = Channel<String>(Channel.UNLIMITED)

  /** Each app installed, with the bytes the lender received and whether the file was still there after. */
  private val installs: MutableList<Pair<TrailblazeDeviceId, ByteArray>> = Collections.synchronizedList(mutableListOf())
  private val installedFiles: MutableList<java.io.File> = Collections.synchronizedList(mutableListOf())
  private val installer = LeasedDeviceInstaller { device, appFile ->
    installs += device to appFile.readBytes()
    installedFiles += appFile
    RemoteInstallReply(success = true, message = "installed ${appFile.extension} on ${device.instanceId}")
  }

  private fun leaseApp(
    maxUploadBytes: Long = DeviceLeaseEndpoint.MAX_UPLOAD_BYTES,
    block: suspend ApplicationTestBuilder.() -> Unit,
  ) = testApplication {
    application {
      routing {
        DeviceLeaseEndpoint.register(
          routing = this,
          attachedDevices = { devices },
          calls = calls,
          registry = DeviceLeaseRegistry(),
          installer = installer,
          maxUploadBytes = maxUploadBytes,
          uploadStartedForTest = { uploadsStarted.trySend(it) },
        )
      }
    }
    block()
  }

  private suspend fun ApplicationTestBuilder.lease(vararg ids: String, holder: String = "test") =
    client.post(DeviceLeaseEndpoint.LEASES_PATH) {
      setBody("""{"devices":[${ids.joinToString { "\"$it\"" }}],"holder":"$holder"}""")
    }

  private suspend fun ApplicationTestBuilder.granted(vararg ids: String, holder: String = "test"): Lease =
    json.decodeFromString(Lease.serializer(), lease(*ids, holder = holder).bodyAsText())

  private suspend fun ApplicationTestBuilder.call(lease: Lease, device: String, secret: String = lease.secret, op: String = "RUN_TOOL") =
    client.post("${DeviceLeaseEndpoint.LEASES_PATH}/${lease.leaseId}/call") {
      header(DeviceLeaseEndpoint.SECRET_HEADER, secret)
      setBody("""{"device":"$device","op":"$op","toolYaml":"- pressBack: {}"}""")
    }

  private suspend fun ApplicationTestBuilder.install(lease: Lease, device: String, file: String, bytes: ByteArray, secret: String = lease.secret) =
    client.post("${DeviceLeaseEndpoint.LEASES_PATH}/${lease.leaseId}/install?device=$device&file=$file") {
      header(DeviceLeaseEndpoint.SECRET_HEADER, secret)
      setBody(bytes)
    }

  private suspend fun ApplicationTestBuilder.renewIdle(lease: Lease) =
    client.post("${DeviceLeaseEndpoint.LEASES_PATH}/${lease.leaseId}/renew?busy=false") {
      header(DeviceLeaseEndpoint.SECRET_HEADER, lease.secret)
    }

  @Test
  fun `a granted lease names its devices, whatever their platform`() = leaseApp {
    val response = lease(phone, simulator)
    assertEquals(HttpStatusCode.OK, response.status)
    val lease = json.decodeFromString(Lease.serializer(), response.bodyAsText())
    assertEquals(listOf(TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY, TrailblazeDriverType.IOS_HOST), lease.devices.map { it.driverType })
    assertTrue(lease.secret.isNotEmpty())
  }

  @Test
  fun `a call on a held device runs there and returns its result`() = leaseApp {
    val lease = granted(simulator)
    val response = call(lease, simulator)
    assertEquals(HttpStatusCode.OK, response.status)
    val reply = json.decodeFromString(RemoteDeviceReply.serializer(), response.bodyAsText())
    assertEquals(RemoteToolResult(success = true, message = "ran - pressBack: {}"), reply.toolResult)
    assertEquals(TrailblazeDeviceId("8F2C-SIM", TrailblazeDevicePlatform.IOS), received.single().first)
  }

  @Test
  fun `a device takes one call at a time, so returning a lease ends it after the call on it`() = leaseApp {
    val lease = granted(phone)
    val slow = CompletableDeferred<Unit>().also { gate = it }
    val path = "${DeviceLeaseEndpoint.LEASES_PATH}/${lease.leaseId}"
    coroutineScope {
      val first = async { call(lease, phone) }
      while (received.isEmpty()) delay(1)
      val returned = async { client.delete(path) { header(DeviceLeaseEndpoint.SECRET_HEADER, lease.secret) } }
      // The lease is gone at once; ending the device waits for the call still on it.
      while (client.post("$path/renew") { header(DeviceLeaseEndpoint.SECRET_HEADER, lease.secret) }.status != HttpStatusCode.NotFound) delay(1)
      gate = null
      slow.complete(Unit)
      assertEquals(HttpStatusCode.OK, first.await().status)
      assertEquals(HttpStatusCode.NoContent, returned.await().status)
    }
    assertFalse(overlapped.get(), "two calls were on the device at once: $received")
    assertEquals(listOf(RemoteDeviceCall.Op.RUN_TOOL, RemoteDeviceCall.Op.END), received.map { it.second.op })
  }

  @Test
  fun `a call reaches no device without the lease's secret, or on a device the lease does not hold`() = leaseApp {
    val lease = granted(phone)
    assertEquals(HttpStatusCode.NotFound, call(lease, phone, secret = "guess").status)
    assertEquals(HttpStatusCode.NotFound, call(lease, simulator).status)
    assertTrue(received.isEmpty(), "$received")
  }

  @Test
  fun `a device taken from an idle holder no longer takes that holder's calls`() = leaseApp {
    val old = granted(phone)
    assertEquals(HttpStatusCode.NoContent, renewIdle(old).status)
    val taken = granted(phone, holder = "bob")
    assertEquals(HttpStatusCode.NotFound, call(old, phone).status)
    assertEquals(HttpStatusCode.OK, call(taken, phone).status)
    assertEquals(1, received.size)
  }

  @Test
  fun `returning a lease ends each of its devices on this machine`() = leaseApp {
    val lease = granted(phone, simulator)
    val path = "${DeviceLeaseEndpoint.LEASES_PATH}/${lease.leaseId}"
    assertEquals(HttpStatusCode.NoContent, client.delete(path) { header(DeviceLeaseEndpoint.SECRET_HEADER, lease.secret) }.status)
    assertEquals(listOf(phone, simulator), received.map { it.second.device })
    assertTrue(received.all { it.second.op == RemoteDeviceCall.Op.END }, "$received")
    assertEquals(HttpStatusCode.NotFound, call(lease, phone).status)
  }

  @Test
  fun `a returned device stays taken until its own cleanup is done, so it never ends the next user's session`() = leaseApp {
    val lease = granted(phone, simulator)
    val phoneEnded = CompletableDeferred<Unit>()
    val simulatorEnded = CompletableDeferred<Unit>()
    gates = mapOf(phone to phoneEnded, simulator to simulatorEnded)
    coroutineScope {
      val returned = async {
        client.delete("${DeviceLeaseEndpoint.LEASES_PATH}/${lease.leaseId}") { header(DeviceLeaseEndpoint.SECRET_HEADER, lease.secret) }
      }
      assertEquals(RemoteDeviceCall(phone, RemoteDeviceCall.Op.END), arrived.receive())
      // The phone is being ended, and the simulator waits its turn: neither can be lent yet.
      assertEquals(HttpStatusCode.Conflict, lease(phone, holder = "bob").status)
      assertEquals(HttpStatusCode.Conflict, lease(simulator, holder = "bob").status)
      phoneEnded.complete(Unit)
      assertEquals(RemoteDeviceCall(simulator, RemoteDeviceCall.Op.END), arrived.receive())
      // The phone is clean, so it goes back at once; the simulator is still being ended.
      granted(phone, holder = "bob")
      assertEquals(HttpStatusCode.Conflict, lease(simulator, holder = "bob").status)
      simulatorEnded.complete(Unit)
      assertEquals(HttpStatusCode.NoContent, returned.await().status)
    }
    val next = granted(simulator, holder = "bob")
    assertEquals(HttpStatusCode.OK, call(next, simulator).status)
    assertEquals(listOf(RemoteDeviceCall.Op.END, RemoteDeviceCall.Op.END, RemoteDeviceCall.Op.RUN_TOOL), received.map { it.second.op })
  }

  @Test
  fun `asking for a device that is lent out is a conflict with a reason`() = leaseApp {
    lease(phone)
    val response = lease(simulator, phone)
    assertEquals(HttpStatusCode.Conflict, response.status)
    assertTrue(response.bodyAsText().contains("$phone is busy: test is using it"), response.bodyAsText())
  }

  @Test
  fun `a holder that renews as idle gives the device up to the next asker`() = leaseApp {
    assertEquals(HttpStatusCode.NoContent, renewIdle(granted(phone)).status)
    assertEquals(HttpStatusCode.OK, lease(phone).status)
  }

  @Test
  fun `renew and return need the lease's secret`() = leaseApp {
    val lease = granted(phone)
    val path = "${DeviceLeaseEndpoint.LEASES_PATH}/${lease.leaseId}"
    assertEquals(HttpStatusCode.NotFound, client.post("$path/renew") { header(DeviceLeaseEndpoint.SECRET_HEADER, "x") }.status)
    assertEquals(HttpStatusCode.NotFound, client.delete(path) { header(DeviceLeaseEndpoint.SECRET_HEADER, "x") }.status)
    assertEquals(HttpStatusCode.NoContent, client.post("$path/renew") { header(DeviceLeaseEndpoint.SECRET_HEADER, lease.secret) }.status)
    assertEquals(HttpStatusCode.NoContent, client.delete(path) { header(DeviceLeaseEndpoint.SECRET_HEADER, lease.secret) }.status)
    assertEquals(HttpStatusCode.NotFound, client.post("$path/renew") { header(DeviceLeaseEndpoint.SECRET_HEADER, lease.secret) }.status)
  }

  @Test
  fun `the device list shows what is lent out`() = leaseApp {
    lease(phone)
    val rows = json.decodeFromString(
      ListSerializer(LeasableDeviceStatus.serializer()),
      client.get(DeviceLeaseEndpoint.DEVICES_PATH).bodyAsText(),
    )
    assertEquals(listOf(phone to true, simulator to false), rows.map { it.id to it.leased })
    assertEquals("test", rows.first().holder)
  }

  @Test
  fun `a request from a browser is refused and reaches no device`() = leaseApp {
    val lease = granted(phone)
    val fromPage = client.post(DeviceLeaseEndpoint.LEASES_PATH) {
      header(HttpHeaders.Origin, "https://example.com")
      setBody("""{"devices":["$simulator"],"holder":"page"}""")
    }
    assertEquals(HttpStatusCode.Forbidden, fromPage.status)
    assertEquals(HttpStatusCode.Forbidden, client.get(DeviceLeaseEndpoint.DEVICES_PATH) { header(HttpHeaders.Origin, "https://example.com") }.status)
    val callFromPage = client.post("${DeviceLeaseEndpoint.LEASES_PATH}/${lease.leaseId}/call") {
      header(HttpHeaders.Origin, "https://example.com")
      header(DeviceLeaseEndpoint.SECRET_HEADER, lease.secret)
      setBody("""{"device":"$phone","op":"SCREEN_STATE"}""")
    }
    assertEquals(HttpStatusCode.Forbidden, callFromPage.status)
    assertTrue(received.isEmpty(), "$received")
    assertEquals(HttpStatusCode.OK, lease(simulator).status)
  }

  @Test
  fun `a request a proxy forwarded is refused and reaches no device`() = leaseApp {
    val lease = granted(phone)
    assertEquals(
      HttpStatusCode.Forbidden,
      client.get(DeviceLeaseEndpoint.DEVICES_PATH) { header(HttpHeaders.XForwardedFor, "203.0.113.7") }.status,
    )
    val forwarded = client.post("${DeviceLeaseEndpoint.LEASES_PATH}/${lease.leaseId}/call") {
      header(HttpHeaders.Forwarded, "for=203.0.113.7")
      header(DeviceLeaseEndpoint.SECRET_HEADER, lease.secret)
      setBody("""{"device":"$phone","op":"SCREEN_STATE"}""")
    }
    assertEquals(HttpStatusCode.Forbidden, forwarded.status)
    assertTrue(received.isEmpty(), "$received")
  }

  @Test
  fun `a malformed request is rejected`() = leaseApp {
    assertEquals(HttpStatusCode.BadRequest, client.post(DeviceLeaseEndpoint.LEASES_PATH) { setBody("{") }.status)
    assertEquals(HttpStatusCode.BadRequest, lease().status)
  }

  @Test
  fun `a device id is its platform and instance`() {
    assertEquals(TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID), DeviceLeaseEndpoint.deviceId(phone))
    assertEquals(TrailblazeDeviceId("checkout/tab", TrailblazeDevicePlatform.WEB), DeviceLeaseEndpoint.deviceId("web/checkout/tab"))
    assertEquals(null, DeviceLeaseEndpoint.deviceId("emulator-5554"))
    assertEquals(null, DeviceLeaseEndpoint.deviceId("android/"))
  }

  @Test
  fun `leasing is off unless the env var asks for it`() {
    assertFalse(DeviceLeaseEndpoint.isEnabled { null })
    assertFalse(DeviceLeaseEndpoint.isEnabled { "0" })
    assertTrue(DeviceLeaseEndpoint.isEnabled { "1" })
    assertTrue(DeviceLeaseEndpoint.isEnabled { "true" })
  }

  @Test
  fun `the saved lend-devices setting turns lending on without the env var`() {
    try {
      assertFalse(DeviceLeaseEndpoint.decideLending(saved = false) { null })
      assertFalse(DeviceLeaseEndpoint.lending)
      assertTrue(DeviceLeaseEndpoint.decideLending(saved = true) { null })
      assertTrue(DeviceLeaseEndpoint.lending)
      assertTrue(DeviceLeaseEndpoint.decideLending(saved = false) { "1" })
    } finally {
      DeviceLeaseEndpoint.decideLending(saved = false) { null }
    }
  }

  @Test
  fun `an explicit off in the env var wins over the saved setting`() {
    try {
      assertFalse(DeviceLeaseEndpoint.decideLending(saved = true) { "0" })
      assertFalse(DeviceLeaseEndpoint.lending)
      assertFalse(DeviceLeaseEndpoint.decideLending(saved = true) { " False " })
      assertTrue(DeviceLeaseEndpoint.decideLending(saved = true) { "" }, "a blank value leaves the saved setting in charge")
    } finally {
      DeviceLeaseEndpoint.decideLending(saved = false) { null }
    }
  }

  @Test
  fun `an app sent for a held device is installed there from the bytes sent, and not kept`() = leaseApp {
    val lease = granted(phone)
    val apk = ByteArray(200_000) { (it % 251).toByte() }
    val response = install(lease, phone, "app-debug.apk", apk)
    assertEquals(HttpStatusCode.OK, response.status)
    assertEquals(
      RemoteInstallReply(success = true, message = "installed apk on emulator-5554"),
      json.decodeFromString(RemoteInstallReply.serializer(), response.bodyAsText()),
    )
    val (device, received) = installs.single()
    assertEquals(TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID), device)
    assertTrue(apk.contentEquals(received))
    assertFalse(installedFiles.single().exists())
  }

  @Test
  fun `an app for a device the lease does not hold, or without its secret, is not installed`() = leaseApp {
    val lease = granted(phone)
    assertEquals(HttpStatusCode.NotFound, install(lease, simulator, "App.zip", byteArrayOf(1)).status)
    assertEquals(HttpStatusCode.NotFound, install(lease, phone, "app.apk", byteArrayOf(1), secret = "wrong").status)
    assertTrue(installs.isEmpty())
  }

  @Test
  fun `a file no device here can install is refused`() = leaseApp {
    val lease = granted(simulator)
    assertEquals(HttpStatusCode.BadRequest, install(lease, simulator, "App.ipa", byteArrayOf(1)).status)
    assertTrue(installs.isEmpty())
  }

  @Test
  fun `an app file over the size cap is refused before anything is installed`() = leaseApp(maxUploadBytes = 4) {
    val lease = granted(phone)
    assertEquals(HttpStatusCode.PayloadTooLarge, install(lease, phone, "app.apk", ByteArray(5)).status)
    assertTrue(installs.isEmpty())
    assertEquals(HttpStatusCode.OK, install(lease, phone, "app.apk", ByteArray(4)).status)
  }

  @Test
  fun `one borrower's upload never holds up another device's`() = leaseApp {
    val phoneLease = granted(phone)
    val simulatorLease = granted(simulator)
    val stall = CompletableDeferred<Unit>()
    coroutineScope {
      val stalled = async {
        client.post("${DeviceLeaseEndpoint.LEASES_PATH}/${phoneLease.leaseId}/install?device=$phone&file=app.apk") {
          header(DeviceLeaseEndpoint.SECRET_HEADER, phoneLease.secret)
          setBody(
            object : OutgoingContent.WriteChannelContent() {
              override suspend fun writeTo(channel: ByteWriteChannel) {
                channel.writeFully(byteArrayOf(1))
                channel.flush()
                stall.await()
              }
            },
          )
        }
      }
      // Both bounds only guard a broken build, which fails by hanging; a working one answers at once.
      assertEquals(phone, withTimeout(30_000) { uploadsStarted.receive() }, "the stalled upload is receiving first")
      val other = withTimeout(30_000) { install(simulatorLease, simulator, "App.zip", byteArrayOf(2)) }
      assertEquals(HttpStatusCode.OK, other.status)
      stall.complete(Unit)
      assertEquals(HttpStatusCode.OK, stalled.await().status)
    }
  }

  @Test
  fun `an upload is cut off once it passes the cap, whatever length it declared`() = runBlocking {
    val kept = java.io.ByteArrayOutputStream()
    assertFalse(DeviceLeaseEndpoint.copyAtMost(ByteReadChannel(ByteArray(10)), kept, max = 4))
    assertTrue(kept.size() <= 4)
    val whole = java.io.ByteArrayOutputStream()
    assertTrue(DeviceLeaseEndpoint.copyAtMost(ByteReadChannel(ByteArray(4)), whole, max = 4))
    assertEquals(4, whole.size())
  }

  @Test
  fun `leasing answers only this machine's loopback`() {
    // A test app always reports loopback, so the check itself is tested here.
    listOf("127.0.0.1", "127.0.0.5", "::1", "0:0:0:0:0:0:0:1", "localhost", "::ffff:127.0.0.1").forEach {
      assertTrue(DeviceLeaseEndpoint.isLoopback(it), it)
    }
    listOf("10.0.0.7", "192.168.1.20", "fe80::1", "::ffff:10.0.0.7", "build-box").forEach {
      assertFalse(DeviceLeaseEndpoint.isLoopback(it), it)
    }
  }
}
