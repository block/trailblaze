// `trailblaze.run()` — hides the `McpServer` + `StdioServerTransport` boilerplate so an
// author's toolset file is just `import { trailblaze } from "@trailblaze/scripting"` + a
// handful of `trailblaze.tool(...)` calls + `await trailblaze.run()` at the bottom.

import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";

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
 * Connects the MCP server to stdio and registers every tool declared via
 * [tool]. Resolves when the transport is connected and the handshake has completed; the
 * subprocess then runs indefinitely handling requests until stdin closes.
 */
export async function run(options: RunOptions = {}): Promise<void> {
  installConsoleLogStdoutGuard();
  const server = new McpServer(
    { name: options.name ?? "trailblaze-scripting-sdk", version: options.version ?? "0.1.0" },
    { capabilities: { tools: {} } },
  );
  registerPendingTools(server);
  const transport = new StdioServerTransport();
  await server.connect(transport);
}
