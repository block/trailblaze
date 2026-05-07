package xyz.block.trailblaze.quickjs.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Typed envelopes for the SDK's `TrailblazeToolResult` shape and the per-invocation `ctx`
 * the runtime hands to bundled handlers. Replaces the earlier `buildJsonObject { put(...) }`
 * scattering with `@Serializable` data classes so the field names live in one place and
 * a typo at a producer call site shows up as a Kotlin compile error rather than a silently
 * malformed JSON envelope at runtime.
 *
 * Sam called this out during PR review — "data class + kotlinx serialization?" / "Hard
 * coded strings always is a smell." Used by:
 *
 *  - [QuickJsRepoHostBinding] — produces error envelopes when `trailblaze.call(...)` is
 *    not yet wired.
 *  - [QuickJsTrailblazeTool] — produces the `ctx` envelope handed to handlers, and
 *    deserializes the result envelope the bundle returns.
 *  - [JsonObject.toTrailblazeToolResult] — consumes the result envelope.
 *
 * Lenient: `ignoreUnknownKeys = true` so a bundle author who tacks on extra fields
 * (`structuredContent`, future content types, etc.) doesn't break dispatch. Defaults on
 * fields where a missing key has a meaningful interpretation (no `content` → empty list,
 * no `isError` → false, no `text` on a content part → null).
 */
@Serializable
internal data class QuickJsToolResultEnvelope(
  val content: List<QuickJsContentPart> = emptyList(),
  val isError: Boolean = false,
)

/**
 * One element of a [QuickJsToolResultEnvelope.content] list. The SDK only mandates
 * `type`; `text` is the most common payload but other content types (image, etc.) may
 * appear with their own fields the runtime doesn't render today. Renderer treats
 * non-text parts as a `<type content>` placeholder.
 */
@Serializable
internal data class QuickJsContentPart(
  val type: String,
  val text: String? = null,
)

/**
 * Per-invocation context handed to a bundled handler as its `ctx` parameter. Authors
 * receive this as `(args, ctx) => ...` in TS — see `@trailblaze/tools`'s
 * `TrailblazeContext` type. The runtime stamps these fields at every dispatch.
 */
@Serializable
internal data class QuickJsToolCtxEnvelope(
  val sessionId: String,
  val device: QuickJsDeviceContext,
  /**
   * The session's active app target — null when the session has no target (web-only,
   * scratch tools, unit-test fixtures). Authors typically read
   * `ctx.target?.resolvedAppId ?? ctx.target?.appIds[0]` to get the app id their tool
   * should act on. See [QuickJsTargetContext] for the field semantics.
   */
  val target: QuickJsTargetContext? = null,
)

/**
 * Device info the bundle handler can read from `ctx.device.platform` /
 * `ctx.device.driver` to specialize behavior per session shape.
 */
@Serializable
internal data class QuickJsDeviceContext(
  val platform: String,
  val driver: String,
)

/**
 * Target info the bundle handler can read from `ctx.target.{id, appIds, resolvedAppId}`
 * to act on the session's active app without hardcoding ids.
 *
 * **Three fields, intentional separation:**
 *  - [id] — the target's identifier from its pack manifest (`"clock"`, `"contacts"`, …).
 *  - [appIds] — the raw declared candidate app ids in priority order, exactly as the
 *    target's pack manifest declares them. Informational; useful when authors want to
 *    inspect "what builds are configured for this target" rather than just launch one.
 *  - [resolvedAppId] — the candidate that's actually installed on the device. Picked at
 *    session start by intersecting [appIds] with the device's installed-apps list (one
 *    `pm list packages` / `simctl listapps` roundtrip per session, cached). Null if no
 *    declared candidate is installed — authors should fall back to `appIds[0]` and let
 *    the launch fail downstream with a clear "app not installed" error.
 *
 * Closes the deferred id-based-overload note from #2699.
 */
@Serializable
internal data class QuickJsTargetContext(
  val id: String,
  val appIds: List<String>,
  val resolvedAppId: String?,
)

/**
 * Module-level `Json` configured for the QuickJS-tool envelopes. Lenient on unknown
 * keys (so a bundle that returns a future content type doesn't break dispatch) and
 * encodes defaults so the produced JSON survives a stricter parser on the JS side
 * without missing-property errors.
 */
internal val QuickJsToolEnvelopeJson: Json = Json {
  ignoreUnknownKeys = true
  encodeDefaults = true
}
