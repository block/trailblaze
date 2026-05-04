package xyz.block.trailblaze.network

import kotlinx.serialization.Serializable

/**
 * One captured network exchange phase. NDJSON-friendly — each emitted line in
 * `<session-dir>/network.ndjson` deserializes to one [NetworkEvent].
 *
 * The schema is shared across capture sources so the report renderer (and any
 * downstream analysis like HAR export, idling, or assertions) can treat web
 * and mobile capture identically.
 *
 * REQUEST_START and RESPONSE_END for the same exchange share an [id] so a
 * reader can pair them. FAILED is terminal (no matching RESPONSE_END).
 */
@Serializable
data class NetworkEvent(
  val id: String,
  val sessionId: String,
  val timestampMs: Long,
  val phase: Phase,
  val method: String,
  val url: String,
  val urlPath: String,
  val statusCode: Int? = null,
  val durationMs: Long? = null,
  /**
   * Headers as captured. Sensitive headers (e.g. `Authorization`) keep their
   * key but have their value replaced with [REDACTED_VALUE] so consumers can
   * see the header was sent without the secret leaking into session
   * artifacts. Cookie / Set-Cookie aren't surfaced at all today — Playwright's
   * non-blocking `headers()` API filters them — so the absence of those keys
   * here is "Playwright didn't expose them", not "we redacted them".
   */
  val requestHeaders: Map<String, String>? = null,
  val responseHeaders: Map<String, String>? = null,
  val requestBodyRef: BodyRef? = null,
  val responseBodyRef: BodyRef? = null,
  val source: Source,
)

/** Placeholder value substituted for sensitive header values; see [NetworkEvent.requestHeaders]. */
const val REDACTED_VALUE: String = "***REDACTED***"

/**
 * Reference to a request/response body, either inline (small text) or as a
 * sidecar blob on disk (large or binary). Renderers should prefer [inlineText]
 * when present and fall back to reading [blobPath] otherwise.
 *
 * [blobPath] is relative to the session directory so the entire session bundle
 * is portable. [truncated] is set when the captured payload exceeded the
 * writer's max-blob threshold; [sizeBytes] still reports the original payload
 * size so the renderer can show real magnitude alongside the truncation.
 */
@Serializable
data class BodyRef(
  val sizeBytes: Long,
  val contentType: String?,
  val inlineText: String? = null,
  val blobPath: String? = null,
  val truncated: Boolean = false,
)

@Serializable
enum class Phase {
  REQUEST_START,
  RESPONSE_END,
  FAILED,
}

/**
 * Capture source — identifies which engine produced the event. PLAYWRIGHT_WEB
 * is emitted today; ANDROID / IOS writers land via on-device engines.
 */
@Serializable
enum class Source {
  PLAYWRIGHT_WEB,
  ANDROID,
  IOS,
}
