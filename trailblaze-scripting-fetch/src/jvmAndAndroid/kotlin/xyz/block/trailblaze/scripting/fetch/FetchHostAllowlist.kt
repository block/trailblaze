package xyz.block.trailblaze.scripting.fetch

/**
 * Optional host allow-list for the scripted-tool `fetch` binding ([OkHttpFetchExtension]).
 *
 * **Posture.** `fetch` is **unrestricted by default** ([allowAll]) — it reaches any host, like the
 * `ctx.tools.exec` + `curl` it replaces; keeping a recorded run replay-deterministic is the
 * author's responsibility. This class is the *opt-in* knob for a consumer that wants to constrain
 * `fetch`: [localhostOnly] (loopback only — e.g. a device bridge on `localhost:<port>`) or
 * [allowHosts] (a named set). When a restrictive list is in effect, a request to a non-permitted
 * host fails before any socket opens.
 *
 * The matching surface is intentionally tiny and **pure** — [isAllowed] is a string-in → boolean
 * with no I/O — so it's unit-testable without an engine or a socket.
 */
class FetchHostAllowlist private constructor(
  private val allowLocalhost: Boolean,
  /** Exact host names (lower-cased) that are permitted in addition to the localhost rule. */
  private val allowedHosts: Set<String>,
  /** Escape hatch: permit every host. Defeats replay determinism — opt in deliberately. */
  private val allowAll: Boolean,
) {
  /**
   * True when [host] (the host component of a request URL, e.g. `localhost`, `127.0.0.1`,
   * `api.internal`) is permitted. Case-insensitive. Never throws.
   */
  fun isAllowed(host: String): Boolean {
    if (allowAll) return true
    val normalized = host.lowercase()
    if (allowLocalhost && isLoopbackHost(normalized)) return true
    return normalized in allowedHosts
  }

  /**
   * True when this list permits every host (the unrestricted default). [OkHttpFetchExtension] reads
   * this to decide whether to keep standard redirect-following (unrestricted) or disable it (a
   * restrictive list, so a redirect can't hop past the allow-list).
   */
  val allowsAllHosts: Boolean get() = allowAll

  companion object {
    /**
     * Loopback hosts only — everything else denied. Opt-in (not the default); covers the
     * device-bridge-on-localhost case when a deployment wants to constrain `fetch`.
     */
    fun localhostOnly(): FetchHostAllowlist =
      FetchHostAllowlist(allowLocalhost = true, allowedHosts = emptySet(), allowAll = false)

    /**
     * Permit the given [hosts] (matched case-insensitively, exact host name) in addition to
     * loopback when [includeLocalhost] is true (the default). Use for a tool that must reach a
     * specific, known internal host — naming it keeps replay deterministic-ish and the blast
     * radius small.
     */
    fun allowHosts(hosts: Set<String>, includeLocalhost: Boolean = true): FetchHostAllowlist =
      FetchHostAllowlist(
        allowLocalhost = includeLocalhost,
        allowedHosts = hosts.map { it.lowercase() }.toSet(),
        allowAll = false,
      )

    /**
     * Permit every host — the default for [OkHttpFetchExtension] (`fetch` is unrestricted, like the
     * `curl` it replaces). A consumer that wants to constrain `fetch` uses [localhostOnly] /
     * [allowHosts] instead.
     */
    fun allowAll(): FetchHostAllowlist =
      FetchHostAllowlist(allowLocalhost = true, allowedHosts = emptySet(), allowAll = true)

    /**
     * Loopback-host predicate. `0.0.0.0` is deliberately NOT loopback (it's the wildcard bind
     * address, routable off-box). `::1` is matched both bare and bracketed because a URL authority
     * carries IPv6 literals in brackets (`http://[::1]:8080/`) while the parsed host is bare.
     */
    internal fun isLoopbackHost(host: String): Boolean =
      host == "localhost" ||
        host == "127.0.0.1" ||
        host == "::1" ||
        host == "[::1]" ||
        host.endsWith(".localhost")
  }
}
