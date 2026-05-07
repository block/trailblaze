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
//   * Data fields (e.g. `TrailblazeTarget.appIds`, `resolvedAppId`, etc.) come from the Kotlin
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
  /** The session's driver (`TrailblazeDriverType.yamlKey` on the Kotlin side). */
  driverType: string;
}

/**
 * The session's resolved target — the pack manifest's `target.platforms.<platform>`
 * data after the framework has consulted the connected device for which candidate
 * to actually use.
 *
 * **Only populated on the in-process QuickJS scripting path** (`:trailblaze-scripting-bundle`
 * on Kotlin side, surfaced via `QuickJsToolEnvelopes`). The MCP-subprocess path's
 * envelope doesn't carry target data — `ctx.target` will be `undefined` there.
 * Optional-chain (`ctx.target?.resolveAppId(...)`) so the same scripted tool body
 * works on both paths.
 *
 * `resolveAppId` and `resolveBaseUrl` are **methods**, not data — injected onto the
 * deserialized ctx object by `QuickJsToolHost` after JSON deserialization (since
 * methods can't survive JSON round-trips). They consult the target's data fields
 * (`resolvedAppId` / `appIds` / `resolvedBaseUrl` / `baseUrls`) and apply a fixed
 * priority order — see each method's kdoc.
 */
export interface TrailblazeTarget {
  /** Pack-defined target id (e.g. `"clock"`, `"wikipedia"`, `"square"`). */
  id: string;
  /** Human-readable display name from the pack manifest's `target.display_name`. */
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
   * Most well-configured packs running on a populated device will have this set
   * and authors should usually consume via [resolveAppId].
   */
  resolvedAppId?: string;

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
   *  1. `this.resolvedAppId` (framework-resolved) — what callers will hit ~always
   *     in well-configured packs running on a device with one of the candidates
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
   * pack manifest schema, the data layers are empty and this method falls
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
   * Resolved-target descriptor — only populated on the in-process QuickJS scripting
   * path (the `:trailblaze-scripting-bundle` runtime). The MCP-subprocess path's
   * envelope doesn't carry target data, so on that path this is `undefined`.
   *
   * Authors writing scripted tools that should work on both paths should
   * optional-chain (`ctx.target?.resolveAppId(...)`) and handle the undefined case.
   * See [TrailblazeTarget] for the shape and the resolver methods.
   */
  target?: TrailblazeTarget;

  /**
   * Agent memory snapshot. Values are strings on the Kotlin side today; typed as `unknown` here
   * so an author-facing upgrade to richer memory types doesn't break the SDK surface.
   */
  memory: Record<string, unknown>;
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
 */
export function fromMeta(meta: unknown): TrailblazeContext | undefined {
  if (typeof meta !== "object" || meta === null) return undefined;
  const bag = meta as Record<string, unknown>;
  const envelope = bag["trailblaze"];
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

  const memoryBag = tb["memory"];
  const memory =
    typeof memoryBag === "object" && memoryBag !== null
      ? (memoryBag as Record<string, unknown>)
      : {};

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
    memory,
  };
}
