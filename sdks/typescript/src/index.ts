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
export { z } from "zod";

export { fromMeta, type TrailblazeContext, type TrailblazeDevice } from "./context.js";
export type { TrailblazeCallToolResult, TrailblazeClient, TrailblazeToolMap } from "./client.js";

// Side-effect import — pulls in the vendored built-in-tool bindings so authors get
// autocomplete / type-checking on framework tools (`tapOnElementWithText`, `inputText`,
// etc.) the moment they import anything from `@trailblaze/scripting`. The imported file is
// pure declaration merging on `TrailblazeToolMap`; no runtime values are added.
import "./built-in-tools.js";
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
