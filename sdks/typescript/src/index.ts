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
import {
  tool,
  type TrailblazeToolHandler,
  type TrailblazeToolSpec,
  type TrailblazeTypedToolSpec,
} from "./tool.js";
export { z } from "zod";

export {
  fromMeta,
  type TrailblazeContext,
  type TrailblazeDevice,
  type TrailblazeLogger,
  type TrailblazeLogLevel,
  type TrailblazeMemory,
  type TrailblazeTarget,
} from "./context.js";
export type {
  TrailblazeCallToolResult,
  TrailblazeClient,
  TrailblazeToolEntry,
  TrailblazeToolMap,
} from "./client.js";
export type { EmptyInput, ToolContext, TypedToolDefinition } from "./tool.js";
export {
  captureViewHierarchy,
  ConditionalActionFailedError,
  runConditionalActions,
  type ConditionalAction,
  type ViewHierarchy,
} from "./conditional-action.js";

// Selector-grammar types generated from Kotlin sealed-class sources by
// `:trailblaze-models:generateSelectorsTs`. The `selectors` factory namespace gives
// authors scoped IDE autocomplete (`selectors.androidAccessibility({ textRegex: "Submit" })`)
// equivalent to the literal `{ androidAccessibility: { textRegex: "Submit" } }` shape;
// both forms produce identical `TrailblazeNodeSelector` values. See the file header in
// `generated/selectors.ts` for the regeneration command and CI gate.
export {
  selectors,
  type Bounds,
  type DriverNodeMatchAndroidAccessibility,
  type DriverNodeMatchAndroidMaestro,
  type DriverNodeMatchCompose,
  type DriverNodeMatchIosAxe,
  type DriverNodeMatchIosMaestro,
  type DriverNodeMatchWeb,
  type MatchDescriptor,
  type TrailblazeNodeSelector,
} from "./generated/selectors.js";

// Side-effect import — pulls in the vendored built-in-tool bindings so authors get
// autocomplete / type-checking on framework tools (`tapOnElementWithText`, `inputText`,
// etc.) the moment they import anything from `@trailblaze/scripting`. The imported file is
// pure declaration merging on `TrailblazeToolMap`; no runtime values are added.
import "./built-in-tools.js";
export type { RunOptions, TrailblazeToolHandler, TrailblazeToolSpec, TrailblazeTypedToolSpec };

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
