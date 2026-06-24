// Typed-tool authoring CORE — the slim, dependency-light half of the `tool()` surface.
//
// This module is deliberately free of the heavy runtime deps. It value-imports ONLY
// `createMemory` (pure) and otherwise uses type-only imports (erased by esbuild). In particular
// it does NOT value-import `@modelcontextprotocol/sdk`, `ajv`, or `zod` — those live in the
// full-path modules (`tool.ts` / `run.ts`). That separation is what lets the in-process /
// on-device bundle (which imports `@trailblaze/scripting` aliased to `./in-process.js`) stay
// KB-scale instead of dragging the ~1 MB MCP server + ajv + zod onto the device.
//
// The typed authoring surface (`trailblaze.tool<I, O>(handler)` / `(spec, handler)`) resolves to
// [defineTypedTool], whose only optional heavy behavior — ajv input validation — is **injected**
// by the caller via [compileValidator]. The full entry (`sub-process.ts`) passes an ajv-backed
// compiler; the slim entry (`in-process.ts`) passes nothing, so the typed tool dispatches without ajv
// validation (the analyzer-emitted schema + MCP advertisement remain the source of truth — see
// `TrailblazeTypedToolSpec.inputSchema`'s "opt-in escape hatch" note). The imperative
// `tool(name, spec, handler)` form is full-path only and lives in `tool.ts`.

import type { ErrorObject, ValidateFunction } from "ajv/dist/2020.js";

import type { TrailblazeClient, TrailblazeToolMethods } from "./client.js";
import type { TrailblazeContext, TrailblazeDevice, TrailblazeTarget } from "./context.js";
import { createMemory, type TrailblazeMemory } from "./memory.js";

/**
 * Minimal handler context for the typed `trailblaze.tool<I, O>(handler)` authoring surface.
 * Exposes the cross-tool primitives a typed handler can reach: [tools], [memory], [target].
 *
 * Deliberately narrower than `TrailblazeClient` / `TrailblazeContext`; grow it in lockstep with
 * real demand (see the full inclusion-policy note that previously lived on this type in `tool.ts`).
 */
export interface ToolContext {
  /** Compose other Trailblaze tools through the typed `tools.<name>(args)` namespace. */
  tools: TrailblazeToolMethods;
  /**
   * Per-invocation memory surface. Reads see the host snapshot + this invocation's writes;
   * writes are flushed back to the host on a successful return via the result envelope's
   * `_meta.trailblaze.memoryDelta` (subprocess path; bundle path buffers without flush).
   */
  memory: TrailblazeMemory;
  /**
   * Connected-device descriptor — platform, pixel dimensions, and the session's driver yamlKey
   * (e.g. `"android-ondevice-accessibility"`). Lets a typed tool branch on the active
   * driver/platform the way a Kotlin tool reads `agent.usesAccessibilityDriver` /
   * `trailblazeDeviceInfo.platform`. `undefined` only when the tool is invoked outside a session
   * envelope (ad-hoc MCP client, a unit test that didn't supply a context).
   */
  device?: TrailblazeDevice;
  /**
   * Resolved-target descriptor (`target.platforms.<platform>` after device resolution).
   * `undefined` when the session has no target configured — optional-chain
   * (`ctx.target?.resolveAppId()`) when the tool should degrade gracefully.
   */
  target?: TrailblazeTarget;
}

/**
 * Public marker for "this tool takes no input." Declared as an `interface` (not
 * `Record<string, never>`) so the analyzer's `ts-json-schema-generator` walks it as a named
 * object type and emits `{"type":"object","additionalProperties":false}`.
 */
// eslint-disable-next-line @typescript-eslint/no-empty-interface
export interface EmptyInput { /* intentionally empty — see kdoc */ }

/**
 * Structured-config spec for the typed `trailblaze.tool<I, O>(spec, handler)` overload. Carries
 * the namespaced framework hints (`supportedPlatforms`, `requiresContext`, `requiresHost`,
 * `supportedDrivers`) the build-time analyzer (`ScriptedToolDefinitionAnalyzer`) extracts from
 * each call site. **No `description`** — typed-tool descriptions live in the TSDoc above the
 * `export const X = trailblaze.tool(...)` binding, which the analyzer reads.
 *
 * Field roles — see the full "registration gate" vs "metadata hint" discussion that previously
 * lived in `tool.ts`. `inputSchema` is the one field consumed at the JS dispatch boundary (via
 * the injected [defineTypedTool] validator); the rest flow into `_meta` via the analyzer.
 */
export interface TrailblazeTypedToolSpec {
  /** Platforms this tool may register on. Empty / omitted = all platforms. */
  supportedPlatforms?: ReadonlyArray<"web" | "android" | "ios" | "desktop">;
  /** UX hint: this tool depends on a live driver context. NOT a registration filter. */
  requiresContext?: boolean;
  /** Host-only — skip registration on-device (Node/Bun APIs). Read before `_meta` on-device. */
  requiresHost?: boolean;
  /** Drivers this tool may register on. Empty / omitted = all drivers. */
  supportedDrivers?: readonly string[];
  /**
   * Advertise this tool to the LLM. `false` hides it from the LLM's tool menu while keeping it
   * dispatchable by name (composed by a parent tool) and resolvable for recorded replays — the
   * scripted-tool equivalent of an internal Kotlin step. NOT a registration filter; the tool still
   * registers. Omitted = `true`.
   */
  surfaceToLlm?: boolean;
  /**
   * Record this tool's invocation in the replayable `.trail.yaml`. `false` keeps an internal step a
   * parent tool composes out of the recording. NOT a registration filter; the tool still runs.
   * Omitted = `true`.
   */
  isRecordable?: boolean;
  /**
   * Optional JSON Schema for the typed tool's input. When present AND the caller injected a
   * validator compiler (full path), the runtime adapter validates `args` BEFORE the handler;
   * a failure throws [TypedToolValidationError]. On the slim path no compiler is injected, so
   * this is advisory only — the analyzer-derived `<TInput>` schema is the source of truth for
   * MCP advertisement either way. Plain JSON Schema literal, not a zod schema.
   */
  inputSchema?: Record<string, unknown>;
}

/**
 * Carrier returned by the typed authoring surface: a 3-arg adapter `(args, ctx, client) =>
 * Promise<TResult>` — the call shape the synthesized scripted-tool wrapper invokes. The `<I, O>`
 * type params are the load-bearing part for codegen; `ScriptedToolDefinitionAnalyzer` walks each
 * `trailblaze.tool<I, O>(...)` call site to derive the `TrailblazeToolMap` entries.
 */
export type TypedToolDefinition<
  TInput = Record<string, never>,
  TResult = string,
> = (
  args: TInput,
  ctx: TrailblazeContext | undefined,
  client: TrailblazeClient,
) => Promise<TResult>;

/**
 * Typed-tool validation failure. Thrown from inside the typed adapter so the synthesized
 * wrapper's host-side dispatch catches it like any handler-thrown error. `name = "ValidationError"`
 * makes it identifiable from session logs without `instanceof`.
 */
export class TypedToolValidationError extends Error {
  override name = "ValidationError";
  constructor(readonly ajvErrors: ReadonlyArray<ErrorObject>) {
    super(`Invalid arguments: ${formatAjvErrors(ajvErrors)}`);
  }
}

/**
 * Convert an ajv error list into a single field-level message the LLM can self-correct against.
 * Root-level errors (`instancePath === ""`) surface as `(root)`.
 */
export function formatAjvErrors(errors: ReadonlyArray<ErrorObject>): string {
  return errors
    .map((e) => {
      const path = e.instancePath.length > 0 ? e.instancePath : "(root)";
      return `${path}: ${e.message ?? "validation failed"}`;
    })
    .join("; ");
}

/**
 * Underlying runtime wrapper for the typed `trailblaze.tool<I, O>(handler)` overload. Returns a
 * 3-arg adapter binding the author's typed `(input, ctx)` handler to the synthesized wrapper's
 * `(args, ctx, client)` call shape, constructing the [ToolContext] from `client.tools` +
 * `legacyCtx.memory` per invocation.
 *
 * **ajv validation is injected, not imported.** When [inputSchema] AND [compileValidator] are both
 * provided (full path), the adapter validates `args` before invoking the handler and throws
 * [TypedToolValidationError] on failure. The slim path passes no [compileValidator], so this core
 * carries zero ajv weight; typed tools dispatch without the runtime check (analyzer schema + MCP
 * advertisement remain the source of truth).
 */
export function defineTypedTool<TInput, TResult>(
  handler: (input: TInput, ctx: ToolContext) => Promise<TResult>,
  inputSchema?: Record<string, unknown>,
  compileValidator?: (schema: Record<string, unknown>) => ValidateFunction,
): TypedToolDefinition<TInput, TResult> {
  if (typeof handler !== "function") {
    // The `<TInput, TResult>` overloads guarantee a function at type-check time, but a JS-only
    // caller (or an `as unknown`-cast TS caller) can still hand us garbage. Fail loudly at
    // definition time instead of surfacing as a "not a function" error far from the call site.
    throw new TypeError(
      "trailblaze.tool<I, O>(handler): argument must be a function. " +
        "Got: " + (handler == null ? String(handler) : typeof handler) + ".",
    );
  }
  // Compile the input validator once at definition time (closure-captured; defineTypedTool runs
  // once per tool, so the closure IS the cache). Wrapped in try/catch so a malformed inputSchema
  // on one tool doesn't abort every typed tool declared after it in module-eval order.
  let validator: ValidateFunction | null = null;
  if (inputSchema != null && compileValidator != null) {
    try {
      validator = compileValidator(inputSchema);
    } catch (e: unknown) {
      const reason = e instanceof Error ? e.message : String(e);
      // eslint-disable-next-line no-console
      console.warn(
        `[trailblaze.tool] typed tool: ajv schema compile failed ` +
          `(${reason}). The tool will dispatch without input validation. Fix the ` +
          `inputSchema in the spec and re-run.`,
      );
    }
  }
  // Bridge adapter — the synthesized wrapper calls every handler as `(args, ctx, client)`; the
  // typed handler wants `(input, ToolContext)`. Build the ToolContext from `client.tools` +
  // `legacyCtx.memory` per-call. When [validator] is non-null, validate `args` first; on rejection
  // throw the typed error the wrapper's host-side dispatch catches.
  return async (args, legacyCtx, client) => {
    // Coerce args to a real object reference before validation so ajv's `useDefaults` mutation
    // has somewhere to write JSDoc-derived defaults, and the handler sees the SAME reference. The
    // imperative path in `tool.ts#registerPendingTools` has a sibling block — keep them in lockstep.
    const validatedArgs: Record<string, unknown> =
      args != null && typeof args === "object" && !Array.isArray(args)
        ? (args as Record<string, unknown>)
        : {};
    if (validator != null && !validator(validatedArgs)) {
      throw new TypedToolValidationError(validator.errors ?? []);
    }
    // `legacyCtx.memory` arrives in one of two shapes depending on dispatch path:
    //   • Subprocess / MCP path: `fromMeta` already wrapped the inbound snapshot into a
    //     `TrailblazeMemory` (the 8-method surface), so it passes through untouched.
    //   • On-device QuickJS path: the injected `__ctx` literal carries `memory` as a raw
    //     `Record<string,string>` snapshot (the Kotlin `QuickJsToolCtxEnvelope.memory` field) —
    //     plain data with no methods. Wrap it via `createMemory` so on-device authors get the
    //     same `ctx.memory.get/has/keys/interpolate/...` surface the subprocess path has.
    // Either way the snapshot is the host's NON-sensitive memory (sensitive keys are filtered out
    // of every scripting envelope on the Kotlin side), so no path ever exposes secrets to JS. The
    // discriminator is the presence of a function-valued `interpolate` — a raw record has only
    // string values, a `TrailblazeMemory` has methods.
    const rawMemory = legacyCtx?.memory;
    const memory: TrailblazeMemory =
      rawMemory != null && typeof (rawMemory as TrailblazeMemory).interpolate === "function"
        ? (rawMemory as TrailblazeMemory)
        : createMemory(rawMemory as Record<string, string> | undefined);
    // `device` and `target` ride through from the legacy ctx. Normalize the device block so typed
    // handlers can consistently branch on `ctx.device.driverType`, even when invoked by an older
    // on-device QuickJS host that only emitted the deprecated `driver` alias.
    const toolContext: ToolContext = {
      tools: client.tools,
      memory,
      device: normalizeDevice(legacyCtx?.device),
      target: legacyCtx?.target,
    };
    return handler(validatedArgs as TInput, toolContext);
  };
}

function normalizeDevice(device: TrailblazeDevice | undefined): TrailblazeDevice | undefined {
  if (device == null) return undefined;
  const raw = device as TrailblazeDevice & { driverType?: string };
  if (typeof raw.driverType === "string" && raw.driverType.length > 0) return device;
  if (typeof raw.driver === "string" && raw.driver.length > 0) {
    return { ...device, driverType: raw.driver };
  }
  return device;
}
