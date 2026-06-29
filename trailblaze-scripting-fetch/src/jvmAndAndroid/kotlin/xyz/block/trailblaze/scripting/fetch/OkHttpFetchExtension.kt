package xyz.block.trailblaze.scripting.fetch

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
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
 * an [OkHttpClient] on the host. The clean replacement for tools that today shell `curl` through
 * `ctx.tools.exec` to reach an HTTP endpoint (e.g. a device bridge listening on `localhost:<port>`).
 *
 * **How it's wired.** Pass an instance to [xyz.block.trailblaze.quickjs.tools.QuickJsToolHost.connect]
 * `(engineExtension = …)`. [install] registers a native `__trailblazeFetch(requestJson)` async
 * function — the same `asyncFunction` idiom the host uses for `__trailblazeCall` — plus a small JS
 * shim that presents the `fetch(input, init) → Response` surface and marshals to/from that native
 * call. The native binding can only return data (not a live JS object with methods), so the shim
 * builds the `Response` (`.status` / `.ok` / `.headers.get()` / `.text()` / `.json()`) on the JS
 * side, exactly as the SDK shim wraps `__trailblazeCall`.
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
 * **Kill-switch.** Set [DISABLE_ENV_VAR] (`TRAILBLAZE_DISABLE_FETCH=1`) to bind a `fetch` that
 * rejects instead — an operator lever for a new outbound-network capability, read when the engine
 * is created (restart the daemon to flip it).
 *
 * **Logging.** Each request emits a daemon-log breadcrumb (quiet-suppressed; `-v` or the log file
 * to see it): `<METHOD> <status> <full-url> (<ms>ms)`, e.g.
 * `POST 200 https://host.com/path?one=two (123ms)`; failures and allow-list denials log the same
 * shape with `FAILED` / `BLOCKED` in the status slot. The **full URL incl. query string** is
 * logged, so a credential passed as a query param appears in the daemon log — an accepted tradeoff
 * for request visibility. Request/response **headers and bodies are never logged**.
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

  override suspend fun install(quickJs: QuickJs) {
    // Operator kill-switch (mirrors the repo's `TRAILBLAZE_DISABLE_*` convention). When set, bind
    // a `fetch` that rejects with a clear message rather than leaving it undefined — so a tool that
    // depended on it fails legibly instead of with "fetch is not a function". Read when the engine
    // is created (per scripted-tool host); restart the daemon to flip it.
    if (isFetchDisabled(System.getenv(DISABLE_ENV_VAR))) {
      Console.log("[OkHttpFetchExtension] fetch disabled via $DISABLE_ENV_VAR — binding a stub that rejects.")
      quickJs.evaluate<Any?>(DISABLED_FETCH_SHIM_JS, "trailblaze-fetch-disabled-shim.js", false)
      return
    }
    // Native side: takes the JS-stringified request, returns the JS-parseable response (or a
    // `{ __fetchError }` envelope). Same `(string) -> string` async-binding shape as
    // `__trailblazeCall`, so QuickJS can `await` it from the shim.
    quickJs.asyncFunction(FETCH_BINDING_NAME) { args ->
      val requestJson = args.getOrNull(0) as? String
        ?: error("$FETCH_BINDING_NAME requires a request JSON string argument")
      executeFetch(requestJson)
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
      Console.log("[OkHttpFetchExtension] $method BLOCKED ${request.url} — host '${httpUrl.host}' not in allow-list")
      return errorJson(
        "host '${httpUrl.host}' is not permitted by this fetch allow-list. (fetch is unrestricted " +
          "by default; this run opted into a FetchHostAllowlist — add the host via " +
          "FetchHostAllowlist.allowHosts(...) if it should be reachable.)",
      )
    }
    return try {
      withContext(Dispatchers.IO) {
        val startedMs = System.currentTimeMillis()
        httpClient.newCall(buildRequest(httpUrl, request)).execute().use { response ->
          val bodyText = response.body.string()
          // Per-request breadcrumb: `METHOD status full-url (ms)`, e.g.
          // `POST 200 https://host.com/path?one=two (123ms)`. The FULL URL (incl. query string) is
          // logged so the daemon log shows exactly what each tool hit — a query-param credential
          // therefore appears here (quiet-suppressed daemon log; `curl`-via-`exec` exposes URLs
          // anyway). Request/response HEADERS and BODIES are never logged.
          Console.log("[OkHttpFetchExtension] $method ${response.code} ${request.url} (${System.currentTimeMillis() - startedMs}ms)")
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
      Console.log("[OkHttpFetchExtension] $method FAILED ${request.url} — ${e::class.simpleName}: ${e.message}")
      errorJson("${e::class.simpleName ?: "Error"}: ${e.message}")
    }
  }

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
     * host launchers install an unrestricted `OkHttpFetchExtension()`); use this when a deployment
     * deliberately wants to limit `fetch` to a local device bridge on `localhost:<port>`.
     */
    fun localhostOnly(client: OkHttpClient = DEFAULT_CLIENT): OkHttpFetchExtension =
      OkHttpFetchExtension(client = client, allowlist = FetchHostAllowlist.localhostOnly())

    /** Operator kill-switch env var. `1`/`true` (case-insensitive) disables `fetch`. */
    const val DISABLE_ENV_VAR: String = "TRAILBLAZE_DISABLE_FETCH"

    private val METHODS_REQUIRING_BODY: Set<String> =
      setOf("POST", "PUT", "PATCH", "PROPPATCH", "REPORT")

    private val JSON: Json = Json { ignoreUnknownKeys = true }

    /**
     * Pure parse of the kill-switch env value, split out so it's unit-testable without touching the
     * process environment. `1` / `true` (case-insensitive, trimmed) → disabled.
     */
    internal fun isFetchDisabled(envValue: String?): Boolean =
      envValue?.trim()?.lowercase().let { it == "1" || it == "true" }

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
     * Stub installed in place of the real `fetch` when [DISABLE_ENV_VAR] is set: a `fetch` that
     * rejects with a clear message, so a tool that depended on it fails legibly.
     */
    internal val DISABLED_FETCH_SHIM_JS: String =
      """
      (function () {
        globalThis.fetch = function () {
          return Promise.reject(new TypeError(
            'fetch is disabled on this runtime ($DISABLE_ENV_VAR); ' +
            'use ctx.tools.exec or a runtime: subprocess tool instead.'
          ));
        };
      })();
      """.trimIndent()

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
