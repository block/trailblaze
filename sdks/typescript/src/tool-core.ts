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

import type { TrailblazeClient, TrailblazeHostMethods, TrailblazeToolMethods } from "./client.js";
import { parseHostPlatform } from "./context.js";
import type {
  TrailblazeContext,
  TrailblazeDevice,
  TrailblazeHostArch,
  TrailblazeHostOs,
  TrailblazeHostPlatform,
  TrailblazeTarget,
} from "./context.js";
import { createSessionEvents, type SessionEvents } from "./session-events.js";
import { createToolHistory, type ToolHistory } from "./history.js";
import type { TrailblazeSessionResources } from "./session-resources.js";
import { attachMemoryDeltaToResult, createMemory, type TrailblazeMemory } from "./memory.js";
import {
  attachTrailheadReportToResult,
  createTrailheadRecorder,
  createTrailRun,
  markErrorWithTrailheadReport,
  validateTeardownResult,
  type TeardownHandler,
  type PreviousRun,
  type TeardownResult,
  type TrailheadContext,
} from "./trailhead.js";

/**
 * `ctx.host`: the Kotlin host functions, plus the computer the tool runs on. `os` and `arch` are
 * `null` when the tool runs on an Android device, where the device is the only computer.
 * `tools.exec` already finds a bare Windows command name (`adb` → `adb.exe`), so they're for what
 * else differs between hosts, such as paths.
 */
export type TrailblazeHost = TrailblazeHostMethods & {
  readonly os: TrailblazeHostOs | null;
  readonly arch: TrailblazeHostArch | null;
};

/** Adds `os` and `arch` to a host-function namespace; every other name still calls a function. */
function hostWithPlatform(
  host: TrailblazeHostMethods | undefined,
  platform: TrailblazeHostPlatform | null,
): TrailblazeHost {
  return new Proxy((host ?? {}) as TrailblazeHostMethods, {
    get(target, prop, receiver) {
      if (prop === "os") return platform?.os ?? null;
      if (prop === "arch") return platform?.arch ?? null;
      return Reflect.get(target, prop, receiver);
    },
  }) as TrailblazeHost;
}

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
   * Call Kotlin host functions — `host.<name>(args)` — for plumbing that isn't a tool (credential
   * lookups, provisioning calls). Unlike `tools`, a host call isn't logged as a step and keeps the
   * cached screen. See `TrailblazeHostFunctionMap`. Also says which computer the tool runs on:
   * `host.os` and `host.arch`.
   */
  host: TrailblazeHost;
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
  /**
   * Opaque process-local session resources. Available on host subprocess tools; use it to
   * register release callbacks for handles that must never enter `ctx.memory` or a tool result.
   */
  session?: TrailblazeSessionResources;
  /**
   * The last trail's hand-off. Non-null only in a trailhead's own handler, when the last run of
   * the same trailhead on this device said its setup is reusable (see `trailhead.teardown`).
   * `null` in every other tool, in `teardown`, and whenever there is nothing to reuse.
   */
  previousRun: PreviousRun | null;
  /** `note(...)` works in any tool and tells the trailhead's `teardown` what to clean up. */
  trailhead: TrailheadContext;
  /** This session's event streams as they stand: query, count, wait for a record, or mark a point. */
  events: SessionEvents;
  /**
   * Every tool call and section of this session so far, recorded by the framework as they ran,
   * so complete when read. A `teardown` reads the trail section's calls to choose its cleanups.
   */
  history: ToolHistory;
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
export interface TrailblazeTypedToolSpec<TInput = Record<string, unknown>> {
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
   * Device-classifier segments the session's device must ALL carry for this tool to register,
   * e.g. `["rig"]` registers only on a device classified `android-phone-rig`. Empty / omitted = any
   * device. Use it for tools that need hardware or infrastructure only some devices have.
   */
  requiredClassifiers?: readonly string[];
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
   * Argument names whose values carry secret material — a password, a session token, an auth
   * payload — and must never reach a persisted session log. Session logs ship as CI artifacts,
   * so an unmasked credential leaves the machine.
   *
   * Names are top-level keys of this tool's input object. At the log-encode boundary each named
   * value is replaced with `<redacted>`; **execution is untouched** — the handler still receives
   * the real value. The scripted-tool equivalent of a Kotlin tool implementing
   * `SensitiveArgsTrailblazeTool`.
   *
   * Scope: this covers the tool-execution log, which is what a replayed trail and its CI artifact
   * carry. A tool invoked directly over MCP (including `trailblaze tool …`) is separately recorded
   * by the MCP request log and the daemon log before this masking runs, and stderr your handler
   * writes is copied to the daemon log verbatim. See the docs section linked below.
   *
   * ```ts
   * export const signIn = trailblaze.tool<{ email: string; password: string }>(
   *   { sensitiveArgNames: ["password"] },
   *   async ({ email, password }, ctx) => { ... },
   * );
   * ```
   *
   * Trade-off: a recording generated from such a session log carries the placeholder, not the
   * real value. That's intended — secret material is re-supplied at authoring time (e.g. via a
   * `{{memory}}` token), never round-tripped through logs.
   *
   * Typed against `TInput`, so the input interface stays the single source of truth for the field
   * names: a typo, or renaming `password` without updating this list, is a compile error rather
   * than a silently-unmasked credential.
   */
  sensitiveArgNames?: readonly Extract<keyof TInput, string>[];
  /**
   * Where the handler runs. `"inProcess"` (the default when omitted) is the embedded QuickJS
   * engine, the only runtime available on a device. `"subprocess"` is a host `bun` process, for a
   * handler that itself needs Node APIs (`node:fs`, `node:child_process`); such a tool can't run
   * on a device, so pair it with `requiresHost: true`. Same values as a descriptor's `runtime:`.
   */
  runtime?: "inProcess" | "subprocess";
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
  trailhead?: TrailheadSpec<TInput>;
}

/**
 * `trailhead` field shape for [TrailblazeTypedToolSpec]. 1:1 with the Kotlin
 * `TrailheadMetadata` data class (`ToolYamlConfig.kt`) that backs `*.trailhead.yaml`'s
 * `trailhead:` block — same two fields, same mutual-exclusion contract.
 */
export interface TrailheadSpec<TInput = Record<string, unknown>> {
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
  /**
   * Teardown and hand-off. Called once after every invocation of this trailhead that started,
   * however the run ended (passed, failed, cancelled, or the trailhead itself threw), while the
   * device is still connected. Clean up what the run left behind, then say whether the next run of
   * this trailhead with identical args can reuse the setup. When it can, the next run's handler
   * receives the result as `ctx.previousRun`; when it can't, `ctx.previousRun` is `null`.
   *
   * It never changes the trail's verdict: a throw or timeout is a warning, and the next run does a
   * full setup. Never copy secrets into `reason` or `state`.
   */
  teardown?: TeardownHandler<TInput, ToolContext>;
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
> = ((
  args: TInput,
  ctx: TrailblazeContext | undefined,
  client: TrailblazeClient,
) => Promise<TResult>) & {
  /**
   * The spec's `trailhead.teardown`, adapted to the same `(args, ctx, client)` call shape plus
   * the host's trail-result payload (`{ outcome, notes }`). Present only when the spec defines it;
   * the synthesized wrappers expose it to the host as the tool's `teardown` member.
   */
  teardown?: (
    args: TInput,
    ctx: TrailblazeContext | undefined,
    client: TrailblazeClient,
    trailRun: unknown,
  ) => Promise<TeardownResult>;
};

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
  teardown?: TeardownHandler<TInput, ToolContext>,
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
  if (teardown !== undefined && typeof teardown !== "function") {
    throw new TypeError("trailblaze.tool(spec, handler): spec.trailhead.teardown must be a function.");
  }
  const adapter: TypedToolDefinition<TInput, TResult> = async (args, legacyCtx, client) => {
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
    const trailhead = createTrailheadRecorder(legacyCtx?.previousRun);
    const toolContext: ToolContext = {
      tools: client.tools,
      host: hostWithPlatform(client.host, parseHostPlatform(legacyCtx?.hostPlatform)),
      memory,
      device: normalizeDevice(legacyCtx?.device),
      target: legacyCtx?.target,
      session: legacyCtx?.session,
      previousRun: trailhead.previousRun,
      trailhead: trailhead.context,
      events: createSessionEvents(client.host),
      history: createToolHistory(client.host),
    };
    let out: TResult;
    try {
      out = await handler(validatedArgs as TInput, toolContext);
    } catch (e: unknown) {
      // Notes written before a throw still matter: a tool that created something and then failed
      // must still tell cleanup about it.
      throw markErrorWithTrailheadReport(e, trailhead.report());
    }
    // Wrapping a result serializes a non-envelope one, which throws on a cycle or a BigInt; the
    // notes still have to reach cleanup, so they ride the error like a handler's own throw.
    let withReport: TResult;
    try {
      withReport = attachTrailheadReportToResult(out, trailhead.report()) as TResult;
    } catch (e: unknown) {
      throw markErrorWithTrailheadReport(e, trailhead.report());
    }
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
    if (memoryIsWrapped) return withReport;
    return attachMemoryDeltaToResult(withReport, memory) as TResult;
  };
  if (teardown !== undefined) {
    adapter.teardown = async (args, legacyCtx, client, trailRunWire) => {
      const validatedArgs =
        args != null && typeof args === "object" && !Array.isArray(args) ? args : ({} as TInput);
      const rawMemory = legacyCtx?.memory;
      const memory: TrailblazeMemory =
        rawMemory != null && typeof (rawMemory as TrailblazeMemory).interpolate === "function"
          ? (rawMemory as TrailblazeMemory)
          : createMemory(rawMemory as Record<string, string> | undefined);
      // `previousRun` belongs to the trailhead's own run, not its teardown, and notes written now
      // would land after the trail they describe — so teardown gets no hand-off and an inert
      // recorder whose report is discarded.
      const toolContext: ToolContext = {
        tools: client.tools,
        host: hostWithPlatform(client.host, parseHostPlatform(legacyCtx?.hostPlatform)),
        memory,
        device: normalizeDevice(legacyCtx?.device),
        target: legacyCtx?.target,
        session: legacyCtx?.session,
        previousRun: null,
        trailhead: createTrailheadRecorder(undefined).context,
        events: createSessionEvents(client.host),
        history: createToolHistory(client.host),
      };
      const result = await teardown(
        validatedArgs as TInput,
        toolContext,
        createTrailRun(trailRunWire, client),
      );
      return validateTeardownResult(result);
    };
  }
  return adapter;
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
