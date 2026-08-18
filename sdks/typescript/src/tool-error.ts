// ToolError lives in its own dependency-free module (not tool.ts) so the in-process entry
// (`in-process.ts`) can re-export it without dragging tool.ts's module graph — MCP SDK, ajv —
// into KB-scale on-device bundles.

/**
 * Cross-realm marker for [ToolError]. `instanceof` breaks the moment two copies of this
 * module exist (esbuild-bundled on-device tool vs the SDK inside the subprocess, or dual
 * CJS/ESM loads in one bun process), so detection goes through `Symbol.for` — same global
 * symbol registry entry in every copy within an engine. The QuickJS host's JS-side catch
 * (`QuickJsToolHost.kt`) matches the same registry key when lifting `data` on-device.
 */
const TOOL_ERROR_MARKER = Symbol.for("trailblaze.ToolError");

/**
 * An error a tool throws to attach a STRUCTURED payload to its failure, alongside the
 * human-readable message. The catch paths (subprocess: `registerPendingTools` in tool.ts;
 * on-device: the QuickJS host's dispatch catch) lift [data] onto the error envelope's
 * `structuredContent`, which the Kotlin host threads through opaquely —
 * `TrailblazeToolResult.Error.ExceptionThrown.structuredPayload` → the session log's
 * `errorPayload` → (for a trailhead failure) the report row's `failure_payload` /
 * `failure_code`. Trailblaze never interprets the payload; the repo whose tool throws it
 * owns the schema. Report rows lift `failure_code` from a top-level string `code` member
 * when the payload is an object, so shape payloads as `{ code: "...", ... }` when a
 * machine-readable class is the point.
 *
 * A plain `throw new Error(...)` keeps working exactly as before — payloads are opt-in.
 */
export class ToolError extends Error {
  readonly data?: unknown;

  constructor(message: string, opts?: { data?: unknown; cause?: unknown }) {
    // Manual `cause` assignment — the SDK's TS lib target predates the two-arg
    // `Error(message, { cause })` constructor, but every runtime we bundle for
    // carries the property just fine.
    super(message);
    this.name = "ToolError";
    this.data = opts?.data;
    if (opts?.cause !== undefined) {
      (this as { cause?: unknown }).cause = opts.cause;
    }
    Object.defineProperty(this, TOOL_ERROR_MARKER, { value: true });
  }
}

/**
 * The [ToolError] `data` of a thrown value, or undefined for everything else. Marker-based
 * (see [TOOL_ERROR_MARKER]) and access-hardened like the rest of the catch path: a hostile
 * object with a throwing marker/`data` getter degrades to "no payload", never a lost envelope.
 */
export function toolErrorData(e: unknown): unknown {
  try {
    if (
      typeof e === "object" && e !== null &&
      (e as Record<PropertyKey, unknown>)[TOOL_ERROR_MARKER] === true
    ) {
      return (e as ToolError).data;
    }
  } catch {
    // Throwing marker/data accessor — treat as payload-less.
  }
  return undefined;
}
