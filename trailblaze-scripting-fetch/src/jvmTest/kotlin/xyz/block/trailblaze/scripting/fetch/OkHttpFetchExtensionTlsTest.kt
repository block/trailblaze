package xyz.block.trailblaze.scripting.fetch

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.tls.HeldCertificate
import okhttp3.tls.HandshakeCertificates

/**
 * Pins how `fetch` treats **self-signed certificates**, which is the shape of every Trailblaze HTTPS
 * endpoint a device talks to: the on-device server, and the host daemon reached over `adb reverse`
 * (`localhost`) or the emulator alias (`10.0.2.2`). Those certificates cannot be validated by any
 * trust store, so a `fetch` that validated them strictly would be unusable for exactly the calls
 * this binding exists to serve — while turning validation off globally would silently downgrade a
 * tool's call to a real API.
 *
 * Both halves of that contract are asserted here against one real self-signed HTTPS server: reached
 * as `localhost` it answers 200, and reached as a non-local hostname (mapped onto the same socket
 * via the injected client's DNS, so nothing but the *name* differs) the handshake fails.
 */
class OkHttpFetchExtensionTlsTest {

  private var server: HttpsServer? = null

  @AfterTest fun teardown() = server?.stop(0) ?: Unit

  /**
   * A self-signed HTTPS server on loopback. The certificate names only `localhost` — deliberately
   * not the alias used by the negative case, so that request fails on trust *and* hostname, the way
   * a real Trailblaze self-signed cert would.
   */
  private fun startSelfSignedServer(body: String): Int {
    val certificate =
      HeldCertificate.Builder().addSubjectAlternativeName("localhost").commonName("localhost").build()
    val sslContext = HandshakeCertificates.Builder().heldCertificate(certificate).build().sslContext()
    val s = HttpsServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
    s.httpsConfigurator = HttpsConfigurator(sslContext)
    s.createContext("/probe") { exchange ->
      val bytes = body.toByteArray()
      exchange.sendResponseHeaders(200, bytes.size.toLong())
      exchange.responseBody.use { it.write(bytes) }
    }
    s.start()
    server = s
    return s.address.port
  }

  /** Resolves [ALIAS_HOST] to loopback so the negative case differs from the positive one only in
   * the URL's hostname — no public DNS, no second server. */
  private fun clientResolvingAliasToLoopback(): OkHttpClient =
    OkHttpClient.Builder()
      .dns { hostname ->
        if (hostname == ALIAS_HOST) listOf(InetAddress.getByName("127.0.0.1"))
        else Dns.SYSTEM.lookup(hostname)
      }
      .build()

  @Test
  fun aSelfSignedCertificateIsAcceptedOnADeviceLocalHost() = runBlocking {
    val port = startSelfSignedServer("self-signed-ok")
    val extension = OkHttpFetchExtension(client = clientResolvingAliasToLoopback())

    val response = extension.fetchJson("https://localhost:$port/probe")

    assertEquals(200, response["status"]!!.jsonPrimitive.int)
    assertEquals("self-signed-ok", response["bodyText"]!!.jsonPrimitive.content)
  }

  @Test
  fun theSameSelfSignedCertificateIsRejectedOnANonLocalHost() = runBlocking {
    val port = startSelfSignedServer("self-signed-ok")
    val extension = OkHttpFetchExtension(client = clientResolvingAliasToLoopback())

    val response = extension.fetchJson("https://$ALIAS_HOST:$port/probe")

    // The error envelope the JS shim turns into a TypeError — asserted as "the request did not
    // succeed", not on the JSSE message, which varies by JDK.
    val error = response["__fetchError"]?.jsonPrimitive?.content
    assertTrue(
      error != null && response["status"] == null,
      "expected a transport failure for an unvalidatable cert on a non-local host, got: $response",
    )
  }

  @Test
  fun deviceLocalHostsAreLoopbackInEverySpelling() {
    val extension = OkHttpFetchExtension()
    listOf("localhost", "LOCALHOST", "127.0.0.1", "::1").forEach {
      assertTrue(extension.isDeviceLocalHost(it), "expected '$it' to be device-local")
    }
    // A name that merely *resolves* to loopback is not the local trust domain: classification is by
    // the URL's host, with no DNS lookup, so a public record pointing at 127.0.0.1 keeps validation.
    listOf("example.com", "localhost.example.com", "127.0.0.1.nip.io").forEach {
      assertTrue(!extension.isDeviceLocalHost(it), "expected '$it' NOT to be device-local")
    }
  }

  @Test
  fun theEmulatorHostAliasIsNotDeviceLocalOnTheHostJvm() {
    // `10.0.2.2` names the host machine only when the caller is the emulator. This same extension
    // is installed by the three host launchers, where it's an ordinary routable address — treating
    // it as device-local there would drop cert and hostname verification for a real remote host.
    assertTrue(!OkHttpFetchExtension().isDeviceLocalHost("10.0.2.2"))
    assertTrue(EMULATOR_HOST_ALIASES.isEmpty())
  }

  private suspend fun OkHttpFetchExtension.fetchJson(url: String): JsonObject =
    Json.parseToJsonElement(executeFetch("""{"url":"$url"}""")) as JsonObject

  private companion object {
    /** Any name that isn't device-local; `.test` is reserved by RFC 6761 so it never resolves. */
    const val ALIAS_HOST = "not-device-local.test"
  }
}
