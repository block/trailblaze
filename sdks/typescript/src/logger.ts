// Author-facing logger surface attached to `ctx.logger`. Removes the per-tool boilerplate of
// reaching for `console.error` with a hand-rolled prefix every time a scripted tool wants to
// emit a diagnostic line.
//
// Implementation strategy: log every call to BOTH paths so the tool gets useful output no
// matter how the host or subprocess is wired up.
//
//   1. Structured (preferred): MCP `notifications/message` via `server.sendLoggingMessage(...)`.
//      The host's `McpSubprocessSession.connect` registers a notification handler that routes
//      these into the host `Console` abstraction (which honors stdout/stderr redirect, quiet
//      mode, and any other Console-wide config). This is what shows up in daemon stdout and
//      session logs.
//
//   2. Subprocess-local fallback: write to stderr with a `[toolName] [level] message` prefix.
//      Acts as a safety net when the host hasn't (or can't) wire the notification handler
//      — e.g. when authors invoke the script directly with `bun run` for ad-hoc debugging.
//      Always stderr (never stdout) because the MCP transport reserves stdout for protocol
//      frames — see `installConsoleLogStdoutGuard` in `run.ts`.
//
// On-device / test contexts that don't have a live MCP server fall back to a no-op logger
// (see [noopLogger]) so authors can write `ctx.logger.info(...)` everywhere without checking
// whether the logger is "live."

import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";

/**
 * Severity levels surfaced to authors. Maps onto MCP's `LoggingMessageNotification` `level`
 * field — we collapse `notice`/`critical`/`alert`/`emergency` from the protocol because
 * scripted tools rarely need finer-grained severity than these four.
 */
export type TrailblazeLogLevel = "debug" | "info" | "warn" | "error";

/**
 * Author-facing logger handed to scripted tools as `ctx.logger`. Methods are fire-and-forget;
 * a failure to deliver the structured notification never throws into the calling tool.
 *
 * The optional `fields` argument is serialized into the MCP notification's `data.fields` and
 * appended to the stderr line as JSON — gives authors a path to structured logging when the
 * host wires it up, without forcing a different API shape today.
 *
 * @example
 *   ctx.logger.info("cache hit", { path: cachePath });
 *   ctx.logger.warn("session stale, invalidating", { reason });
 *   ctx.logger.error("merchant lookup failed", { key: resolvedKey });
 */
export interface TrailblazeLogger {
  debug(message: string, fields?: Record<string, unknown>): void;
  info(message: string, fields?: Record<string, unknown>): void;
  warn(message: string, fields?: Record<string, unknown>): void;
  error(message: string, fields?: Record<string, unknown>): void;
}

/**
 * Builds a logger that emits MCP `notifications/message` via [server] AND mirrors to stderr
 * with a `[toolName]` prefix. Use this when constructing a `TrailblazeContext` from a live
 * tool-invocation handler — the server reference is what authorizes the structured-log path.
 */
export function createLogger(server: McpServer, toolName: string): TrailblazeLogger {
  const emit = (
    level: TrailblazeLogLevel,
    message: string,
    fields?: Record<string, unknown>,
  ): void => {
    // 1. Structured: fire-and-forget MCP notification. The .catch swallows any transport
    //    error so logging can't break a tool's happy path. `level: "warning"` matches MCP's
    //    enum naming; we expose the friendlier `warn` to authors.
    const mcpLevel = level === "warn" ? "warning" : level;
    const data: unknown = fields ? { message, fields } : message;
    void server
      .sendLoggingMessage({ level: mcpLevel, data, logger: toolName })
      .catch(() => {
        // best-effort
      });

    // 2. Immediate stderr visibility. Always stderr — stdout belongs to the MCP frame stream.
    const prefix = `[${toolName}] [${level}] ${message}`;
    const suffix = fields ? " " + safeStringify(fields) : "";
    // eslint-disable-next-line no-console
    console.error(prefix + suffix);
  };

  return {
    debug: (message, fields) => emit("debug", message, fields),
    info: (message, fields) => emit("info", message, fields),
    warn: (message, fields) => emit("warn", message, fields),
    error: (message, fields) => emit("error", message, fields),
  };
}

/**
 * Drop-in logger that silently discards everything. Used when [fromMeta] is invoked outside
 * a live tool-handler context (on-device QuickJS path, unit tests) so authors don't have to
 * null-check `ctx.logger` before calling it.
 */
export const noopLogger: TrailblazeLogger = {
  debug: () => {},
  info: () => {},
  warn: () => {},
  error: () => {},
};

function safeStringify(value: unknown): string {
  try {
    return JSON.stringify(value);
  } catch {
    return String(value);
  }
}
