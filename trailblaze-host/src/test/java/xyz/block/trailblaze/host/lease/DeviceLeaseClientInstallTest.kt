package xyz.block.trailblaze.host.lease

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What a borrower makes of each answer a lender can give to an app install. */
class DeviceLeaseClientInstallTest {

  private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
  private val lease = Lease(leaseId = "lease-1", secret = "s", ttlMs = 30_000, devices = emptyList())
  private val apk: File = Files.createTempFile("app", ".apk").toFile().apply { writeText("apk bytes") }

  @AfterTest
  fun tearDown() {
    server.stop(0)
    apk.delete()
  }

  /** A lender that answers every install with [status] and [body]. */
  private fun lender(status: Int, body: String): DeviceLeaseClient {
    server.createContext("/") { exchange ->
      exchange.requestBody.readBytes()
      val bytes = body.toByteArray()
      exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
      if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) } else exchange.close()
    }
    server.start()
    return DeviceLeaseClient("http://127.0.0.1:${server.address.port}")
  }

  @Test
  fun `the lender's no-such-lease answer is a lost lease`() {
    val client = lender(404, """{"error":"no such lease"}""")
    assertFailsWith<LeaseLostException> { client.install(lease, "android/emulator-5554", apk) }
  }

  @Test
  fun `a lender too old to install apps leaves the lease alone`() {
    val client = lender(404, "")
    val failure = assertFailsWith<IOException> { client.install(lease, "android/emulator-5554", apk) }
    assertFalse(failure is LeaseLostException, "the device is still lent: ${failure.message}")
    assertTrue(failure.message!!.contains("update Trailblaze"), failure.message)
  }

  @Test
  fun `a reply the borrower can't read fails as an error, not a crash`() {
    val client = lender(200, "<html>proxy error</html>")
    val failure = assertFailsWith<IOException> { client.install(lease, "android/emulator-5554", apk) }
    assertTrue(failure.message!!.contains("proxy error"), failure.message)
  }

  @Test
  fun `a lender's install reply comes back as sent`() {
    val client = lender(200, """{"success":true,"message":"installed"}""")
    assertEquals(RemoteInstallReply(success = true, message = "installed"), client.install(lease, "android/emulator-5554", apk))
  }
}
