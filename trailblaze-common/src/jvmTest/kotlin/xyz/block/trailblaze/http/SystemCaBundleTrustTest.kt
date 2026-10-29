package xyz.block.trailblaze.http

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import java.io.File
import java.net.InetSocketAddress
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.runBlocking
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A host whose certificate comes from a CA only the OS bundle trusts (a corporate or proxy CA) must
 * be reachable through the default client, as it is with curl.
 */
class SystemCaBundleTrustTest {

  @get:Rule val tmp = TemporaryFolder()

  private val privateCa = HeldCertificate.Builder().certificateAuthority(0).commonName("Private CA").build()

  @Test
  fun `the default client reaches a host signed by a CA from the OS bundle`() {
    val bundle = bundleOf(privateCa)
    withHttpsServerSignedBy(privateCa) { url ->
      val body = runBlocking {
        TrailblazeHttpClientFactory
          .createDefaultHttpClient(2, SystemCaBundleTrust.trustManagerWithCaBundle(bundle))
          .use { it.get(url).bodyAsText() }
      }
      assertEquals("pong", body)
    }
  }

  @Test
  fun `without the bundle the same host fails certificate validation`() {
    // Proves the fixture bites: the JVM defaults alone do not trust the private CA.
    withHttpsServerSignedBy(privateCa) { url ->
      assertFailsWith<SSLHandshakeException> {
        runBlocking { TrailblazeHttpClientFactory.createDefaultHttpClient(2, null).use { it.get(url) } }
      }
    }
  }

  @Test
  fun `the public entry point picks the bundle up from SSL_CERT_FILE`() {
    // A child JVM, because the bundle is read from the environment once per process. The control
    // run with no SSL_CERT_FILE shows the variable is what made the difference.
    val bundle = bundleOf(privateCa)
    withHttpsServerSignedBy(privateCa) { url ->
      assertEquals("pong", runDefaultClientProbe(url, sslCertFile = bundle))
      assertEquals("SSLHandshakeException", runDefaultClientProbe(url, sslCertFile = null))
    }
  }

  @Test
  fun `the merged trust keeps every JVM default CA`() {
    val merged = assertNotNull(SystemCaBundleTrust.trustManagerWithCaBundle(bundleOf(privateCa)))
      .acceptedIssuers.toSet()
    val jvmDefaults = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
      .apply { init(null as KeyStore?) }
      .trustManagers.filterIsInstance<X509TrustManager>().first()
      .acceptedIssuers.toSet()

    assertTrue(jvmDefaults.isNotEmpty())
    assertTrue(merged.containsAll(jvmDefaults), "a JVM default CA was dropped")
    assertTrue(privateCa.certificate in merged)
  }

  @Test
  fun `a bundle with no certificates leaves the platform trust alone`() {
    val junk = tmp.newFile("junk.pem").apply {
      writeText("-----BEGIN CERTIFICATE-----\nnot base64 der\n-----END CERTIFICATE-----\n")
    }
    assertNull(SystemCaBundleTrust.trustManagerWithCaBundle(junk))
    assertNull(SystemCaBundleTrust.trustManagerWithCaBundle(File(tmp.root, "missing.pem")))
    assertNull(SystemCaBundleTrust.trustManagerWithCaBundle(null))
  }

  @Test
  fun `undecodable blocks are skipped rather than discarding the bundle`() {
    val bundle = tmp.newFile("mixed.pem").apply {
      writeText("-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n" + privateCa.certificatePem())
    }
    val trust = assertNotNull(SystemCaBundleTrust.trustManagerWithCaBundle(bundle))
    assertTrue(privateCa.certificate in trust.acceptedIssuers)
  }

  @Test
  fun `the OS bundle is SSL_CERT_FILE when it names a file, else the system fallback`() {
    val named = tmp.newFile("named.pem")
    val fallback = tmp.newFile("fallback.pem")

    assertEquals(named, SystemCaBundleTrust.osCaBundle(named.path, fallback))
    assertEquals(fallback, SystemCaBundleTrust.osCaBundle(File(tmp.root, "missing.pem").path, fallback))
    assertEquals(fallback, SystemCaBundleTrust.osCaBundle(" ", fallback))
    assertNull(SystemCaBundleTrust.osCaBundle(null, File(tmp.root, "absent.pem")))
  }

  /** What [DefaultClientProbe] printed after calling [url] with [sslCertFile] as the bundle. */
  private fun runDefaultClientProbe(url: String, sslCertFile: File?): String {
    val java = File(System.getProperty("java.home"), "bin/java").path
    // Output goes to a file, not a pipe, so the timeout holds even if the probe hangs holding stdout.
    val output = tmp.newFile()
    val process = ProcessBuilder(
      java, "-cp", System.getProperty("java.class.path"), DefaultClientProbe::class.java.name, url,
    ).redirectOutput(output).redirectError(ProcessBuilder.Redirect.DISCARD).apply {
      environment().remove("SSL_CERT_FILE")
      if (sslCertFile != null) environment()["SSL_CERT_FILE"] = sslCertFile.path
    }.start()
    if (!process.waitFor(60, TimeUnit.SECONDS)) {
      process.destroyForcibly()
      fail("probe JVM did not exit within 60s")
    }
    return output.readText().trim().lines().last()
  }

  private fun bundleOf(ca: HeldCertificate): File =
    tmp.newFile("bundle.pem").apply { writeText(ca.certificatePem()) }

  private fun withHttpsServerSignedBy(ca: HeldCertificate, block: (String) -> Unit) {
    val serverCert = HeldCertificate.Builder()
      .signedBy(ca)
      .addSubjectAlternativeName("127.0.0.1")
      .build()
    val handshake = HandshakeCertificates.Builder().heldCertificate(serverCert, ca.certificate).build()
    val server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
      httpsConfigurator = HttpsConfigurator(handshake.sslContext())
      createContext("/") { exchange ->
        val body = "pong".toByteArray()
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
      }
      start()
    }
    try {
      block("https://127.0.0.1:${server.address.port}/")
    } finally {
      server.stop(0)
    }
  }
}

/**
 * Calls its one argument through the public [TrailblazeHttpClientFactory.createDefaultHttpClient]
 * and prints the body, or the simple name of the exception that stopped it.
 */
internal object DefaultClientProbe {
  @JvmStatic
  fun main(args: Array<String>) {
    val result = runCatching {
      runBlocking { TrailblazeHttpClientFactory.createDefaultHttpClient(5).use { it.get(args[0]).bodyAsText() } }
    }
    println(result.getOrElse { it::class.java.simpleName })
    exitProcess(0)
  }
}
