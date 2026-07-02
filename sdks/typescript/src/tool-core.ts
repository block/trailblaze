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
import { attachMemoryDeltaToResult, createMemory, type TrailblazeMemory } from "./memory.js";

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
 * each call site.
 *
 * **`description` precedence — where the LLM-facing description comes from.** A typed tool's
 * description is resolved in this order, most-authoritative first:
 *   1. a YAML sidecar `description:` (when the tool has a `<tool>.yaml` descriptor — already
 *      authoritative for bundled / sidecar tools),
 *   2. this spec's [description] field (NEW — set it here to make the LLM-facing intent explicit
 *      and co-located with the other registration config),
 *   3. the JSDoc/TSDoc block above the `export const X = trailblaze.tool(...)` binding, which the
 *      analyzer reads as the catch-all fallback (the historical behavior).
 *
 * Prefer [description] over relying on the doc comment when the LLM-facing wording matters: the
 * TSDoc doubles as developer documentation, so implementation notes parked there leak to the
 * model. Setting [description] keeps the LLM contract obvious next to `supportedPlatforms` /
 * `surfaceToLlm` and leaves the doc comment free for author-facing prose.
 *
 * Field roles — see the full "registration gate" vs "metadata hint" discussion that previously
 * lived in `tool.ts`. [inputSchema] is the one field consumed at the JS dispatch boundary (via
 * the injected [defineTypedTool] validator); [description] routes into the tool's primary
 * descriptor (NOT `_meta`); the remaining gate fields flow into `_meta` via the analyzer.
 */
export interface TrailblazeTypedToolSpec {
  /**
   * LLM-facing description for this tool. When set, it overrides the JSDoc/TSDoc above the
   * binding (but a YAML sidecar `description:` still wins — see the precedence note on this
   * interface). Omit it to fall back to the doc comment, which the analyzer reads.
   */
  description?: string;
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
  /**
   * Marks this tool as a **trailhead**: a deterministic bootstrap that takes a device from a
   * blank slate to a known app state (clear data, launch, sign in, land on a screen) — step 0 of
   * a trail. Presence of this field is what makes the tool a trailhead; `to`/`dynamic` mirror
   * the YAML `*.trailhead.yaml` sidecar's `trailhead:` block (`TrailheadMetadata` in
   * `ToolYamlConfig.kt`) field-for-field, so a TS-authored tool no longer needs a companion
   * `.trailhead.yaml` just to register its navigation metadata.
   *
   * Like [description] this is a primary-descriptor field, NOT a `_meta` gate: it doesn't affect
   * registration or dispatch, so it's read directly off the analyzer-captured spec by discovery
   * surfaces (`toolbox trailheads`, the Trail Runner "Use as Trailhead" picker) rather than
   * projected into the namespaced `trailblaze/...` `_meta` keys the runtime reads to gate
   * execution.
   */
  trailhead?: TrailheadSpec;
}

/**
 * `trailhead` field shape for [TrailblazeTypedToolSpec]. 1:1 with the Kotlin
 * `TrailheadMetadata` data class (`ToolYamlConfig.kt`) that backs `*.trailhead.yaml`'s
 * `trailhead:` block — same two fields, same mutual-exclusion contract.
 */
export interface TrailheadSpec {
  /**
   * The single waypoint this trailhead lands on. Required unless [dynamic] is true — a trailhead
   * with neither is dropped with a warning (not a real bootstrap destination).
   */
  to?: string;
  /**
   * Marks a trailhead whose destination varies by input (e.g. a deep-link launcher), so no
   * single `to:` waypoint applies. Mutually exclusive with [to]. Defaults to `false`.
   */
  dynamic?: boolean;
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
    // `memoryIsWrapped` = the memory arrived already as a `TrailblazeMemory` (subprocess / MCP path,
    // where `fromMeta` wrapped it). When it did NOT, we reconstruct one from the raw snapshot — that
    // reconstruction is the on-device / in-process QuickJS path, and it's the memory the host has no
    // other handle on, so we own flushing its diff back (see the post-handler block below).
    const memoryIsWrapped =
      rawMemory != null && typeof (rawMemory as TrailblazeMemory).interpolate === "function";
    const memory: TrailblazeMemory = memoryIsWrapped
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
    const out = await handler(validatedArgs as TInput, toolContext);
    // On the subprocess / MCP path, the surrounding `attachMemoryDelta` (tool.ts) drains
    // `ctx.memory` after the handler returns, so leave the raw result alone here. On the in-process
    // / on-device QuickJS path there is no such wrapper AND `ctx.memory` is a raw snapshot the host
    // can't drain — the writes went into the `memory` we reconstructed above, so flush its diff onto
    // the result's `_meta.trailblaze.memoryDelta` here. Without this, an on-device
    // `ctx.memory.set(...)` was silently dropped and never reached the next tool's `.get(...)` (the
    // write-then-read hand-off between two scripted tools). The Kotlin `QuickJsTrailblazeTool` applies
    // the delta back into the shared `AgentMemory`, mirroring `SubprocessTrailblazeTool`.
    // Cast: the analyzer treats `<TResult>` as a compile-time signal; at runtime the synthesized
    // wrapper's `__normalizeResult` consumes whatever shape we return (bare value or envelope).
    if (memoryIsWrapped) return out;
    return attachMemoryDeltaToResult(out, memory) as TResult;
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
