// FULL / subprocess profile entry for `@trailblaze/scripting` — the complete MCP runtime
// surface. This is what the bundler aliases `@trailblaze/scripting` to for any tool that
// declares `runtime: subprocess`, and the surface `index.ts` re-exports as the package's
// authoring/type surface.
//
// Unlike the slim in-process profile (`./in-process.js`), this entry pulls in the full runtime:
// `./tool.js` carries the ajv instance + zod + the imperative `tool(name, spec, handler)` form,
// and `./run.js` carries the `@modelcontextprotocol/sdk` stdio server. The result is the heavy
// (~1 MB) bundle — built ONLY for subprocess tools that actually need the MCP server.

import { run } from "./run.js";
import { tool } from "./tool.js";

/**
 * Namespace bundle authors import as `trailblaze` on the subprocess path. `tool` is the full
 * dispatcher (typed + imperative forms, ajv-validated); `run` starts the MCP stdio server.
 */
export const trailblaze = {
  tool,
  run,
};

export { run, tool };

// Re-export the runtime-relevant authoring + spec types so subprocess tool authors get the full
// type surface from this entry directly.
export type { RunOptions } from "./run.js";
export type {
  TrailblazeToolHandler,
  TrailblazeToolResult,
  TrailblazeToolSpec,
  TrailblazeTypedToolSpec,
  TrailheadSpec,
  ToolContext,
  EmptyInput,
  TypedToolDefinition,
} from "./tool.js";
