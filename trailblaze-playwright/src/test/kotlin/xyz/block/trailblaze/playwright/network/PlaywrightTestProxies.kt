package xyz.block.trailblaze.playwright.network

import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Request
import com.microsoft.playwright.Response
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.function.Consumer

/**
 * Java-reflect-Proxy fakes for the slice of Playwright we need in unit tests
 * without launching a real browser. Each fake handles only the methods touched
 * by [WebNetworkCapture] and friends; anything else returns a sentinel error so
 * a future test relying on an unstubbed method gets a clear failure rather
 * than silent null behavior.
 */
internal object PlaywrightTestProxies {

  /**
   * Fake [BrowserContext] that records the listener consumers passed to
   * `on*`/`off*`. Tests can drive synthetic events by invoking those
   * consumers directly.
   */
  class FakeBrowserContext {
    val requestListeners: MutableList<Consumer<Request>> = mutableListOf()
    val responseListeners: MutableList<Consumer<Response>> = mutableListOf()
    val requestFailedListeners: MutableList<Consumer<Request>> = mutableListOf()

    val proxy: BrowserContext = Proxy.newProxyInstance(
      BrowserContext::class.java.classLoader,
      arrayOf(BrowserContext::class.java),
      InvocationHandler { _, method, args ->
        @Suppress("UNCHECKED_CAST")
        when (method.name) {
          "onRequest" -> requestListeners.add(args[0] as Consumer<Request>)
          "offRequest" -> requestListeners.remove(args[0] as Consumer<Request>)
          "onResponse" -> responseListeners.add(args[0] as Consumer<Response>)
          "offResponse" -> responseListeners.remove(args[0] as Consumer<Response>)
          "onRequestFailed" -> requestFailedListeners.add(args[0] as Consumer<Request>)
          "offRequestFailed" -> requestFailedListeners.remove(args[0] as Consumer<Request>)
          "equals" -> return@InvocationHandler args[0] === Proxy.getInvocationHandler(args[0] as Any?)
          "hashCode" -> return@InvocationHandler System.identityHashCode(this)
          "toString" -> return@InvocationHandler "FakeBrowserContext"
          else -> error("Unstubbed BrowserContext.${method.name}")
        }
        null
      },
    ) as BrowserContext

    fun fireRequest(request: Request) {
      requestListeners.toList().forEach { it.accept(request) }
    }

    fun fireResponse(response: Response) {
      responseListeners.toList().forEach { it.accept(response) }
    }

    fun fireRequestFailed(request: Request) {
      requestFailedListeners.toList().forEach { it.accept(request) }
    }
  }

  /**
   * Fake [Request] returning the given fixture data. `postDataBuffer` returns
   * null by default (matching Playwright's behavior on GET).
   */
  fun fakeRequest(
    method: String = "GET",
    url: String = "https://example.com/",
    headers: Map<String, String> = emptyMap(),
    postBody: ByteArray? = null,
  ): Request = Proxy.newProxyInstance(
    Request::class.java.classLoader,
    arrayOf(Request::class.java),
    InvocationHandler { _, m, _ ->
      when (m.name) {
        "method" -> method
        "url" -> url
        "allHeaders", "headers" -> headers
        "postDataBuffer" -> postBody
        "postData" -> postBody?.toString(Charsets.UTF_8)
        "equals" -> false
        "hashCode" -> System.identityHashCode(m)
        "toString" -> "FakeRequest($method $url)"
        else -> error("Unstubbed Request.${m.name}")
      }
    },
  ) as Request

  /**
   * Fake [Response] for the given [request], returning [body] from `body()`
   * and [status] from `status()`.
   */
  fun fakeResponse(
    request: Request,
    status: Int = 200,
    headers: Map<String, String> = emptyMap(),
    body: ByteArray = ByteArray(0),
  ): Response = Proxy.newProxyInstance(
    Response::class.java.classLoader,
    arrayOf(Response::class.java),
    InvocationHandler { _, m, _ ->
      when (m.name) {
        "request" -> request
        "status" -> status
        "allHeaders", "headers" -> headers
        "body" -> body
        "equals" -> false
        "hashCode" -> System.identityHashCode(m)
        "toString" -> "FakeResponse($status)"
        else -> error("Unstubbed Response.${m.name}")
      }
    },
  ) as Response
}
