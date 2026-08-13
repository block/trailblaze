package xyz.block.trailblaze.scripting.fetch

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.function
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import xyz.block.trailblaze.quickjs.tools.QuickJsEngineExtension
import xyz.block.trailblaze.util.Console

/**
 * Installs a real, WHATWG-shaped `globalThis.fetch` into a scripted-tool QuickJS engine, backed by
 * an [OkHttpClient] (on the host JVM or on-device ART). The clean replacement for tools that today shell `curl` through
 * `ctx.tools.exec` to reach an HTTP endpoint (e.g. a device bridge listening on `localhost:<port>`).
 *
 * **How it's wired.** Pass an instance to [xyz.block.trailblaze.quickjs.tools.QuickJsToolHost.connect]
 * `(engineExtension = …)`. [install] registers a native `__trailblazeFetch(requestJson)`
 * function — the same synchronous-binding-plus-JS-shim idiom the host uses for `__trailblazeCall`
 * (NOT `asyncFunction`; see the binding's install site for why) — plus a small JS shim that
 * presents the `fetch(input, init) → Response` surface and marshals to/from that native call. The
 * native binding can only return data (not a live JS object with methods), so the shim builds the
 * `Response` (`.status` / `.ok` / `.headers.get()` / `.text()` / `.json()`) on the JS side, exactly
 * as the SDK shim wraps `__trailblazeCall`.
 *
 * **Posture: author-only.** Like [xyz.block.trailblaze.quickjs.tools.HostBinding] and
 * `ctx.tools.exec`, this surfaces a capability to scripted-tool *authors*, never to the LLM.
 *
 * **Host access.** Unrestricted by default ([FetchHostAllowlist.allowAll]) — `fetch` reaches any
 * host, exactly like the `ctx.tools.exec` + `curl` it replaces, so it isn't artificially weaker
 * than the escape hatch. Keeping a recorded run replay-deterministic is the author's
 * responsibility (as with `exec`). A caller that *wants* to constrain `fetch` can opt into a
 * [FetchHostAllowlist] — [FetchHostAllowlist.localhostOnly] (loopback only) or
 * [FetchHostAllowlist.allowHosts] (a named set); a denied host then fails with a clear error
 * before any socket opens. **Redirects** follow the WHATWG default (followed) when unrestricted;
 * when a restrictive allow-list is supplied they are NOT followed, so a permitted host can't 30x
 * past the allow-list to a denied one (see [httpClient]).
 *
 * **TLS.** Certificates are validated normally for every real host. Validation is skipped for
 * **device-local hosts only** ([isDeviceLocalHost]) — loopback, plus the emulator's host alias when
 * running on Android — which is where Trailblaze's own HTTPS surfaces live and where they present
 * self-signed certificates by construction (the on-device server, and the host daemon reached over
 * `adb reverse`). Every other Trailblaze HTTP client disables validation *globally* for that same
 * reason; scoping it to the addresses that need it keeps a tool's call to a real API fully
 * validated. See [tlsRelaxedClient].
 *
 * **Logging.** Each request emits a log breadcrumb (quiet-suppressed on the host; `-v` or the log
 * file to see it): `<METHOD> <status> <url> (<ms>ms)`, e.g. `POST 200 https://host.com/path
 * (123ms)`; failures and allow-list denials log the same shape with `FAILED` / `BLOCKED` in the
 * status slot. **Userinfo, the query string and the fragment are stripped** — see [logSafeUrl],
 * which explains why (on-device these lines reach logcat, which CI uploads) and notes the one case
 * the surviving path does not cover. Request/response **headers and bodies are never logged**.
 *
 * **Scope: basic.** GET/POST/… with headers and a string body; response `status` / `statusText` /
 * `ok` / `headers` / `text()` / `json()`. Streaming bodies and `arrayBuffer()` are out of scope —
 * a tool that needs them belongs on the `runtime: subprocess` path (bun's full `fetch`).
 *
 * **Proxy: deliberately NOT supported (intentional, not a deferred feature).** WHATWG `fetch` has
 * no proxy concept — proxy
 * is a property of the backing client, not the call. This binding relies on OkHttp's default
 * `ProxySelector`, which honors the JVM proxy **system properties**
 * (`-Dhttp.proxyHost/Port`, `-Dhttps.proxyHost/Port`, and `http.nonProxyHosts` — put `localhost`
 * there for the device-bridge case). OkHttp does NOT read `HTTP_PROXY` / `HTTPS_PROXY` env vars by
 * default. A tool that genuinely needs explicit/per-call proxying should use an existing escape
 * hatch instead of us adding a proxy option here: (a) `ctx.tools.exec` with `curl --proxy <url>`
 * (curl honors `http(s)_proxy` / `NO_PROXY`), or (b) a `runtime: subprocess` tool, where bun's
 * `fetch` exposes a `proxy` option and honors the proxy env vars. Both are author-only and
 * host-side — the correct scope for proxying. Do not add a proxy option to this binding; point
 * "I need a proxy" requests at those hatches.
 *
 * @param client the backing OkHttp client. Shares one process-wide [DEFAULT_CLIENT] by default
 *   (OkHttp pools connections and is built to be shared). Inject a custom client to tune timeouts
 *   or configure a proxy at the client level (see the proxy note above).
 * @param allowlist which hosts `fetch` may reach. Defaults to [FetchHostAllowlist.allowAll]
 *   (unrestricted). Pass [FetchHostAllowlist.localhostOnly] / [FetchHostAllowlist.allowHosts] to
 *   constrain it.
 */
class OkHttpFetchExtension(
  private val client: OkHttpClient = DEFAULT_CLIENT,
  private val allowlist: FetchHostAllowlist = FetchHostAllowlist.allowAll(),
) : QuickJsEngineExtension {

  /**
   * The actual client used for requests. When the [allowlist] is unrestricted (the default) this is
   * just [client] — standard behavior, including following redirects. When a *restrictive*
   * allow-list is supplied, redirects are force-disabled so a permitted host can't transparently
   * 30x past the allow-list to a denied one (the allow-list is checked against the request URL's
   * host only; a tool that must follow a redirect reads `res.status` / the `Location` header and
   * issues a second `fetch`, which is re-checked). `newBuilder()` shares [client]'s connection pool
   * + dispatcher, so the restricted variant is cheap.
   */
  private val httpClient: OkHttpClient =
    if (allowlist.allowsAllHosts) {
      client
    } else {
      client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    }

  /**
   * [httpClient] with certificate and hostname verification disabled, used **only** for requests to
   * a device-local host ([isDeviceLocalHost]).
   *
   * Trailblaze's HTTPS surfaces on those addresses present self-signed certificates — the on-device
   * server, and the host daemon a device reaches over `adb reverse` (`localhost`) or the emulator
   * alias (`10.0.2.2`). No trust store can validate them, which is why every other Trailblaze HTTP
   * client (`TrailblazeHttpClientFactory.createInsecureTrustAllCertsHttpClient`, used by the logging
   * rule and the on-device LLM client) turns validation off outright. Doing that globally here would
   * silently downgrade a tool's call to a real API, so the relaxation is scoped to the hosts that
   * require it: a self-signed loopback cert already implies code running on the device.
   *
   * Lazy, so a run whose tools never touch a device-local host never builds an insecure SSL context.
   * `newBuilder()` shares [httpClient]'s connection pool and dispatcher.
   */
  private val tlsRelaxedClient: OkHttpClient by lazy {
    val trustAll = @Suppress("CustomX509TrustManager") object : X509TrustManager {
      @Suppress("TrustAllX509TrustManager")
      override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit

      @Suppress("TrustAllX509TrustManager")
      override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit

      override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    val sslContext = SSLContext.getInstance("TLS").apply {
      init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
    }
    httpClient.newBuilder()
      .sslSocketFactory(sslContext.socketFactory, trustAll)
      // A self-signed cert for a local port rarely carries a matching SAN either; verifying the
      // hostname against it would fail the handshake for the same reason the trust check does.
      .hostnameVerifier { _, _ -> true }
      .build()
  }

  override suspend fun install(quickJs: QuickJs) {
    // Native side: takes the JS-stringified request, returns the JS-parseable response (or a
    // `{ __fetchError }` envelope). Same `(string) -> string` binding shape as `__trailblazeCall`,
    // so QuickJS can `await` it from the shim. Synchronous binding, NOT asyncFunction — quickjs-kt
    // 1.0.5's asyncFunction invoke path has a native JNI reference-lifecycle bug that crashes the
    // JVM (block/trailblaze#194; see QuickJsToolHost's __trailblazeCall install site for the full
    // rationale). runBlocking here blocks the confined engine thread only for the duration of the
    // call; the actual HTTP request runs on Dispatchers.IO inside executeFetch, not on this thread.
    quickJs.function(FETCH_BINDING_NAME) { args ->
      val requestJson = args.getOrNull(0) as? String
        ?: error("$FETCH_BINDING_NAME requires a request JSON string argument")
      runBlocking { executeFetch(requestJson) }
    }
    // JS side: define `globalThis.fetch` wrapping the native binding with the WHATWG surface.
    quickJs.evaluate<Any?>(FETCH_SHIM_JS, "trailblaze-fetch-shim.js", false)
  }

  /**
   * The pure-ish core: decode a request JSON, enforce the allow-list, perform the HTTP call on
   * [Dispatchers.IO], and encode the response JSON. Returns a `{ __fetchError }` envelope (never
   * throws out) for a malformed request, a denied host, an unsupported URL, or a transport
   * failure, so the JS shim can turn it into a `TypeError` the author's `try/catch` sees.
   * `internal` so the module's tests can drive it directly without standing up an engine.
   */
  internal suspend fun executeFetch(requestJson: String): String {
    val request = try {
      JSON.decodeFromString(FetchRequestPayload.serializer(), requestJson)
    } catch (e: Exception) {
      return errorJson("malformed fetch request JSON: ${e.message}")
    }
    val httpUrl = request.url.toHttpUrlOrNull()
      ?: return errorJson(
        "invalid or unsupported URL '${request.url}' — only http/https URLs are supported",
      )
    val method = request.method.uppercase()
    if (!allowlist.isAllowed(httpUrl.host)) {
      // Only reachable when a restrictive allow-list was opted into. Same shape as the success line,
      // BLOCKED in the status slot.
      Console.log("[OkHttpFetchExtension] $method BLOCKED ${logSafeUrl(httpUrl)} — host '${httpUrl.host}' not in allow-list")
      return errorJson(
        "host '${httpUrl.host}' is not permitted by this fetch allow-list. (fetch is unrestricted " +
          "by default; this run opted into a FetchHostAllowlist — add the host via " +
          "FetchHostAllowlist.allowHosts(...) if it should be reachable.)",
      )
    }
    // Device-local HTTPS is the self-signed case; everything else keeps full validation.
    val callClient =
      if (httpUrl.isHttps && isDeviceLocalHost(httpUrl.host)) tlsRelaxedClient else httpClient
    return try {
      withContext(Dispatchers.IO) {
        val startedMs = System.currentTimeMillis()
        callClient.newCall(buildRequest(httpUrl, request)).execute().use { response ->
          val bodyText = response.body.string()
          // Per-request breadcrumb: `METHOD status url (ms)`, e.g.
          // `POST 200 https://host.com/path (123ms)`. See [logSafeUrl] for why the query string is
          // redacted. Request/response HEADERS and BODIES are never logged.
          Console.log("[OkHttpFetchExtension] $method ${response.code} ${logSafeUrl(httpUrl)} (${System.currentTimeMillis() - startedMs}ms)")
          JSON.encodeToString(
            FetchResponsePayload.serializer(),
            FetchResponsePayload(
              status = response.code,
              statusText = response.message,
              url = response.request.url.toString(),
              headers = response.headers.map { (name, value) -> listOf(name, value) },
              bodyText = bodyText,
            ),
          )
        }
      }
    } catch (e: Exception) {
      // Transport failure (timeout, connection refused, DNS). Same shape, FAILED in the status slot.
      // The author still gets the thrown error; this makes a silently swallowed `fetch().catch(...)`
      // debuggable from the daemon log.
      Console.log("[OkHttpFetchExtension] $method FAILED ${logSafeUrl(httpUrl)} — ${e::class.simpleName}: ${e.message}")
      errorJson("${e::class.simpleName ?: "Error"}: ${e.message}")
    }
  }

  /**
   * The request URL as it is safe to log: scheme, host, port and path only. The three URL
   * components that can carry a credential are stripped — **userinfo** (`user:password@`), the
   * **query string** (replaced by a `?<redacted>` marker when one was present, so the log still
   * shows that parameters existed), and the **fragment** (never sent to the server anyway, so it
   * has no diagnostic value here).
   *
   * These used to be logged in full, on the reasoning that this was a local, quiet-suppressed
   * daemon log. That reasoning does not hold on-device, where this binding also runs: `Console.log`
   * maps to `Log.i` on Android and `enableQuietMode` is a no-op there, so every line is emitted to
   * logcat unconditionally — and logcat is streamed into the session's `device.log`, which CI zips
   * and uploads as a build artifact. A credential anywhere in the URL would therefore leave the
   * device.
   *
   * The path is deliberately kept: it identifies which endpoint a tool hit, which is the whole
   * point of the breadcrumb. Note that this is a real tradeoff rather than a safe default — a
   * webhook-shaped URL carries its secret *in the path*, so a tool that fetches one will log it.
   * A tool handling a path-embedded secret should not rely on this redaction.
   *
   * `internal` so it's unit-testable as a pure function ([OkHttpFetchExtensionRedactionTest])
   * without driving a real request — the redaction is the security-load-bearing part of the
   * logging, so it's pinned directly rather than via the emitted log line.
   */
  internal fun logSafeUrl(httpUrl: HttpUrl): String {
    val hadQuery = httpUrl.querySize > 0
    val stripped =
      httpUrl.newBuilder().username("").password("").query(null).fragment(null).build()
    return if (hadQuery) "$stripped?<redacted>" else stripped.toString()
  }

  /**
   * Whether [host] names the device itself or the machine hosting it — the only addresses whose
   * certificates [tlsRelaxedClient] accepts unvalidated.
   *
   * A literal set rather than an `InetAddress` lookup on purpose: this decides which client a
   * request uses, so it must not perform a blocking DNS resolution, and a name that *resolves* to
   * loopback (a public DNS record pointing at `127.0.0.1`) is not the local trust domain this
   * relaxation is scoped to. The emulator's `10.0.2.2` host alias is included **only on Android**
   * ([EMULATOR_HOST_ALIASES]) — it names the host machine when the caller is the emulator, and is
   * an ordinary routable address to the host JVM running this same extension.
   */
  internal fun isDeviceLocalHost(host: String): Boolean = host.lowercase() in DEVICE_LOCAL_HOSTS

  private fun buildRequest(httpUrl: HttpUrl, payload: FetchRequestPayload): Request {
    val builder = Request.Builder().url(httpUrl)
    for (header in payload.headers) {
      if (header.size >= 2) builder.addHeader(header[0], header[1])
    }
    val method = payload.method.uppercase()
    // GET/HEAD forbid a body; POST/PUT/PATCH/… require one. Synthesize an empty body for the
    // require-a-body case so a body-less POST doesn't throw inside OkHttp's builder.
    val forbidsBody = method == "GET" || method == "HEAD"
    val requiresBody = method in METHODS_REQUIRING_BODY
    val requestBody = when {
      forbidsBody -> null
      payload.body != null -> payload.body.toRequestBody(null)
      requiresBody -> "".toRequestBody(null)
      else -> null
    }
    builder.method(method, requestBody)
    return builder.build()
  }

  private fun errorJson(message: String): String =
    JSON.encodeToString(FetchErrorPayload.serializer(), FetchErrorPayload(message))

  /** Wire shape the JS shim sends to [FETCH_BINDING_NAME]: `{ url, method, headers, body }`. */
  @Serializable
  private data class FetchRequestPayload(
    val url: String,
    val method: String = "GET",
    /** Header pairs as `[name, value]` — preserves duplicates/order the JS side collected. */
    val headers: List<List<String>> = emptyList(),
    val body: String? = null,
  )

  /** Wire shape returned on success; the shim builds the `Response` object from it. */
  @Serializable
  private data class FetchResponsePayload(
    val status: Int,
    val statusText: String,
    val url: String,
    val headers: List<List<String>>,
    val bodyText: String,
  )

  /**
   * Wire shape returned on failure. `__fetchError` is the discriminator the shim checks; it throws
   * a `TypeError` on the JS side (matching WHATWG `fetch`, which rejects on network failure).
   */
  @Serializable
  private data class FetchErrorPayload(
    @SerialName("__fetchError") val fetchError: String,
  )

  companion object {
    /** Name of the native async binding the JS `fetch` shim calls. */
    const val FETCH_BINDING_NAME: String = "__trailblazeFetch"

    /**
     * Opt-in convenience: an extension constrained to loopback hosts only. Not the default (the
     * host and on-device launchers install an unrestricted `OkHttpFetchExtension()`); use this when a deployment
     * deliberately wants to limit `fetch` to a local device bridge on `localhost:<port>`.
     */
    fun localhostOnly(client: OkHttpClient = DEFAULT_CLIENT): OkHttpFetchExtension =
      OkHttpFetchExtension(client = client, allowlist = FetchHostAllowlist.localhostOnly())

    /**
     * Hosts [isDeviceLocalHost] treats as the device / its host machine. Lowercase.
     *
     * Loopback in every spelling a URL can carry, plus [EMULATOR_HOST_ALIASES] — which is
     * Android-only, since `10.0.2.2` names the host machine only when the caller is the emulator
     * and is an ordinary routable address to the host JVM that installs this same extension.
     */
    private val DEVICE_LOCAL_HOSTS: Set<String> =
      setOf("localhost", "127.0.0.1", "::1", "0:0:0:0:0:0:0:1") + EMULATOR_HOST_ALIASES

    private val METHODS_REQUIRING_BODY: Set<String> =
      setOf("POST", "PUT", "PATCH", "PROPPATCH", "REPORT")

    private val JSON: Json = Json { ignoreUnknownKeys = true }

    /**
     * Process-wide shared client (OkHttp pools connections and is designed to be shared across the
     * whole process). Bounded timeouts so a wedged endpoint fails the tool instead of hanging the
     * session. Uses OkHttp's default `ProxySelector` (honors JVM proxy system properties) — see
     * the class-level proxy note. Redirect-following is left at OkHttp's default here; [httpClient]
     * disables it per-instance only when a restrictive allow-list is in effect.
     */
    private val DEFAULT_CLIENT: OkHttpClient by lazy {
      OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    }

    /**
     * The JS shim defining `globalThis.fetch`. Deliberately avoids template literals / `$` (so it
     * stays a plain Kotlin raw string) and any global the engine doesn't provide (no `Headers`
     * constructor — the shim builds a minimal headers object itself, since neither the QuickJS
     * host nor the on-device prelude ships one).
     */
    internal val FETCH_SHIM_JS: String =
      """
      (function () {
        if (globalThis.__trailblazeFetchInstalled) { return; }
        globalThis.__trailblazeFetchInstalled = true;

        function makeHeaders(pairs) {
          var map = {};
          var order = [];
          for (var i = 0; i < pairs.length; i++) {
            var lower = String(pairs[i][0]).toLowerCase();
            var value = String(pairs[i][1]);
            if (Object.prototype.hasOwnProperty.call(map, lower)) {
              map[lower] = map[lower] + ', ' + value;
            } else {
              map[lower] = value;
              order.push(lower);
            }
          }
          return {
            get: function (n) {
              var k = String(n).toLowerCase();
              return Object.prototype.hasOwnProperty.call(map, k) ? map[k] : null;
            },
            has: function (n) {
              return Object.prototype.hasOwnProperty.call(map, String(n).toLowerCase());
            },
            forEach: function (cb) {
              for (var i = 0; i < order.length; i++) { cb(map[order[i]], order[i], this); }
            },
            keys: function () { return order.slice(); },
            values: function () { return order.map(function (k) { return map[k]; }); },
            entries: function () { return order.map(function (k) { return [k, map[k]]; }); },
            // `for (const [k, v] of res.headers)` — iterate the header pairs. keys()/values()/
            // entries() return arrays (themselves iterable), which covers the common for-of usage.
            // Mutation methods (append/set/delete) are intentionally omitted: this is a read-only
            // view of a response's headers.
            [Symbol.iterator]: function () {
              return order.map(function (k) { return [k, map[k]]; })[Symbol.iterator]();
            },
          };
        }

        function normalizeHeaders(h) {
          var out = [];
          if (!h) { return out; }
          if (typeof h.forEach === 'function' && !Array.isArray(h)) {
            h.forEach(function (value, key) { out.push([String(key), String(value)]); });
            return out;
          }
          if (Array.isArray(h)) {
            for (var i = 0; i < h.length; i++) { out.push([String(h[i][0]), String(h[i][1])]); }
            return out;
          }
          for (var key in h) {
            if (Object.prototype.hasOwnProperty.call(h, key)) {
              out.push([String(key), String(h[key])]);
            }
          }
          return out;
        }

        globalThis.fetch = async function (input, init) {
          init = init || {};
          var url;
          if (typeof input === 'string') { url = input; }
          else if (input && typeof input.url === 'string') { url = input.url; }
          else { url = String(input); }

          var requestJson = JSON.stringify({
            url: url,
            method: init.method ? String(init.method) : 'GET',
            headers: normalizeHeaders(init.headers),
            body: (init.body === undefined || init.body === null) ? null : String(init.body),
          });

          var responseJson = await globalThis.__trailblazeFetch(requestJson);
          var raw = JSON.parse(responseJson);
          if (raw && raw.__fetchError) {
            throw new TypeError('fetch failed: ' + raw.__fetchError);
          }

          var bodyText = (raw.bodyText === undefined || raw.bodyText === null) ? '' : raw.bodyText;
          var status = raw.status;
          return {
            status: status,
            statusText: raw.statusText || '',
            ok: status >= 200 && status < 300,
            url: raw.url || url,
            redirected: false,
            type: 'basic',
            headers: makeHeaders(raw.headers || []),
            text: function () { return Promise.resolve(bodyText); },
            json: function () {
              // Match WHATWG: json() REJECTS on a non-JSON body rather than throwing a raw
              // SyntaxError, and the message names the binding so the author knows the origin.
              try { return Promise.resolve(JSON.parse(bodyText)); }
              catch (e) {
                return Promise.reject(new TypeError(
                  'failed to parse response body as JSON: ' + ((e && e.message) || e)
                ));
              }
            },
            arrayBuffer: function () {
              // Reject (don't throw synchronously) so it matches the declared
              // `arrayBuffer(): Promise<ArrayBuffer>` and works with `.catch(...)`.
              return Promise.reject(new TypeError(
                'Response.arrayBuffer() is not supported by the Trailblaze fetch binding; ' +
                'use a runtime: subprocess tool for binary response bodies.'
              ));
            },
          };
        };
      })();
      """.trimIndent()
  }
}
