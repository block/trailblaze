package xyz.block.trailblaze.scripting.fetch

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import xyz.block.trailblaze.quickjs.tools.QuickJsToolHost

/**
 * End-to-end tests: a scripted tool calls `fetch(…)` inside a real [QuickJsToolHost] whose engine
 * has [OkHttpFetchExtension] installed, against a real loopback HTTP server (the JDK's
 * `com.sun.net.httpserver.HttpServer` — a genuine server, like MockWebServer, not a hand-rolled
 * HTTP mock). We assert the OBSERVABLE contract: what the tool's handler sees back from `fetch`,
 * and what request the server actually received — never internal call counts or wire-marshaling
 * details.
 */
class OkHttpFetchExtensionTest {

  private val hosts = mutableListOf<QuickJsToolHost>()
  private var server: HttpServer? = null

  @AfterTest
  fun teardown() = runBlocking {
    hosts.forEach { runCatching { it.shutdown() } }
    hosts.clear()
    server?.stop(0)
    server = null
  }

  private suspend fun connect(extension: OkHttpFetchExtension): QuickJsToolHost {
    val host = QuickJsToolHost.connect(FETCH_PROBE_BUNDLE, engineExtension = extension)
    hosts.add(host)
    return host
  }

  /** Start a loopback server with the given handler; returns its base URL (`http://127.0.0.1:<port>`). */
  private fun startServer(handler: (HttpExchange) -> Unit): String {
    val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    s.createContext("/") { exchange -> exchange.use { handler(it) } }
    s.executor = null
    s.start()
    server = s
    return "http://127.0.0.1:${s.address.port}"
  }

  @Test
  fun `fetch GET surfaces status, ok, headers and body to the tool handler`() = runBlocking {
    val method = AtomicReference<String>()
    val baseUrl = startServer { exchange ->
      method.set(exchange.requestMethod)
      val body = """{"hello":"world"}""".toByteArray()
      exchange.responseHeaders.add("Content-Type", "application/json")
      exchange.sendResponseHeaders(200, body.size.toLong())
      exchange.responseBody.write(body)
    }
    val host = connect(OkHttpFetchExtension())

    val result = host.callTool("fetchProbe", buildJsonObject { put("url", "$baseUrl/data") })
    val probe = Json.parseToJsonElement(textContent(result)).jsonObject

    assertEquals(200, probe["status"]!!.jsonPrimitive.int)
    assertEquals(true, probe["ok"]!!.jsonPrimitive.boolean)
    assertEquals("application/json", probe["contentType"]!!.jsonPrimitive.content)
    assertTrue(
      probe["body"]!!.jsonPrimitive.content.contains("world"),
      "expected the response body text to reach the handler",
    )
    // `res.json()` round-trips too.
    assertEquals("world", probe["jsonHello"]!!.jsonPrimitive.content)
    assertEquals("GET", method.get(), "the server saw the GET the handler issued")
  }

  @Test
  fun `fetch POST sends the method, headers and body the handler supplied`() = runBlocking {
    val seenMethod = AtomicReference<String>()
    val seenContentType = AtomicReference<String>()
    val seenBody = AtomicReference<String>()
    val baseUrl = startServer { exchange ->
      seenMethod.set(exchange.requestMethod)
      seenContentType.set(exchange.requestHeaders.getFirst("Content-Type"))
      seenBody.set(exchange.requestBody.readBytes().decodeToString())
      val body = "ok".toByteArray()
      exchange.sendResponseHeaders(201, body.size.toLong())
      exchange.responseBody.write(body)
    }
    val host = connect(OkHttpFetchExtension())

    val result = host.callTool(
      "fetchProbe",
      buildJsonObject {
        put("url", "$baseUrl/command")
        put(
          "init",
          buildJsonObject {
            put("method", "POST")
            put("headers", buildJsonObject { put("content-type", "application/json") })
            put("body", """{"flag":true}""")
          },
        )
      },
    )
    val probe = Json.parseToJsonElement(textContent(result)).jsonObject

    assertEquals(201, probe["status"]!!.jsonPrimitive.int)
    assertEquals("POST", seenMethod.get())
    assertEquals("application/json", seenContentType.get())
    assertEquals("""{"flag":true}""", seenBody.get())
  }

  @Test
  fun `an opt-in restrictive allow-list blocks a non-permitted host before any request`() = runBlocking {
    // Default is unrestricted; here we OPT IN to localhost-only. Pointing at an external host must
    // then fail in the binding (the tool's `try/catch` sees a thrown error) WITHOUT a socket being
    // opened — so this never touches the network even though example.com is a real host.
    val host = connect(OkHttpFetchExtension(allowlist = FetchHostAllowlist.localhostOnly()))

    val result = host.callTool("fetchProbe", buildJsonObject { put("url", "http://example.com/x") })
    val probe = Json.parseToJsonElement(textContent(result)).jsonObject

    val error = probe["error"]?.jsonPrimitive?.content
    assertTrue(error != null, "expected fetch to throw for a denied host; got: $probe")
    assertTrue(
      error.contains("allow-list") && error.contains("example.com"),
      "expected the denial to name the host and the allow-list; got: $error",
    )
  }

  @Test
  fun `fetch can reach an explicitly allow-listed external-shaped host`() = runBlocking {
    // The server is on 127.0.0.1, but we register its host name explicitly with localhost EXCLUDED
    // to prove allowHosts(...) is what's granting access (not the loopback rule). We reach it via
    // the same 127.0.0.1 URL — registering "127.0.0.1" as an allowed host.
    val baseUrl = startServer { exchange ->
      val body = "allowed".toByteArray()
      exchange.sendResponseHeaders(200, body.size.toLong())
      exchange.responseBody.write(body)
    }
    val host = connect(
      OkHttpFetchExtension(allowlist = FetchHostAllowlist.allowHosts(setOf("127.0.0.1"), includeLocalhost = false)),
    )

    val result = host.callTool("fetchProbe", buildJsonObject { put("url", "$baseUrl/x") })
    val probe = Json.parseToJsonElement(textContent(result)).jsonObject
    assertEquals(200, probe["status"]!!.jsonPrimitive.int)
    assertEquals("allowed", probe["body"]!!.jsonPrimitive.content)
  }

  @Test
  fun `redirects are followed by default (unrestricted)`() = runBlocking {
    // Default is unrestricted -> standard WHATWG behavior: the 302 is followed to the final 200.
    val baseUrl = startServer { exchange ->
      if (exchange.requestURI.path.endsWith("/landed")) {
        val body = "landed".toByteArray()
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.write(body)
      } else {
        exchange.responseHeaders.add("Location", "/landed") // relative; OkHttp resolves it
        exchange.sendResponseHeaders(302, -1)
      }
    }
    val host = connect(OkHttpFetchExtension())

    val result = host.callTool("fetchProbe", buildJsonObject { put("url", "$baseUrl/start") })
    val probe = Json.parseToJsonElement(textContent(result)).jsonObject

    assertEquals(200, probe["status"]!!.jsonPrimitive.int, "the 302 should have been followed to /landed")
    assertEquals("landed", probe["body"]!!.jsonPrimitive.content)
  }

  @Test
  fun `a restrictive allow-list disables redirect-following so it cannot be bypassed`() = runBlocking {
    // With an opt-in restrictive allow-list, redirects are NOT followed — otherwise a permitted
    // (localhost) endpoint could 30x to an off-host URL the allow-list never re-checks. The tool
    // gets the raw 302 back (ok=false) and must follow it itself. We point the Location at an
    // external host; because the redirect is NOT followed, that host is never touched.
    val baseUrl = startServer { exchange ->
      exchange.responseHeaders.add("Location", "http://example.com/evil")
      exchange.sendResponseHeaders(302, -1)
    }
    val host = connect(OkHttpFetchExtension(allowlist = FetchHostAllowlist.localhostOnly()))

    val result = host.callTool("fetchProbe", buildJsonObject { put("url", "$baseUrl/redirect") })
    val probe = Json.parseToJsonElement(textContent(result)).jsonObject

    assertEquals(302, probe["status"]!!.jsonPrimitive.int, "the 302 must come back as-is, not be followed")
    assertEquals(false, probe["ok"]!!.jsonPrimitive.boolean)
  }

  @Test
  fun `a non-2xx response resolves with ok=false and the body, not a thrown error`() = runBlocking {
    // WHATWG contract: only network failures reject; a 4xx/5xx resolves with ok=false.
    val baseUrl = startServer { exchange ->
      val body = "nope".toByteArray()
      exchange.sendResponseHeaders(404, body.size.toLong())
      exchange.responseBody.write(body)
    }
    val host = connect(OkHttpFetchExtension())

    val result = host.callTool("fetchProbe", buildJsonObject { put("url", "$baseUrl/missing") })
    val probe = Json.parseToJsonElement(textContent(result)).jsonObject

    assertEquals(404, probe["status"]!!.jsonPrimitive.int)
    assertEquals(false, probe["ok"]!!.jsonPrimitive.boolean)
    assertEquals("nope", probe["body"]!!.jsonPrimitive.content)
  }

  @Test
  fun `a POST with no body sends an empty body the server accepts`() = runBlocking {
    val seenBody = AtomicReference<String>()
    val baseUrl = startServer { exchange ->
      seenBody.set(exchange.requestBody.readBytes().decodeToString())
      exchange.sendResponseHeaders(200, -1)
    }
    val host = connect(OkHttpFetchExtension())

    // POST requires a body in OkHttp's builder; the binding synthesizes an empty one.
    host.callTool(
      "fetchProbe",
      buildJsonObject {
        put("url", "$baseUrl/command")
        put("init", buildJsonObject { put("method", "POST") })
      },
    )
    assertEquals("", seenBody.get(), "a body-less POST reaches the server with an empty body")
  }

  @Test
  fun `a GET with a body drops the body - GET cannot carry one`() = runBlocking {
    val seenBody = AtomicReference<String>()
    val baseUrl = startServer { exchange ->
      seenBody.set(exchange.requestBody.readBytes().decodeToString())
      exchange.sendResponseHeaders(200, -1)
    }
    val host = connect(OkHttpFetchExtension())

    host.callTool(
      "fetchProbe",
      buildJsonObject {
        put("url", "$baseUrl/data")
        put("init", buildJsonObject { put("method", "GET"); put("body", "should-be-dropped") })
      },
    )
    assertEquals("", seenBody.get(), "a body supplied on a GET is dropped, not sent")
  }

  @Test
  fun `fetch to an invalid URL surfaces a clear error to the handler`() = runBlocking {
    val host = connect(OkHttpFetchExtension())
    val result = host.callTool("fetchProbe", buildJsonObject { put("url", "ht!tp://[not-a-url") })
    val probe = Json.parseToJsonElement(textContent(result)).jsonObject
    val error = probe["error"]?.jsonPrimitive?.content
    assertTrue(error != null && error.contains("URL"), "expected an invalid-URL error; got: $probe")
  }

  @Test
  fun `response json() rejects with a clear error on a non-JSON body`() = runBlocking {
    val baseUrl = startServer { exchange ->
      val body = "not json at all".toByteArray()
      exchange.sendResponseHeaders(200, body.size.toLong())
      exchange.responseBody.write(body)
    }
    val host = connect(OkHttpFetchExtension())

    val result = host.callTool(
      "fetchResponseMethod",
      buildJsonObject { put("url", "$baseUrl/x"); put("call", "json") },
    )
    val probe = Json.parseToJsonElement(textContent(result)).jsonObject
    val error = probe["error"]?.jsonPrimitive?.content
    assertTrue(error != null && error.contains("JSON"), "expected a JSON-parse rejection; got: $probe")
  }

  @Test
  fun `response json() parses a JSON body via the real Response json() method`() = runBlocking {
    // Exercises Response.json() end-to-end (the observable contract), not JSON.parse in the probe.
    val baseUrl = startServer { exchange ->
      val body = """{"flag":true,"name":"bridge"}""".toByteArray()
      exchange.responseHeaders.add("Content-Type", "application/json")
      exchange.sendResponseHeaders(200, body.size.toLong())
      exchange.responseBody.write(body)
    }
    val host = connect(OkHttpFetchExtension())

    val result = host.callTool(
      "fetchResponseMethod",
      buildJsonObject { put("url", "$baseUrl/x"); put("call", "json") },
    )
    val probe = Json.parseToJsonElement(textContent(result)).jsonObject
    assertEquals(true, probe["ok"]?.jsonPrimitive?.boolean, "expected json() to resolve; got: $probe")
    val value = probe["value"]!!.jsonObject
    assertEquals("bridge", value["name"]!!.jsonPrimitive.content)
    assertEquals(true, value["flag"]!!.jsonPrimitive.boolean)
  }

  @Test
  fun `response headers are iterable with for-of`() = runBlocking {
    // Pins Headers[Symbol.iterator] — the type declares it, so the runtime shim must support it.
    val baseUrl = startServer { exchange ->
      exchange.responseHeaders.add("X-Custom", "abc")
      exchange.sendResponseHeaders(200, -1)
    }
    val host = connect(OkHttpFetchExtension())

    val result = host.callTool(
      "fetchResponseMethod",
      buildJsonObject { put("url", "$baseUrl/x"); put("call", "headers") },
    )
    val probe = Json.parseToJsonElement(textContent(result)).jsonObject
    assertEquals(true, probe["ok"]?.jsonPrimitive?.boolean, "iterating headers should not throw; got: $probe")
    assertTrue(
      probe["value"]!!.jsonPrimitive.content.contains("x-custom=abc"),
      "expected the iterated header pair; got: ${probe["value"]}",
    )
  }

  @Test
  fun `response arrayBuffer() throws a clear not-supported error`() = runBlocking {
    val baseUrl = startServer { exchange ->
      val body = "x".toByteArray()
      exchange.sendResponseHeaders(200, body.size.toLong())
      exchange.responseBody.write(body)
    }
    val host = connect(OkHttpFetchExtension())

    val result = host.callTool(
      "fetchResponseMethod",
      buildJsonObject { put("url", "$baseUrl/x"); put("call", "arrayBuffer") },
    )
    val probe = Json.parseToJsonElement(textContent(result)).jsonObject
    val error = probe["error"]?.jsonPrimitive?.content
    assertTrue(
      error != null && error.contains("arrayBuffer") && error.contains("subprocess"),
      "expected a not-supported error pointing at subprocess; got: $probe",
    )
  }

  @Test
  fun `executeFetch returns an error envelope for malformed request JSON`() = runBlocking {
    // Direct unit of the decode-error path — no engine needed (executeFetch is the testable core).
    val result = OkHttpFetchExtension().executeFetch("{ this is not json")
    assertTrue(result.contains("__fetchError"), "expected an error envelope; got: $result")
    assertTrue(result.contains("malformed"), "expected the 'malformed' reason; got: $result")
  }

  @Test
  fun `isFetchDisabled parses the kill-switch env value`() {
    assertTrue(OkHttpFetchExtension.isFetchDisabled("1"))
    assertTrue(OkHttpFetchExtension.isFetchDisabled("true"))
    assertTrue(OkHttpFetchExtension.isFetchDisabled(" TRUE "))
    assertFalse(OkHttpFetchExtension.isFetchDisabled(null))
    assertFalse(OkHttpFetchExtension.isFetchDisabled("0"))
    assertFalse(OkHttpFetchExtension.isFetchDisabled("yes"))
  }

  private fun textContent(result: JsonObject): String =
    ((result["content"] as JsonArray).first().jsonObject["text"] as JsonPrimitive).content

  companion object {
    /**
     * A self-contained bundle (same shape `QuickJsToolHostTest` uses) registering one tool that
     * issues a `fetch` and reports the observable bits of the `Response` back as JSON — or the
     * thrown error message when `fetch` rejects. Lets a test assert against the handler's view of
     * `fetch`, which is the contract authors actually depend on.
     */
    private val FETCH_PROBE_BUNDLE =
      """
      const tools = (globalThis.__trailblazeTools = globalThis.__trailblazeTools || {});
      tools["fetchProbe"] = {
        name: "fetchProbe",
        spec: {},
        handler: async (args) => {
          try {
            const res = await fetch(args.url, args.init || undefined);
            const bodyText = await res.text();
            let jsonHello = null;
            try { jsonHello = JSON.parse(bodyText).hello; } catch (e) {}
            return { content: [{ type: "text", text: JSON.stringify({
              status: res.status,
              ok: res.ok,
              contentType: res.headers.get("content-type"),
              body: bodyText,
              jsonHello: jsonHello,
            }) }] };
          } catch (e) {
            return { content: [{ type: "text", text: JSON.stringify({
              error: String((e && e.message) || e),
            }) }] };
          }
        },
      };
      tools["fetchResponseMethod"] = {
        name: "fetchResponseMethod",
        spec: {},
        handler: async (args) => {
          try {
            const res = await fetch(args.url);
            let value = null;
            if (args.call === "arrayBuffer") { await res.arrayBuffer(); }
            else if (args.call === "json") { value = await res.json(); }
            else if (args.call === "headers") {
              // Exercises Headers[Symbol.iterator] (for-of over the response headers).
              const collected = [];
              for (const pair of res.headers) { collected.push(pair[0] + "=" + pair[1]); }
              value = collected.join(",");
            }
            return { content: [{ type: "text", text: JSON.stringify({ ok: true, value: value }) }] };
          } catch (e) {
            return { content: [{ type: "text", text: JSON.stringify({
              error: String((e && e.message) || e),
            }) }] };
          }
        },
      };
      """.trimIndent()
  }
}
