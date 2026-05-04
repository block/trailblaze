// `trailblaze.run()` — hides the `McpServer` + transport boilerplate so an author's toolset
// file is just `import { trailblaze } from "@trailblaze/scripting"` + a handful of
// `trailblaze.tool(...)` calls + `await trailblaze.run()` at the bottom.

import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { Transport } from "@modelcontextprotocol/sdk/shared/transport.js";

import { registerPendingTools } from "./tool.js";

export interface RunOptions {
  /**
   * Server implementation name advertised on `initialize`. Defaults to a stable string so the
   * host can recognize SDK-authored subprocesses in server-side logs. Override when you want
   * your toolset identifiable in multi-subprocess sessions.
   */
  name?: string;
  /** Advertised version. Author-owned — doesn't have to track Trailblaze's version. */
  version?: string;
}

/**
 * Redirects `console.log` onto `console.error` on the host-subprocess path, so an author
 * who writes `console.log("debugging")` in a tool handler doesn't silently corrupt the MCP
 * stdio JSON-RPC wire (stdout is reserved for protocol messages). This is the same fix
 * several community MCP SDKs apply: authors who know the convention already use
 * `console.error` and are unaffected; authors who don't know get their output in stderr
 * — captured by the host-side `StderrCapture` into `subprocess_stderr_<script>.log` —
 * instead of a broken session.
 *
 * Skipped on the on-device bundle runtime: `BundleRuntimePrelude` already installs a
 * `console` shim that routes every method through a Kotlin binding to logcat. Re-wrapping
 * would double-indirect for no benefit. We detect the bundle runtime by the presence of
 * `globalThis.__trailblazeCallback` (installed by `QuickJsBridge` before the author bundle
 * evaluates).
 *
 * Authors who actually want to write to stdout can still use `process.stdout.write(...)`
 * directly; this just protects the casual `console.log` case.
 */
function installConsoleLogStdoutGuard(): void {
  const onDeviceBundleRuntime =
    typeof (globalThis as { __trailblazeCallback?: unknown }).__trailblazeCallback === "function";
  if (onDeviceBundleRuntime) return;
  // Defensive: if a runtime is missing `console` or `console.error` (exotic embedder,
  // patched test harness), silently no-op rather than crash the subprocess at SDK boot.
  // The guard's whole purpose is to protect authors from a wire-corruption foot-gun — it
  // MUST NOT become a new crash surface of its own. Realistically Node/bun always provide
  // console.error, but the cost of being defensive here is a single type check.
  const maybeErr = (globalThis as { console?: { error?: unknown } }).console?.error;
  if (typeof maybeErr !== "function") return;
  const err = (maybeErr as (...args: unknown[]) => void).bind(console);
  console.log = (...args: unknown[]): void => {
    err(...args);
  };
}

/**
 * Global name of the in-process transport installed by the on-device bundle runtime's
 * prelude. Matches `BundleRuntimePrelude.IN_PROCESS_TRANSPORT` on the Kotlin side — a
 * rename there needs the same rename here. The prelude's transport object already shapes
 * itself to the MCP SDK's `Transport` interface (`start` / `send` / `close` / `onmessage`
 * / `onerror` / `onclose`), so `server.connect(...)` accepts it without adaptation.
 */
const IN_PROCESS_TRANSPORT_GLOBAL = "__trailblazeInProcessTransport";

/**
 * Picks the MCP transport appropriate to the runtime.
 *
 *  - **On-device** (QuickJS bundle runtime): the prelude pre-installs
 *    `globalThis.__trailblazeInProcessTransport`. No dynamic import needed; QuickJS takes
 *    the early return before the `node:process`-dependent stdio module is ever touched.
 *  - **Host** (bun/tsx/node subprocess): fall back to `StdioServerTransport`. Imported
 *    dynamically so the on-device bundle doesn't have to resolve it at bundle time — the
 *    stdio module pulls in `node:process` + `ajv`, which break `--platform=neutral`
 *    esbuild bundling and would poison the on-device artifact if imported statically.
 *
 * Author code is transport-agnostic — the same `await trailblaze.run()` works in both.
 */
async function pickTransport(): Promise<Transport> {
  const inProcess = (globalThis as Record<string, unknown>)[IN_PROCESS_TRANSPORT_GLOBAL];
  if (inProcess != null) {
    // Structural check — an object with the three required Transport methods is enough to
    // trust. We don't validate onmessage/onerror/onclose because the SDK installs those as
    // setters, not pre-existing fields; a stricter check here would reject valid transports.
    const t = inProcess as Partial<Transport>;
    if (typeof t.start === "function" && typeof t.send === "function" && typeof t.close === "function") {
      return t as Transport;
    }
    throw new Error(
      `globalThis.${IN_PROCESS_TRANSPORT_GLOBAL} is present but doesn't match the MCP Transport ` +
        `interface (start/send/close required). This usually means a Trailblaze/MCP SDK version ` +
        `mismatch — report as a bug.`,
    );
  }
  const { StdioServerTransport } = await import("@modelcontextprotocol/sdk/server/stdio.js");
  return new StdioServerTransport();
}

/**
 * Connects the MCP server and registers every tool declared via [tool]. On host, binds
 * stdio; on-device, binds the pre-installed in-process transport. Resolves when the
 * handshake has completed; the runtime then handles requests until stdin closes (host) or
 * the QuickJS session ends (on-device).
 */
export async function run(options: RunOptions = {}): Promise<void> {
  installConsoleLogStdoutGuard();
  const server = new McpServer(
    { name: options.name ?? "trailblaze-scripting-sdk", version: options.version ?? "0.1.0" },
    { capabilities: { tools: {} } },
  );
  registerPendingTools(server);
  await server.connect(await pickTransport());
}
