// Typed reader for the Trailblaze `_meta.trailblaze` envelope injected onto every
// `tools/call` request. Mirrors the Kotlin `TrailblazeContextEnvelope.buildMetaTrailblaze`
// shape exactly — keep these in sync when the Kotlin side changes.
//
// The host dual-writes: `_meta.trailblaze` (read here, used by the SDK) AND a legacy
// `_trailblazeContext` reserved arg (read directly by the pre-SDK raw-MCP examples). New tools
// should only ever read `_meta.trailblaze` via this helper.
//
// ─── Sync coupling ──────────────────────────────────────────────────────────────────────────
// This file is hand-maintained, NOT generated. There is no codegen pipeline keeping it in sync
// with the Kotlin side, so any change to either side must be reflected here manually:
//
//   * Data fields (e.g. `TrailblazeTarget.appIds`, `appId`, etc.) come from the Kotlin
//     `TrailblazeContextEnvelope` writer — search for `buildMetaTrailblaze` /
//     `QuickJsToolEnvelopes`.
//   * Method members (e.g. `TrailblazeTarget.resolveAppId`, `resolveBaseUrl`) are NOT
//     serialized — they're injected onto the deserialized `__ctx.target` object inside
//     QuickJS at dispatch time. The injection lives in `QuickJsToolHost.kt`'s
//     `connectInternal()` script. If you add a new method here, you MUST also add the
//     matching JS injection there, or `ctx.target.<newMethod>(...)` will be undefined at
//     runtime even though TypeScript thinks it exists.
//
// When `WorkspaceClientDtsGenerator` is extended to cover this surface (see
// `docs/scripted_tools.md` follow-up notes), this comment can be deleted and the
// hand-sync requirement goes away.
// ────────────────────────────────────────────────────────────────────────────────────────────

import { noopLogger, type TrailblazeLogger } from "./logger.js";
import {
  createMemory,
  META_KEY_MEMORY,
  META_KEY_TRAILBLAZE,
  type TrailblazeMemory,
} from "./memory.js";
export type { TrailblazeLogger, TrailblazeLogLevel } from "./logger.js";
export type { TrailblazeMemory } from "./memory.js";

export interface TrailblazeDevice {
  /**
   * Coarse platform; useful for branching in cross-platform tools. Lowercase by convention on
   * the envelope wire (`"ios" | "android" | "web"`) — distinct from the uppercase
   * `TRAILBLAZE_DEVICE_PLATFORM` env var and the uppercase `trailblaze/supportedPlatforms`
   * filter values, which are separate contracts.
   */
  platform: "ios" | "android" | "web";
  widthPixels: number;
  heightPixels: number;
  /**
   * The session's driver (`TrailblazeDriverType.yamlKey` on the Kotlin side), e.g.
   * `"android-ondevice-accessibility"`. Carried on the MCP/subprocess envelope (this `fromMeta`
   * path).
   *
   * ⚠️ The **on-device QuickJS** path (`runtime: inProcess`) injects a different device shape
   * (`QuickJsDeviceContext`) that carries the same value under [driver] instead — see that field.
   * A tool that must branch on the driver should read EITHER, e.g.
   * `ctx.device?.driverType ?? ctx.device?.driver`, so it works on both dispatch paths.
   */
  driverType?: string;
  /**
   * The session's driver yamlKey as carried by the **on-device QuickJS** envelope
   * (`QuickJsDeviceContext.driver`, populated from `TrailblazeDriverType.yamlKey`). Present only on
   * the in-process bundle path; on the MCP/subprocess path the same value lives in [driverType].
   * Read both when branching on the driver (the in-process path is the one most mobile tools
   * actually run under).
   */
  driver?: string;
}

/**
 * The session's resolved target — the trailmap manifest's `target.platforms.<platform>`
 * data after the framework has consulted the connected device for which candidate
 * to actually use.
 *
 * Populated on both the in-process QuickJS scripting path (`:trailblaze-scripting-bundle`,
 * via `QuickJsToolEnvelopes`) and the MCP-subprocess path (via `TrailblazeContextEnvelope`'s
 * `target` block). Absent only when the host session has no target (web-only sessions,
 * scratch tools, unit-test fixtures) or when the daemon predates the `target` field —
 * optional-chain (`ctx.target?.resolveAppId(...)`) so the same scripted tool body works
 * either way.
 *
 * `resolveAppId` and `resolveBaseUrl` are **methods**, not data — injected onto the
 * deserialized ctx object by `QuickJsToolHost` after JSON deserialization (since
 * methods can't survive JSON round-trips). They consult the target's data fields
 * (`appId` / `appIds` / `resolvedBaseUrl` / `baseUrls`) and apply a fixed
 * priority order — see each method's kdoc.
 */
export interface TrailblazeTarget {
  /** Trailmap-defined target id (e.g. `"clock"`, `"wikipedia"`, `"square"`). */
  id: string;
  /** Human-readable display name from the trailmap manifest's `target.display_name`. */
  displayName?: string;

  /**
   * All Android/iOS app id candidates declared in the manifest's
   * `target.platforms.<android|ios>.app_ids:` for this session's platform. Order is
   * preserved from the manifest so `appIds[0]` is the canonical "first declared".
   * Empty array when the target's current-platform section has no `app_ids:`.
   */
  appIds: string[];

  /**
   * Framework-resolved app id — picked at session start by intersecting [appIds]
   * against the set of apps actually installed on the connected device. Undefined
   * when no candidate was installed at session start (or when [appIds] is empty).
   * Most well-configured trailmaps running on a populated device will have this set
   * and authors should usually consume via [resolveAppId].
   */
  appId?: string;

  /**
   * Future: all web base URL candidates declared in the manifest's
   * `target.platforms.web.base_urls:` (mirrors `app_ids:` for android/ios).
   *
   * Currently empty / undefined — the manifest schema field hasn't landed yet.
   * The method [resolveBaseUrl] is wired and will start picking up real values
   * once the framework starts emitting this data. Authors writing web tools
   * today can call `resolveBaseUrl({ defaultBaseUrl: "..." })` and rely on the
   * caller-default for now; future framework upgrades will transparently take
   * over without source changes.
   */
  baseUrls?: string[];

  /**
   * Future: framework-resolved base URL — picked at session start. See [baseUrls]
   * for the rollout status.
   */
  resolvedBaseUrl?: string;

  /**
   * Resolves the Android/iOS app id to use, applying the priority:
   *
   *  1. `this.appId` (framework-resolved) — what callers will hit ~always
   *     in well-configured trailmaps running on a device with one of the candidates
   *     installed.
   *  2. `this.appIds[0]` (first declared candidate) — fallback when the framework
   *     couldn't resolve anyone (e.g. session started before any candidate was
   *     installed). Lets the launch attempt at least try the canonical id.
   *  3. `options.defaultAppId` (caller-supplied) — final fallback for non-target-
   *     aware contexts or targets with empty `app_ids:`.
   *
   * Returns `undefined` if all three layers miss. Authors typically check the
   * return value and throw a tool-specific error so the failure mode is visible
   * in trail logs.
   *
   * @example
   *   const appId = ctx.target?.resolveAppId({ defaultAppId: "com.example.app" });
   *   if (!appId) throw new Error("Could not resolve app id from ctx.target.");
   */
  resolveAppId(options?: { defaultAppId?: string }): string | undefined;

  /**
   * Resolves the web base URL to use, applying the same priority shape as
   * [resolveAppId] but reading from `this.resolvedBaseUrl` and `this.baseUrls`.
   *
   * Note: until the framework wires `target.platforms.web.base_urls:` into the
   * trailmap manifest schema, the data layers are empty and this method falls
   * through to `options.defaultBaseUrl` every time. Authors writing web tools
   * today can rely on the caller-default; future framework upgrades will
   * transparently start populating from the manifest without source changes.
   *
   * @example
   *   const baseUrl = ctx.target?.resolveBaseUrl({ defaultBaseUrl: "https://en.wikipedia.org" });
   *   if (!baseUrl) throw new Error("Could not resolve base URL from ctx.target.");
   */
  resolveBaseUrl(options?: { defaultBaseUrl?: string }): string | undefined;
}

export interface TrailblazeContext {
  /**
   * Daemon HTTP base URL (e.g. `http://localhost:52525`). `client.callTool()` dispatches to
   * `${baseUrl}/scripting/callback` on the host-subprocess path. Absent on the on-device
   * bundle path — there's no HTTP server in the Android instrumentation process, and the
   * SDK switches to the in-process binding instead (see [runtime]).
   */
  baseUrl?: string;

  /**
   * Execution runtime tag. Present as `"ondevice"` when the tool is running inside the
   * Android QuickJS bundle runtime (`:trailblaze-scripting-bundle` on the Kotlin side);
   * absent on the subprocess / daemon path (where [baseUrl] carries the dispatch target).
   *
   * The SDK reads this to decide how `client.callTool(…)` dispatches: `"ondevice"` →
   * in-process binding via `globalThis.__trailblazeCallback`; otherwise → HTTP fetch
   * against [baseUrl]. Authors should rarely need to branch on this directly — the
   * transport choice is the SDK's responsibility.
   */
  runtime?: "ondevice";

  /** Opaque session id — use for log correlation only, never for security. */
  sessionId: string;

  /**
   * Per-tool-call identifier. Forward this verbatim on any subsequent callback request so the
   * daemon can resolve the callback back to this invocation's live tool repo + execution
   * context. Regenerated per call; do not cache across calls.
   */
  invocationId: string;

  device: TrailblazeDevice;

  /**
   * Resolved-target descriptor. Populated whenever the host session has a target
   * configured (the trailmap manifest's `target:` block resolved against the connected
   * device's installed apps) — both the MCP-subprocess path and the in-process
   * QuickJS scripting path emit it. Absent for sessions with no target (web-only
   * scratch tools, unit-test fixtures) and for envelopes from older daemons that
   * predate the field.
   *
   * Optional-chain (`ctx.target?.resolveAppId(...)`) — authors writing scripted
   * tools that should also work outside a target-aware session need to handle
   * the undefined case. See [TrailblazeTarget] for the shape and the resolver
   * methods.
   */
  target?: TrailblazeTarget;

  /**
   * Per-tool-call agent memory surface. Reads consult the host's snapshot captured at
   * envelope build time, plus any writes made earlier in this handler invocation
   * (read-your-own-writes). Writes are buffered locally and flushed back to the host on
   * a successful tool return via `_meta.trailblaze.memoryDelta`; a handler that throws
   * produces no delta and the host's memory is left unchanged. See [TrailblazeMemory]
   * for the full surface and [memory.ts] for the wire-shape rationale.
   */
  memory: TrailblazeMemory;

  /**
   * Author-facing logger. Always present on the surface so scripted tools can write
   * `ctx.logger.info("...")` without null-checking — `fromMeta` defaults to a no-op logger
   * for paths that don't have a live MCP server (on-device QuickJS, unit tests). The
   * subprocess tool-invocation handler in `registerPendingTools` replaces this with a
   * server-backed logger that emits MCP `notifications/message` (routed by the host into
   * `Console` — see `McpSubprocessSession.connect`) and mirrors to stderr as a fallback.
   */
  logger: TrailblazeLogger;
}

/** Platform values the host will emit — anything else means envelope drift. */
const VALID_PLATFORMS: ReadonlySet<TrailblazeDevice["platform"]> = new Set(["ios", "android", "web"]);

/**
 * Extracts [TrailblazeContext] from an MCP `tools/call` request's `_meta` object. Returns
 * undefined when the envelope isn't present or is structurally invalid — the tool handler
 * should then branch (refuse to run, degrade, or log) rather than run against a silently
 * fabricated default context.
 *
 * **Strictness rationale.** An earlier revision defaulted missing fields (platform →
 * `"android"`, ids → `""`) so the handler always received a context. That silently masked
 * envelope drift: an iOS tool author would see `"android"` on a corrupt envelope and branch
 * wrong. Returning undefined surfaces the problem immediately and matches the "envelope
 * missing" case — which is already a branch the caller must handle.
 *
 * The TS MCP SDK surfaces `_meta` as `request.params._meta`; pass that value in.
 *
 * The optional [logger] is attached as `ctx.logger`. Callers in a live tool-invocation
 * handler should pass a server-backed logger (see `createLogger` in `./logger.js`); other
 * callers (on-device QuickJS bridge, unit tests) can omit it and accept the no-op default.
 */
export function fromMeta(meta: unknown, logger?: TrailblazeLogger): TrailblazeContext | undefined {
  if (typeof meta !== "object" || meta === null) return undefined;
  const bag = meta as Record<string, unknown>;
  const envelope = bag[META_KEY_TRAILBLAZE];
  if (typeof envelope !== "object" || envelope === null) return undefined;
  const tb = envelope as Record<string, unknown>;

  // sessionId / invocationId are load-bearing for the callback channel — missing them
  // means we can't forward a callback, so this envelope is unusable. baseUrl is optional so
  // a daemon without a live HTTP server can still inject the envelope; only sessionId +
  // invocationId are required for a valid context.
  const sessionId = tb["sessionId"];
  const invocationId = tb["invocationId"];
  if (typeof sessionId !== "string" || typeof invocationId !== "string") return undefined;

  const baseUrl = typeof tb["baseUrl"] === "string" ? (tb["baseUrl"] as string) : undefined;

  // Strict allow-list for runtime tags. An envelope from a future Kotlin-side runtime we don't
  // recognize should be treated as absent (fall back to HTTP fetch) rather than letting an
  // unknown string leak through — any callTool dispatch that depends on it will surface a
  // clearer error than "unknown transport mode".
  const runtimeRaw = tb["runtime"];
  const runtime: "ondevice" | undefined = runtimeRaw === "ondevice" ? "ondevice" : undefined;

  const deviceBag = tb["device"];
  if (typeof deviceBag !== "object" || deviceBag === null) return undefined;
  const deviceRecord = deviceBag as Record<string, unknown>;
  const platformRaw = deviceRecord["platform"];
  if (typeof platformRaw !== "string" || !VALID_PLATFORMS.has(platformRaw as TrailblazeDevice["platform"])) {
    return undefined;
  }
  const widthPixels = deviceRecord["widthPixels"];
  const heightPixels = deviceRecord["heightPixels"];
  const driverType = deviceRecord["driverType"];
  if (
    typeof widthPixels !== "number" ||
    typeof heightPixels !== "number" ||
    typeof driverType !== "string"
  ) {
    return undefined;
  }

  // `_meta.trailblaze.memory` is a `Record<string, string>` snapshot of the host's
  // `AgentMemory.variables` at envelope build time. Wrap it in a `TrailblazeMemory` so
  // the handler sees the full 8-method surface — `createMemory` filters out non-string
  // values defensively in case a producer-side bug leaks a non-string into the snapshot.
  const memoryBag = tb[META_KEY_MEMORY];
  const memorySnapshot: Record<string, string> | undefined =
    typeof memoryBag === "object" && memoryBag !== null
      ? (memoryBag as Record<string, string>)
      : undefined;
  const memory = createMemory(memorySnapshot);

  // Target block — absent on older daemons (and sessions with no target). Strictness mirrors
  // the device block: a malformed `target` (wrong shape, missing required keys, non-string
  // entries in appIds) is treated as "no target" rather than aborting the whole envelope.
  // A bogus `target` is recoverable — the handler can still operate without target data —
  // whereas a missing sessionId/invocationId would render callbacks unroutable.
  const target = parseTarget(tb["target"]);

  return {
    baseUrl,
    runtime,
    sessionId,
    invocationId,
    device: {
      platform: platformRaw as TrailblazeDevice["platform"],
      widthPixels,
      heightPixels,
      driverType,
    },
    target,
    memory,
    logger: logger ?? noopLogger,
  };
}

/**
 * Parses the optional `target` block out of the `_meta.trailblaze` envelope. Returns
 * `undefined` if the block is absent or structurally invalid — `id` and `appIds` are
 * the only required fields; everything else is optional and silently dropped if its
 * shape doesn't match.
 *
 * Attaches `resolveAppId` / `resolveBaseUrl` onto the returned object — implemented as
 * closures over the parsed locals (not `this`-bound methods) so destructured access like
 * `const { resolveAppId } = ctx.target` works without losing its data context. Mirrors the
 * priority order documented on the `TrailblazeTarget` interface: appId → appIds[0]
 * → caller-default. This is what makes the same `ctx.target?.resolveAppId(...)` site work
 * on both runtimes — the QuickJS host's separate injection (in
 * `QuickJsToolHost.connectInternal()`) covers the direct in-process dispatch path where
 * `ctx` doesn't pass through `fromMeta`.
 */
function parseTarget(raw: unknown): TrailblazeTarget | undefined {
  if (typeof raw !== "object" || raw === null) return undefined;
  const bag = raw as Record<string, unknown>;
  const id = bag["id"];
  const appIdsRaw = bag["appIds"];
  if (typeof id !== "string" || !Array.isArray(appIdsRaw)) return undefined;
  // Reject the whole block if any candidate is non-string — partial filtering would mask a
  // producer-side bug (a stray null leaking into the array) and let authors silently miss
  // candidates. An empty appIds array is fine — a target may legitimately declare no
  // candidates for the active platform.
  if (!appIdsRaw.every((entry): entry is string => typeof entry === "string")) return undefined;
  const appIds = appIdsRaw as string[];

  const displayName = typeof bag["displayName"] === "string" ? (bag["displayName"] as string) : undefined;
  const appId = typeof bag["appId"] === "string" ? (bag["appId"] as string) : undefined;
  const resolvedBaseUrl = typeof bag["resolvedBaseUrl"] === "string" ? (bag["resolvedBaseUrl"] as string) : undefined;
  const baseUrlsRaw = bag["baseUrls"];
  const baseUrls = Array.isArray(baseUrlsRaw) && baseUrlsRaw.every((entry): entry is string => typeof entry === "string")
    ? (baseUrlsRaw as string[])
    : undefined;

  // Closures over the parsed locals rather than `this`-bound methods. A `this.appId`
  // read would lose its binding the moment an author writes
  // `const { resolveAppId } = ctx.target` — an easy mistake and a quietly-broken resolver.
  // Capturing the locals keeps the data reachable regardless of how the function value gets
  // passed around.
  const resolveAppId = (options?: { defaultAppId?: string }): string | undefined => {
    const fromTarget = appId || appIds[0];
    if (typeof fromTarget === "string" && fromTarget.length > 0) return fromTarget;
    const fallback = options?.defaultAppId?.trim();
    return fallback && fallback.length > 0 ? fallback : undefined;
  };
  const resolveBaseUrl = (options?: { defaultBaseUrl?: string }): string | undefined => {
    const fromTarget = resolvedBaseUrl || (baseUrls && baseUrls[0]);
    if (typeof fromTarget === "string" && fromTarget.length > 0) return fromTarget;
    const fallback = options?.defaultBaseUrl?.trim();
    return fallback && fallback.length > 0 ? fallback : undefined;
  };

  const target: TrailblazeTarget = {
    id,
    displayName,
    appIds,
    appId,
    baseUrls,
    resolvedBaseUrl,
    resolveAppId,
    resolveBaseUrl,
  };
  return target;
}
