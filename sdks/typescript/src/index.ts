// Public entry point for `@trailblaze/scripting`. Authors write:
//
//   import { trailblaze } from "@trailblaze/scripting";
//
//   trailblaze.tool("myTool", { description: "..." }, async (args, ctx) => {
//     // ctx is TrailblazeContext | undefined
//     return { content: [{ type: "text", text: "hello" }] };
//   });
//
//   await trailblaze.run();

import { run, type RunOptions } from "./run.js";
import { tool, type TrailblazeToolHandler, type TrailblazeToolSpec } from "./tool.js";

export { fromMeta, type TrailblazeContext, type TrailblazeDevice } from "./context.js";
export type { TrailblazeCallToolResult, TrailblazeClient } from "./client.js";
export type { RunOptions, TrailblazeToolHandler, TrailblazeToolSpec };

/**
 * Namespace bundle authors import as `trailblaze`. Flat entry points (`tool`, `run`) also
 * export individually for anyone who prefers named imports over the namespace.
 *
 * Test-only helpers (e.g., `_clearPendingTools` in `./tool.js`) are deliberately NOT
 * re-exported here. The SDK's public surface is `tool`, `run`, `fromMeta`, and the type
 * exports above; tests that need internals import from the module's relative path directly.
 */
export const trailblaze = {
  tool,
  run,
};

export { run, tool };
