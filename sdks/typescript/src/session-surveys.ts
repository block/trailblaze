// `ctx.surveys`: run a trailmap's survey while the trail runs, from any tool, in either runtime.
//
// A survey lives in its own file under a trailmap's `surveys/`, so it is not part of the calling
// tool's bundle. The framework bundles it on its own (on the host when the daemon first needs it, at
// build time for a device), and `trailblaze.surveys.load` hands that bundle over. It is evaluated
// once per engine and holds the survey runtime, so this file stays small in every tool's bundle.

import type { SessionEvents, SessionEventsSince } from "./session-events.js";

/** Reserved host function behind [TrailblazeSurveys]. Mirrors `SurveyHostFunctions.kt`. */
export const HOST_FUNCTION_SURVEYS_LOAD = "trailblaze.surveys.load";

/**
 * The surveys a tool can run mid-trail, by export name. Augmented by the generated per-trailmap
 * `client.d.ts` with one entry per survey that does not set `requiresFinishedSession`; one that
 * does runs only after the session and is left out.
 */
// eslint-disable-next-line @typescript-eslint/no-empty-interface
export interface TrailblazeSurveyMap {}

export interface SurveyRunOptions {
  /** Read only events since this starting point or `ctx.events.mark(name)`. Default: the whole session. */
  since?: SessionEventsSince;
  /** How long to wait for a finding before failing. Default 0: judge what is captured now. */
  waitMs?: number;
  /** Partial finding to wait for. Objects allow extra keys; arrays match expected items in any order. Primitives match exactly. */
  expected?: Record<string, unknown>;
}

/** One finding, with the records that prove it and whatever `data` its survey carried. */
export interface SurveyRunFinding {
  /** The survey that found it. */
  survey: string;
  summary: string;
  evidence: Array<{ kind: string; summary: string; file: string; line?: number; timeMs?: number; detail?: unknown }>;
  /** Whatever the survey chose to carry, in the shape it chose. */
  data?: Record<string, unknown>;
}

export interface SurveyRunResult {
  survey: string;
  /** True on the first matching finding. False when time ran out or the capture lost data first. */
  passed: boolean;
  /** What was found, or why nothing was: the queries that matched and those that did not. */
  message: string;
  findings: SurveyRunFinding[];
  waitedMs: number;
}

/** `ctx.surveys.<name>(options)`: runs that survey over what is captured so far. Never throws when it finds nothing. */
export type TrailblazeSurveys = {
  [K in keyof TrailblazeSurveyMap]: (options?: SurveyRunOptions) => Promise<SurveyRunResult>;
};

/** What `ctx` knows about the session, for findings and a survey's `platforms`, `appIds` and `targets`. */
export interface SurveySessionInfo {
  sessionId?: string;
  platform?: string;
  driver?: string;
  target?: string;
  appId?: string;
}

interface SurveyBundle {
  capabilities?: { since?: boolean; expected?: boolean };
  runLive(
    name: string,
    events: SessionEvents,
    options: SurveyRunOptions & { sessionId?: string; summary?: Omit<SurveySessionInfo, "sessionId">; targets?: TargetCatalog },
  ): Promise<SurveyRunResult>;
}

/** Target id → its app ids, the shape a survey's `targets` is matched against. */
type TargetCatalog = Record<string, { id: string; appIds: string[]; platforms: Record<string, string[]> }>;

interface LoadedSurvey {
  /** An esbuild IIFE that assigns the bundle to `BUNDLE_GLOBAL`. */
  source: string;
  /** Workspace-relative file the survey came from, for messages. */
  file: string;
  /** The workspace's targets: id → platform → app ids. */
  targets?: Record<string, Record<string, string[]>>;
}

function catalogOf(targets: LoadedSurvey["targets"]): TargetCatalog {
  const out: TargetCatalog = {};
  for (const [id, platforms] of Object.entries(targets ?? {})) {
    out[id] = { id, appIds: [...new Set(Object.values(platforms).flat())], platforms };
  }
  return out;
}

/** The global a survey bundle's IIFE assigns. Mirrors `SurveyBundles.GLOBAL_NAME` in Kotlin. */
const BUNDLE_GLOBAL = "__trailblazeSurveyBundle";

type HostCall = (args: unknown) => Promise<unknown>;

export function createSessionSurveys(host: unknown, events: SessionEvents, about: SurveySessionInfo = {}): TrailblazeSurveys {
  const bundles = new Map<string, Promise<{ bundle: SurveyBundle; targets: TargetCatalog }>>();
  const load = (name: string): Promise<{ bundle: SurveyBundle; targets: TargetCatalog }> => {
    let bundle = bundles.get(name);
    if (!bundle) {
      bundle = (async () => {
        const fn = (host as Record<string, HostCall> | undefined)?.[HOST_FUNCTION_SURVEYS_LOAD];
        if (typeof fn !== "function") {
          throw new Error(`ctx.surveys is unavailable: this runtime has no host function '${HOST_FUNCTION_SURVEYS_LOAD}'.`);
        }
        const loaded = (await fn({ name })) as LoadedSurvey;
        // The bundle is the framework's own build of a workspace file, the same trust as the
        // calling tool's bundle; it runs in this engine because QuickJS has no module loader.
        const bundle = new Function(`${loaded.source}\nreturn ${BUNDLE_GLOBAL};`)() as SurveyBundle;
        return { bundle, targets: catalogOf(loaded.targets) };
      })();
      // A failed load is not cached: the next call retries, after a fix or a capture that started late.
      bundle.catch(() => bundles.delete(name));
      bundles.set(name, bundle);
    }
    return bundle;
  };
  return new Proxy({} as TrailblazeSurveys, {
    get(_, name) {
      // `then` is not a survey: without this, awaiting `ctx.surveys` would try to call it.
      if (typeof name !== "string" || name === "then") return undefined;
      const { sessionId, ...summary } = about;
      const defined = Object.fromEntries(Object.entries(summary).filter(([, v]) => v !== undefined));
      return async (options?: SurveyRunOptions) => {
        const { bundle, targets } = await load(name);
        // Pushed tools can be newer than the survey bundle packaged in a device's test APK.
        for (const option of ["since", "expected"] as const) {
          if (options?.[option] !== undefined && bundle.capabilities?.[option] !== true) {
            throw new Error(`Survey ${name}'s bundle does not support "${option}". Rebuild the test APK or regenerate the survey bundle with the current framework.`);
          }
        }
        return bundle.runLive(name, events, { ...options, ...(sessionId ? { sessionId } : {}), summary: defined, targets });
      };
    },
  });
}
