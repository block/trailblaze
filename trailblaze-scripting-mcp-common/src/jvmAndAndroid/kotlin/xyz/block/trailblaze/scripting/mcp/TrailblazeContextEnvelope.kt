package xyz.block.trailblaze.scripting.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext

/**
 * Builders for the two envelopes Trailblaze injects into every `tools/call`:
 *
 *  1. **Legacy arg envelope** under the reserved `_trailblazeContext` argument key — shape
 *     frozen by the conventions devlog (§ 2): `{ memory, device }`. Existing raw-SDK tools
 *     (e.g. `examples/android-sample-app/trailblaze-config/mcp/tools.ts`) read from
 *     this key directly. Must stay backwards-compatible.
 *
 *  2. **`_meta.trailblaze` envelope** — richer shape the scripting SDK consumes, per the
 *     envelope-migration devlog ([`2026-04-22-scripting-sdk-envelope-migration.md`](../../../../../../../../docs/devlog/2026-04-22-scripting-sdk-envelope-migration.md)).
 *     Adds `baseUrl`, `sessionId`, and a per-call `invocationId` on top of the device + memory
 *     snapshot so the subprocess can hit `/scripting/callback` in later PRs.
 *
 * Both are emitted on every `tools/call` request. Authors using the new SDK read from
 * `request.params._meta.trailblaze`; authors on the raw SDK continue to read the arg key. Once
 * the arg-key path is formally deprecated (separate landing), it can drop away without
 * affecting SDK consumers.
 *
 * Shared across the subprocess (`:trailblaze-scripting-subprocess`) and on-device bundle
 * (`:trailblaze-scripting-bundle`) runtimes — both build the envelope identically so a TS
 * author writes one handler that reads `ctx` and has it work the same whether the tool is
 * spawned as a subprocess on the host or evaluated in-process inside QuickJS on-device.
 *
 * ```typescript
 * // The MCP `_meta.trailblaze` shape — kept in sync with the TS SDK's TrailblazeContext type.
 * type TrailblazeMeta = {
 *   baseUrl?: string;
 *   runtime?: "ondevice"; // optional; absent on subprocess / daemon paths.
 *   sessionId: string;
 *   invocationId: string;
 *   device: {
 *     platform: "ios" | "android" | "web";
 *     widthPixels: number;
 *     heightPixels: number;
 *     driverType: string;
 *   };
 *   memory: Record<string, unknown>;
 * };
 * ```
 */
object TrailblazeContextEnvelope {

  /** Reserved argument key for the legacy envelope. Public API; breaking changes require a bump. */
  const val RESERVED_KEY: String = "_trailblazeContext"

  /** Top-level `_meta` bucket holding the Trailblaze envelope. */
  const val META_KEY: String = "trailblaze"

  /**
   * Runtime tag stamped onto `_meta.trailblaze.runtime` for invocations dispatched through
   * the on-device QuickJS bundle runtime (`:trailblaze-scripting-bundle`). TS SDK consumers
   * read this to pick the in-process callback transport (`globalThis.__trailblazeCallback`)
   * instead of HTTP fetch. Absent on subprocess / daemon paths — their callback channel is
   * the `/scripting/callback` HTTP endpoint, which the SDK dispatches to by default when
   * [TrailblazeContext.baseUrl] is populated.
   */
  const val RUNTIME_ONDEVICE: String = "ondevice"

  /**
   * Legacy arg-key envelope — `{ memory, device }` only. Used by the `_trailblazeContext`
   * injection path that pre-dates the SDK. Authors who read the arg directly see exactly this
   * shape.
   *
   * Shortcut that reads the [TrailblazeToolExecutionContext] directly.
   */
  fun buildLegacyArgEnvelope(context: TrailblazeToolExecutionContext): JsonObject =
    buildLegacyArgEnvelope(memory = context.memory, device = context.trailblazeDeviceInfo)

  /** Lower-level entry point so tests can construct an envelope without a full context. */
  fun buildLegacyArgEnvelope(memory: AgentMemory, device: TrailblazeDeviceInfo): JsonObject =
    buildJsonObject {
      putJsonObject("memory") {
        memory.variables.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
      }
      putDeviceObject(device)
    }

  /**
   * `_meta.trailblaze` envelope with the richer SDK shape. [baseUrl] points at the Trailblaze
   * daemon's HTTP server — null on the on-device bundle path where the in-process
   * [QuickJsBridge] replaces HTTP as the callback transport. [invocationId] is per-call;
   * correlates back to a [JsScriptingInvocationRegistry] entry. [runtime] tags the execution
   * environment so the TS SDK can pick the right callback transport; [RUNTIME_ONDEVICE] for
   * the bundle runtime, null (omitted from the JSON) for subprocess / daemon paths.
   *
   * At least one of [baseUrl] or [runtime] must be non-null — otherwise the TS SDK has no
   * way to dispatch `client.callTool(…)` from inside a handler. Enforced via [require] so a
   * caller that forgets to supply either surfaces immediately, rather than the TS side
   * throwing with a misleading "no baseUrl / no runtime" message several frames down.
   */
  fun buildMetaTrailblaze(
    context: TrailblazeToolExecutionContext,
    baseUrl: String?,
    sessionId: SessionId,
    invocationId: String,
    runtime: String? = null,
  ): JsonObject = buildMetaTrailblaze(
    memory = context.memory,
    device = context.trailblazeDeviceInfo,
    baseUrl = baseUrl,
    sessionId = sessionId,
    invocationId = invocationId,
    runtime = runtime,
  )

  /** Lower-level entry point so tests can assemble the envelope from parts. */
  fun buildMetaTrailblaze(
    memory: AgentMemory,
    device: TrailblazeDeviceInfo,
    baseUrl: String?,
    sessionId: SessionId,
    invocationId: String,
    runtime: String? = null,
  ): JsonObject {
    require(baseUrl != null || runtime != null) {
      "TrailblazeContextEnvelope.buildMetaTrailblaze: at least one of baseUrl / runtime must be " +
        "non-null — otherwise client.callTool has no way to dispatch from the TS handler."
    }
    return buildJsonObject {
      // Emit the tagged fields only when populated. The TS SDK's `fromMeta` treats missing
      // fields as the pre-migration default (HTTP path for baseUrl; subprocess runtime for
      // absent runtime), so an envelope from an older daemon without a runtime tag still
      // works unchanged.
      if (baseUrl != null) put("baseUrl", baseUrl)
      if (runtime != null) put("runtime", runtime)
      put("sessionId", sessionId.value)
      put("invocationId", invocationId)
      putDeviceObject(device)
      putJsonObject("memory") {
        memory.variables.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
      }
    }
  }

  /**
   * Shared device-block writer so the legacy arg envelope and the `_meta.trailblaze` envelope
   * stay structurally identical — drift between them would produce confusing bugs for authors
   * migrating off the arg-key path.
   */
  private fun JsonObjectBuilder.putDeviceObject(device: TrailblazeDeviceInfo) {
    putJsonObject("device") {
      // Lowercase — the envelope is a TS-consumed contract, and the SHOUTY_CASE of Kotlin's
      // enum name reads awkwardly as a literal union type ("ios" | "android" | "web" is the
      // standard TS convention). Intentionally distinct from the `TRAILBLAZE_DEVICE_PLATFORM`
      // env var (uppercase — that's a separate already-shipped contract) and from
      // `trailblaze/supportedPlatforms` tool-metadata filter values (also uppercase).
      put("platform", device.platform.name.lowercase())
      put("widthPixels", device.widthPixels)
      put("heightPixels", device.heightPixels)
      put("driverType", device.trailblazeDriverType.yamlKey)
    }
  }
}
