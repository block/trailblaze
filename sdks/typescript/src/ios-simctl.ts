// `simctlOrThrow`: `ctx.tools.ios_simctl` for callers that treat any failure as fatal. The tool
// itself returns a non-zero exit as a result, so a caller that probes ("is this app running?") can
// read `exitCode`; most callers just want a throw, and this is it.

import type { IosSimctlArgs } from "./built-in-tools.js";
import type { IosSimctlResult } from "./generated/built-in-tool-results.js";
import { ToolError } from "./tool-error.js";

/** Anything with an `ios_simctl` tool method: `ctx.tools`, or a test double. */
export interface IosSimctlTools {
  ios_simctl(args: IosSimctlArgs): Promise<IosSimctlResult>;
}

/**
 * Runs `ios_simctl` and returns its result, throwing a {@link ToolError} when simctl exits non-zero
 * or times out. The error's `data` is `{ code: "simctl_failed", argv, exitCode, stderr, timedOut }`.
 *
 * ```ts
 * const { stdout } = await simctlOrThrow(ctx.tools, ["get_app_container", "booted", appId, "data"]);
 * await simctlOrThrow(ctx.tools, ["push", "booted", appId], { stdin: JSON.stringify(payload) });
 * ```
 */
export async function simctlOrThrow(
  tools: IosSimctlTools,
  argv: string[],
  options: Omit<IosSimctlArgs, "argv"> = {},
): Promise<IosSimctlResult> {
  const result = await tools.ios_simctl({ ...options, argv });
  if (result.exitCode === 0 && !result.timedOut) return result;
  const what = `simctl ${argv[0] ?? ""}`.trim();
  const why = result.timedOut ? "timed out" : `exited ${result.exitCode}`;
  const detail = result.stderr.trim();
  throw new ToolError(`${what} ${why}${detail ? `: ${detail}` : "."}`, {
    data: { code: "simctl_failed", argv, exitCode: result.exitCode, stderr: result.stderr, timedOut: result.timedOut },
  });
}
