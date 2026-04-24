// Typed reader for the Trailblaze `_meta.trailblaze` envelope injected onto every
// `tools/call` request. Mirrors the Kotlin `TrailblazeContextEnvelope.buildMetaTrailblaze`
// shape exactly — keep these in sync when the Kotlin side changes.
//
// The host dual-writes: `_meta.trailblaze` (read here, used by the SDK) AND a legacy
// `_trailblazeContext` reserved arg (read directly by the pre-SDK raw-MCP examples). New tools
// should only ever read `_meta.trailblaze` via this helper.

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
