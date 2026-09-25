package xyz.block.trailblaze.http

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import java.io.IOException
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * The device→host log channel must not be routed through the process's proxy selection. On
 * Android that selection is the device's global HTTP proxy; a network capture sets one, and a
 * daemon that dies mid-capture leaves it pointing at a port nothing listens on — which then took
 * down the runner's log uploads too.
 */
class TrailblazeHttpClientFactoryProxyTest {

  @Test
  fun `the single-argument entry point this factory used to be still exists`() {
    // The on-device runner is built and pinned separately from the host, so it can be running code
    // compiled against the signature this method had before `bypassSystemProxy` was added. Adding
    // a defaulted parameter rewrites the JVM descriptor from (long) to (long, boolean) and nothing
    // answers the old call but a NoSuchMethodError, so the old descriptor has to stay published.
    val descriptors = TrailblazeHttpClientFactory::class.java.methods
      .filter { it.name == "createInsecureTrustAllCertsHttpClient" }
      .map { method -> method.parameterTypes.map { it.simpleName } }

    assertTrue(
      listOf("long") in descriptors,
      "a caller compiled against the old signature has nothing to call; found $descriptors",
    )
  }

  @Test
  fun `a client that bypasses the system proxy reaches a local server the proxy would have eaten`() {
    withDeadDefaultProxy { url ->
      val body = runBlocking {
        TrailblazeHttpClientFactory
          .createInsecureTrustAllCertsHttpClient(timeoutInSeconds = 2, bypassSystemProxy = true)
          .use { it.get(url).bodyAsText() }
      }
      assertEquals("pong", body)
    }
  }

  @Test
  fun `the same client without the bypass is routed into the dead proxy`() {
    // Proves the fixture bites: if the selector were ignored anyway, the test above would pass for
    // the wrong reason.
    withDeadDefaultProxy { url ->
      assertFailsWith<IOException> {
        runBlocking {
          TrailblazeHttpClientFactory
            .createInsecureTrustAllCertsHttpClient(timeoutInSeconds = 2)
            .use { it.get(url).bodyAsText() }
        }
      }
    }
  }

  /**
   * Points the JVM's proxy selection at a dead proxy the way Android points it at the capture
   * proxy: through the `http.proxy*` system properties, which the default selector reads on every
   * lookup. Swapping the selector instance would not do — Ktor's OkHttp engine captures
   * `ProxySelector.getDefault()` once per JVM into a shared prototype, so a selector installed
   * after the first Ktor client exists is never consulted. The throwaway client below forces that
   * capture first, so this test sees the same ordering alone as it does in a full run.
   */
  private fun withDeadDefaultProxy(block: (url: String) -> Unit) {
    runBlocking { HttpClient(OkHttp).use { } }

    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/ping") { exchange ->
      val bytes = "pong".toByteArray()
      exchange.sendResponseHeaders(200, bytes.size.toLong())
      exchange.responseBody.use { it.write(bytes) }
    }
    server.start()
    val previous = PROXY_PROPERTIES.associateWith { System.getProperty(it) }
    // Port 1 on loopback: reserved, never listening, refuses immediately. The JDK exempts
    // loopback from proxying by default; an empty non-proxy list turns that exemption off.
    System.setProperty("http.proxyHost", "127.0.0.1")
    System.setProperty("http.proxyPort", "1")
    System.setProperty("http.nonProxyHosts", "")
    try {
      block("http://127.0.0.1:${server.address.port}/ping")
    } finally {
      previous.forEach { (key, value) ->
        if (value == null) System.clearProperty(key) else System.setProperty(key, value)
      }
      server.stop(0)
    }
  }

  private companion object {
    val PROXY_PROPERTIES = listOf("http.proxyHost", "http.proxyPort", "http.nonProxyHosts")
  }
}
