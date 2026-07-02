package xyz.block.trailblaze.scripting.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.applyScriptedToolMemoryDelta
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext

/**
 * Builders for the two envelopes Trailblaze injects into every `tools/call`:
 *
 *  1. **Legacy arg envelope** under the reserved `_trailblazeContext` argument key — shape
 *     frozen by the conventions devlog (§ 2): `{ memory, device }`. Existing raw-SDK tools
 *     (e.g. `examples/android-sample-app/trails/config/trailmaps/sampleapp/tools/mcp/tools.ts`) read from
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
 *   target?: {
 *     id: string;
 *     displayName?: string;
 *     appIds: string[];
 *     appId?: string;
 *     baseUrls?: string[];
 *     resolvedBaseUrl?: string;
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

  // Wire-key constant for the `_meta.trailblaze.memory` sub-field (the inbound snapshot this
  // module builds). Mirrored on the TS side as `META_KEY_MEMORY` in
  // [./sdks/typescript/src/memory.ts]; a rename must happen on both sides in lockstep or the
  // inbound parser silently drifts.
  //
  // The sibling OUTBOUND keys — `memoryDelta` / `memoryDeletions` — used to live here too, but
  // this module never reads them directly anymore: [applyResultMemoryDelta] below delegates
  // entirely to [xyz.block.trailblaze.applyScriptedToolMemoryDelta] in `:trailblaze-models`,
  // which owns those two constants as the single Kotlin-side declaration (see
  // `ScriptedToolMemoryDelta.kt`) — both the subprocess path (this module) and the on-device
  // QuickJS path (`:trailblaze-quickjs-tools`, which can't depend on this module) read them from
  // there. Keeping a second copy here would have been dead code from the day it stopped being
  // read, and a real drift risk the day someone renamed one copy and not the other.
  internal const val META_KEY_MEMORY: String = "memory"

  /**
   * Runtime tag stamped onto `_meta.trailblaze.runtime` for invocations dispatched through
   * the on-device QuickJS bundle runtime. TS SDK consumers read this to pick the in-process
   * callback transport (`globalThis.__trailblazeCallback`) instead of HTTP fetch.
   *
   * NOTE: the in-process callback transport this tag selects is not wired yet — its host-side
   * binding installer (`QuickJsBridge`) does not exist (see the 2026-06-17 "Consolidate
   * scripted-tool surfaces" decision). Until it lands, only the subprocess / daemon path is
   * live: that callback channel is the `/scripting/callback` HTTP endpoint, which the SDK
   * dispatches to by default when [TrailblazeContext.baseUrl] is populated.
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
      putJsonObject(META_KEY_MEMORY) {
        memory.variables.forEach { (k, v) ->
          if (k !in memory.sensitiveKeys) put(k, JsonPrimitive(v))
        }
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
    target = context.resolvedTarget?.let { resolved ->
      TargetSnapshot(
        id = resolved.id,
        appIds = resolved.appIds,
        appId = context.appId,
      )
    },
  )

  /** Lower-level entry point so tests can assemble the envelope from parts. */
  fun buildMetaTrailblaze(
    memory: AgentMemory,
    device: TrailblazeDeviceInfo,
    baseUrl: String?,
    sessionId: SessionId,
    invocationId: String,
    runtime: String? = null,
    target: TargetSnapshot? = null,
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
      if (target != null) putTargetObject(target)
      putJsonObject(META_KEY_MEMORY) {
        memory.variables.forEach { (k, v) ->
          if (k !in memory.sensitiveKeys) put(k, JsonPrimitive(v))
        }
      }
    }
  }

  /**
   * Apply a tool-result memory delta to [memory]. Reads `_meta.trailblaze.memoryDelta`
   * (a `Record<String, String>` of sets) and `_meta.trailblaze.memoryDeletions` (a
   * `List<String>` of removed keys) from the [resultMeta] block emitted by the TS SDK's
   * `attachMemoryDeltaToResult`. Symmetric counterpart to [buildMetaTrailblaze]'s `memory`
   * snapshot — that one carries host-to-handler reads, this one carries
   * handler-to-host writes.
   *
   * Thin delegate to [xyz.block.trailblaze.applyScriptedToolMemoryDelta] in `:trailblaze-models`
   * — see that function's kdoc for the full apply semantics (no-op conditions, malformed-entry
   * handling, sensitive-key preservation). Kept here, rather than having
   * [xyz.block.trailblaze.scripting.subprocess.SubprocessTrailblazeTool] (this function's only
   * production caller) call the shared function directly, so the subprocess runtime's
   * envelope-building ([buildMetaTrailblaze]) and envelope-applying ([applyResultMemoryDelta])
   * halves stay paired on this one object — a caller reasoning about the subprocess wire
   * contract reads both directions from the same place.
   */
  fun applyResultMemoryDelta(memory: AgentMemory, resultMeta: JsonObject?): Boolean =
    applyScriptedToolMemoryDelta(memory, resultMeta)

  /**
   * Wire shape of the session's resolved target, projected into the `_meta.trailblaze.target`
   * block. Mirrors the TS SDK's `TrailblazeTarget` data-field surface — kept in lockstep with
   * `TrailblazeContext.target` in the TS SDK's `context.ts` (see the SDK module under
   * `sdks/typescript/src/`).
   *
   * Constructed from a [ResolvedTarget] + the device-resolved app id at envelope build time
   * — the framework already pre-computes both at session start
   * ([TrailblazeToolExecutionContext.resolvedTarget] / [TrailblazeToolExecutionContext.appId]),
   * so per-call envelope emission is a cheap projection rather than a fresh device probe.
   *
   * Optional / not-yet-wired fields ([displayName], [baseUrls], [resolvedBaseUrl]) default to
   * null and are omitted from the JSON when unset — the TS SDK treats absence and explicit
   * null identically. Adding a manifest-driven displayName / web base-url surface is a
   * forward-compat extension that won't disturb existing consumers.
   */
  data class TargetSnapshot(
    val id: String,
    val appIds: List<String>,
    val appId: String?,
    val displayName: String? = null,
    val baseUrls: List<String>? = null,
    val resolvedBaseUrl: String? = null,
  )

  /**
   * Writes the `target` block. Mirrors the device-block convention: a private writer keyed off
   * the typed snapshot so the JSON shape lives in one place and producer call sites don't drift.
   */
  private fun JsonObjectBuilder.putTargetObject(target: TargetSnapshot) {
    putJsonObject("target") {
      put("id", target.id)
      if (target.displayName != null) put("displayName", target.displayName)
      putJsonArray("appIds") {
        target.appIds.forEach { add(it) }
      }
      if (target.appId != null) put("appId", target.appId)
      if (target.baseUrls != null) {
        putJsonArray("baseUrls") {
          target.baseUrls.forEach { add(it) }
        }
      }
      if (target.resolvedBaseUrl != null) put("resolvedBaseUrl", target.resolvedBaseUrl)
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
      // standard TS convention). Distinct from the `TRAILBLAZE_DEVICE_PLATFORM` env var,
      // which stays uppercase — a separate already-shipped contract. The
      // `trailblaze/supportedPlatforms` tool-metadata filter values are case-insensitive at
      // parse time (TrailblazeToolMeta.fromJsonObject normalizes to uppercase), so authors
      // can write the same lowercase form they see in TS without surprise.
      put("platform", device.platform.name.lowercase())
      put("widthPixels", device.widthPixels)
      put("heightPixels", device.heightPixels)
      put("driverType", device.trailblazeDriverType.yamlKey)
    }
  }
}
