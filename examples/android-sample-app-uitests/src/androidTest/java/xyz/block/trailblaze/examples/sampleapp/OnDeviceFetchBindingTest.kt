package xyz.block.trailblaze.examples.sampleapp

import java.net.InetAddress
import java.net.ServerSocket
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.block.trailblaze.quickjs.tools.QuickJsToolHost
import xyz.block.trailblaze.scripting.fetch.OkHttpFetchExtension

/**
 * Proves `globalThis.fetch` works for a scripted tool **on a real device** — the claim the
 * on-device launchers (`AndroidTrailblazeRule`, `OnDeviceScriptedToolBundleLauncher`) make by
 * installing [OkHttpFetchExtension]. The JVM tests in `:trailblaze-scripting-fetch` cover fetch's
 * HTTP behavior; what only a device can answer is whether the binding installs and OkHttp actually
 * runs inside QuickJS on ART.
 *
 * The round trip runs over **https against a self-signed certificate** — the only shape that
 * matters on a device. Trailblaze's own HTTPS surfaces (the on-device server; the host daemon over
 * `adb reverse` or the `10.0.2.2` emulator alias) all present self-signed certs, and a pure
 * on-device farm run has no `adb reverse` to fall back on, so this is the exact path a real tool
 * takes. Plain http is deliberately not used anywhere: it would need a cleartext opt-in in this
 * APK's network-security config, and there's no reason for a tool to speak it.
 *
 * `com.sun.net.httpserver` (which the JVM tests use) doesn't exist on Android, so the test stands
 * up a [LoopbackHttpsServer] — a small single-request responder over `SSLServerSocket` — rather
 * than pulling in a mock-server dependency for one assertion.
 *
 * Ships in `android-sample-app-uitests-debug-androidTest.apk` and runs on the cloud device farm, so
 * a regression in the binding under ART (R8 stripping, a missing class, a QuickJS-thread issue)
 * fails here and blocks merge.
 */
class OnDeviceFetchBindingTest {

  private val hosts = mutableListOf<QuickJsToolHost>()

  @After
  fun teardown() = runBlocking {
    hosts.forEach { runCatching { it.shutdown() } }
    hosts.clear()
  }

  private suspend fun connect(extension: OkHttpFetchExtension?): QuickJsToolHost =
    QuickJsToolHost.connect(
        bundleJs = FETCH_PROBE_BUNDLE,
        bundleFilename = "on-device-fetch-probe.js",
        engineExtension = extension,
      )
      .also { hosts.add(it) }

  @Test
  fun theExtensionBindsFetchOnDeviceAndItIsAbsentWithoutIt() = runBlocking {
    assertEquals("function", typeofFetch(connect(OkHttpFetchExtension())))
    // The binding comes from the extension, not the engine — asserts the observable contract
    // (is the global bound?) rather than an engine error message.
    assertEquals("undefined", typeofFetch(connect(extension = null)))
  }

  @Test
  fun fetchCompletesASelfSignedHttpsRoundTripOnDevice() = runBlocking {
    // Asserts the POSITIVE contract — a 200 and the response body reaching the JS handler. An
    // earlier version probed a CLOSED port and asserted only that the error wasn't "not a
    // function", which passed for any failure, including ones raised before OkHttp ever opened a
    // socket (a malformed-request or invalid-URL envelope) — so it did not actually pin that the
    // request reached the network.
    //
    // Nothing about the certificate is installed on the device or configured on the extension: the
    // binding accepts it because the host is device-local. That is what makes a purely on-device
    // run (device farm, no `adb reverse`, no host to proxy through) work, and it's why this test
    // stands up a self-signed server rather than reusing a public https endpoint.
    LoopbackHttpsServer(body = "on-device-ok").use { server ->
      val host = connect(OkHttpFetchExtension())

      val result =
        host.callTool("fetchProbe", buildJsonObject { put("url", "${server.baseUrl}/probe") })
      assertEquals("status:200 body:on-device-ok", result.firstTextContent())
    }
  }

  /**
   * Minimal single-request HTTPS responder on loopback, holding a **self-signed** certificate.
   * `com.sun.net.httpserver` (used by the JVM tests in `:trailblaze-scripting-fetch`) doesn't exist
   * on Android, and a mock-server dependency isn't worth pulling in for one assertion — this reads
   * the request head, replies 200, and closes. Deliberately not a general-purpose server.
   */
  private class LoopbackHttpsServer(private val body: String) : AutoCloseable {
    private val socket: ServerSocket =
      HandshakeCertificates.Builder()
        .heldCertificate(
          HeldCertificate.Builder()
            .addSubjectAlternativeName("127.0.0.1")
            .commonName("localhost")
            .build()
        )
        .build()
        .sslContext()
        .serverSocketFactory
        .createServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))

    val baseUrl: String
      get() = "https://127.0.0.1:${socket.localPort}"

    private val thread =
      Thread {
          runCatching {
            socket.accept().use { client ->
              // Drain the request head so the client isn't writing into a closed pipe.
              val reader = client.getInputStream().bufferedReader()
              while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
              }
              val bytes = body.toByteArray()
              client.getOutputStream().apply {
                write(
                  ("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\n" +
                      "Content-Type: text/plain\r\nConnection: close\r\n\r\n")
                    .toByteArray()
                )
                write(bytes)
                flush()
              }
            }
          }
        }
        .apply {
          isDaemon = true
          start()
        }

    override fun close() {
      runCatching { socket.close() }
      runCatching { thread.interrupt() }
    }
  }

  private suspend fun typeofFetch(host: QuickJsToolHost): String =
    host.callTool("fetchTypeof", JsonObject(emptyMap())).firstTextContent()

  private fun JsonObject.firstTextContent(): String {
    val content = this["content"] as JsonArray
    return (content.first().jsonObject["text"] as JsonPrimitive).content
  }

  companion object {
    private val FETCH_PROBE_BUNDLE =
      """
      const tools = (globalThis.__trailblazeTools = globalThis.__trailblazeTools || {});
      tools["fetchTypeof"] = {
        name: "fetchTypeof",
        spec: {},
        handler: async () => ({ content: [{ type: "text", text: typeof globalThis.fetch }] }),
      };
      tools["fetchProbe"] = {
        name: "fetchProbe",
        spec: {},
        handler: async (args) => {
          try {
            const res = await fetch(args.url);
            const body = await res.text();
            return { content: [{ type: "text", text: "status:" + res.status + " body:" + body }] };
          } catch (e) {
            return { content: [{ type: "text", text: String((e && e.message) || e) }] };
          }
        },
      };
      """
        .trimIndent()
  }
}
