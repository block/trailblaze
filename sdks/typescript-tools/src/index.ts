// `@trailblaze/tools` — the entire on-device-compatible authoring contract.
//
// Authors write:
//
//   import { trailblaze } from "@trailblaze/tools";
//
//   trailblaze.tool("greet", { description: "Say hi" }, async (args, ctx) => {
//     return { content: [{ type: "text", text: `hello ${args.name}` }] };
//   });
//
// That's it. There's no `await trailblaze.run(...)` to call, no MCP server to spin up, no
// transport to connect. The Kotlin host evaluates the bundle, reads `globalThis.__trailblazeTools`
// to discover what got registered, and dispatches calls via direct QuickJS function invocation.
//
// **Why no MCP.** The runtime that consumes these tools is QuickJS embedded in a Kotlin
// process — there is no process boundary, no transport to abstract. MCP exists to bridge
// boundaries that don't exist here. See the `2026-04-30-scripted-tools-not-mcp` devlog for
// the full reasoning.
//
// **Same engine, host or device.** A tool authored against this SDK runs in QuickJS whether
// it's executing in a host-embedded engine (CLI / desktop daemon) or on an Android device.
// Same compiled JS, same registry, same dispatch path. Tools that need full Node (`child_process`,
// `node:fs`, `fetch` without polyfill) target the bun-subprocess path instead and use the
// MCP-shaped `@trailblaze/scripting` SDK there.

/**
 * Author-facing input schema. Either an empty object (no args), an object describing each
 * argument by name, or any plain object the runtime should pass through to JSON-Schema
 * validation. Intentionally a loose type — kept narrow to what authors actually write so the
 * SDK isn't tied to any specific validator.
 */
export type TrailblazeToolSpec = {
  description?: string;
  inputSchema?: Record<string, unknown>;
  outputSchema?: Record<string, unknown>;
  /** Custom metadata surfaced to the runtime — e.g., `{ "trailblaze/requiresHost": true }`. */
  _meta?: Record<string, unknown>;
};

/**
 * Tool result envelope. Mirrors the MCP-flavored `content` array shape since that's what
 * Trailblaze's tool-call response model uses internally — but does NOT pull in the MCP SDK's
 * type graph. Authors who want richer content types just write the literal object; the runtime
 * forwards `content` straight through.
 */
export type TrailblazeToolResult = {
  content: Array<{ type: "text"; text: string } | Record<string, unknown>>;
  isError?: boolean;
  structuredContent?: unknown;
};

/**
 * Per-invocation envelope handed to handlers as the `ctx` parameter. Populated by the runtime
 * before each dispatch — `undefined` outside an active session (e.g., a unit test invoking
 * the tool directly).
 *
 * Optional fields rather than a fully populated record: future runtime versions can grow this
 * shape, and handlers that ignore unknown fields stay forward-compatible.
 */
export type TrailblazeContext = {
  sessionId?: string;
  invocationId?: string;
  device?: {
    platform?: string;
    driver?: string;
  };
};

/**
 * Author handler signature.
 *
 * - `args`: arguments the caller passed (LLM tool call, another tool's `trailblaze.call`, etc.).
 * - `ctx`: per-invocation envelope, or `undefined` outside a session.
 *
 * No third `client` parameter — the SDK exposes composition via the namespace (`trailblaze.call`)
 * rather than a per-handler client object. Simplifies the type and matches "the SDK is the
 * contract" framing — the global namespace is the entry point for everything.
 */
export type TrailblazeToolHandler = (
  args: Record<string, unknown>,
  ctx: TrailblazeContext | undefined,
) => Promise<TrailblazeToolResult> | TrailblazeToolResult;

/**
 * Internal record stored in `globalThis.__trailblazeTools`. The runtime reads this map after
 * bundle evaluation to discover what got registered.
 */
type RegisteredTool = {
  name: string;
  spec: TrailblazeToolSpec;
  handler: TrailblazeToolHandler;
};

/**
 * Lazily-initialized global registry. `??=` so multiple bundles loaded into the same QuickJS
 * engine accumulate into one map (the runtime can clear it between sessions if it wants
 * isolation; here we just provide the registration surface).
 */
function registry(): Record<string, RegisteredTool> {
  const g = globalThis as unknown as { __trailblazeTools?: Record<string, RegisteredTool> };
  return (g.__trailblazeTools ??= {});
}

/**
 * Bridge to call back into Trailblaze (other tools, built-ins) from inside a handler. Backed
 * by a host-installed binding `globalThis.__trailblazeCall(name, argsJson)` that the QuickJS
 * runtime registers; the binding returns a Promise resolving to JSON-encoded result text.
 *
 * Throws if the binding isn't installed — that signals the bundle is being evaluated outside
 * a Trailblaze runtime context (e.g., a unit test that just imports the bundle without
 * spinning up a host). Better than silently returning undefined.
 */
async function callOtherTool(
  name: string,
  args: Record<string, unknown>,
): Promise<TrailblazeToolResult> {
  const g = globalThis as unknown as {
    __trailblazeCall?: (name: string, argsJson: string) => Promise<string>;
  };
  if (typeof g.__trailblazeCall !== "function") {
    throw new Error(
      "trailblaze.call: host binding `__trailblazeCall` not installed — this bundle is " +
        "running outside a Trailblaze runtime context.",
    );
  }
  const resultJson = await g.__trailblazeCall(name, JSON.stringify(args));
  // Guard against a buggy host that returns malformed JSON. `JSON.parse` would otherwise
  // throw an opaque SyntaxError with no provenance — the author handler can't tell which
  // tool failed or why. Catching here lets us surface a directed message naming the inner
  // tool, so the author sees "trailblaze.call to <name> returned malformed JSON" instead of
  // a runtime parse error from a layer they don't own.
  try {
    return JSON.parse(resultJson) as TrailblazeToolResult;
  } catch (e) {
    const message = e instanceof Error ? e.message : String(e);
    throw new Error(
      `trailblaze.call: host returned malformed JSON for tool "${name}": ${message}`,
    );
  }
}

/**
 * The single namespace authors interact with. Flat by design — `tool` to register, `call` to
 * compose. No `run`, no `connect`, no transport setup; the runtime drives everything.
 */
export const trailblaze = {
  /** Register a tool with the runtime. Idempotent — re-registering with the same name overwrites. */
  tool(name: string, spec: TrailblazeToolSpec, handler: TrailblazeToolHandler): void {
    registry()[name] = { name, spec, handler };
  },
  /** Call another tool from inside a handler. Throws if the host binding isn't installed. */
  call: callOtherTool,
};

/** Direct named exports for authors who prefer `import { tool } from "@trailblaze/tools"`. */
export const tool = trailblaze.tool;
export const call = trailblaze.call;
