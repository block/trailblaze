/**
 * Process-local resources that belong to a single Trailblaze session.
 *
 * A scripted tool can use this surface for values that must stay out of agent memory, tool
 * results, recordings, and logs: it closes over the value in its release callback instead of
 * returning or remembering it. The host asks the subprocess to drain this registry immediately
 * before it shuts the process down (see `SESSION_RESOURCE_FINALIZER_TOOL`), so cleanup still runs
 * for failed and cancelled trails.
 *
 * This is deliberately a process-local registry rather than `ctx.memory`: `AgentMemory` is part
 * of the model-visible, replayable context whereas a resource registered here is opaque to the
 * framework outside the closure supplied by the tool author.
 */

import type { TrailblazeToolMethods } from "./client.js";

/** Reserved hidden MCP tool invoked by the host during subprocess teardown. */
export const SESSION_RESOURCE_FINALIZER_TOOL = "trailblaze_releaseSessionResources";

/** Metadata key used by the host to identify the reserved finalizer advertisement. */
export const SESSION_RESOURCE_FINALIZER_META_KEY = "trailblaze/sessionResourceFinalizer";

/**
 * The deliberately narrow live-call surface passed to a session cleanup.
 *
 * A cleanup runs in the hidden finalizer invocation, not in the invocation that acquired the
 * resource. In particular, it must use this `tools` handle rather than close over the acquiring
 * tool's `ctx.tools`: the host unregisters that original invocation as soon as the tool returns.
 */
export interface TrailblazeSessionCleanupContext {
  tools: TrailblazeToolMethods;
}

/** A best-effort asynchronous cleanup action owned by one scripted-tool session. */
export type TrailblazeSessionCleanup = (
  context: TrailblazeSessionCleanupContext,
) => void | Promise<void>;

/**
 * A cleanup must not be able to keep the subprocess alive indefinitely. This leaves enough room
 * for the callback channel's normal 30 s deadline while still allowing later resources to drain
 * when an earlier release hangs.
 */
const DEFAULT_CLEANUP_TIMEOUT_MS = 35_000;
let cleanupTimeoutMs = DEFAULT_CLEANUP_TIMEOUT_MS;

/**
 * Session-scoped writer exposed on `TrailblazeContext.session`.
 *
 * Register cleanup as soon as the external resource has been acquired. The returned function
 * unregisters it for callers that explicitly release the resource early. A callback is invoked at
 * most once: an explicit unregister or session finalization wins the race and removes it before
 * doing any asynchronous work.
 */
export interface TrailblazeSessionResources {
  registerCleanup(cleanup: TrailblazeSessionCleanup): () => void;
}

type RegisteredCleanup = {
  cleanup: TrailblazeSessionCleanup;
};

const cleanupsBySession = new Map<string, Map<number, RegisteredCleanup>>();
let nextCleanupId = 1;

/** Gets the opaque resource writer for one in-process session. */
export function sessionResourcesFor(sessionId: string): TrailblazeSessionResources {
  return {
    registerCleanup(cleanup: TrailblazeSessionCleanup): () => void {
      if (typeof cleanup !== "function") {
        throw new TypeError("ctx.session.registerCleanup(cleanup): cleanup must be a function.");
      }
      const cleanupId = nextCleanupId++;
      let cleanups = cleanupsBySession.get(sessionId);
      if (cleanups == null) {
        cleanups = new Map<number, RegisteredCleanup>();
        cleanupsBySession.set(sessionId, cleanups);
      }
      cleanups.set(cleanupId, { cleanup });

      return () => {
        const current = cleanupsBySession.get(sessionId);
        if (current == null) return;
        current.delete(cleanupId);
        if (current.size === 0) cleanupsBySession.delete(sessionId);
      };
    },
  };
}

/**
 * Drains every cleanup registered by [sessionId]. This is host-invoked; it intentionally returns
 * no resource details and never includes a cleanup's exception message, because a closure may
 * contain credentials or an opaque lease handle.
 *
 * The entire bucket is detached before any callback begins. This makes retries idempotent and
 * prevents a re-entrant cleanup callback from observing (or re-running) a partially-drained set.
 * Every callback is still attempted after an earlier failure or timeout so one broken release does
 * not strand unrelated resources in the same session.
 */
export async function releaseSessionResources(
  sessionId: string,
  cleanupContext?: TrailblazeSessionCleanupContext,
): Promise<void> {
  const cleanups = cleanupsBySession.get(sessionId);
  if (cleanups == null || cleanups.size === 0) return;
  cleanupsBySession.delete(sessionId);

  // Start every cleanup before awaiting any of them. The host's MCP request has its own deadline;
  // awaiting each 35-second callback in series could exhaust that outer deadline before a later
  // callback even starts. Reverse registration order still determines invocation order so nested
  // resources begin releasing child-first, while all releases get their own bounded opportunity.
  const results = await Promise.allSettled(
    [...cleanups.values()].reverse().map(({ cleanup }) =>
      withCleanupTimeout(cleanup, cleanupContext),
    ),
  );
  const failures = results.filter((result) => result.status === "rejected").length;
  if (failures > 0) {
    throw new Error(`${failures} session resource cleanup callback(s) failed.`);
  }
}

async function withCleanupTimeout(
  cleanup: TrailblazeSessionCleanup,
  cleanupContext: TrailblazeSessionCleanupContext | undefined,
): Promise<void> {
  let timeout: ReturnType<typeof setTimeout> | undefined;
  try {
    await Promise.race([
      Promise.resolve().then(() => cleanup(cleanupContext as TrailblazeSessionCleanupContext)),
      new Promise<never>((_, reject) => {
        timeout = setTimeout(
          () => reject(new Error("Session resource cleanup callback timed out.")),
          cleanupTimeoutMs,
        );
      }),
    ]);
  } finally {
    if (timeout !== undefined) clearTimeout(timeout);
  }
}

/** Test-only override so timeout behavior is covered without slowing the SDK suite. */
export function _setSessionResourceCleanupTimeoutForTest(timeoutMs: number): void {
  cleanupTimeoutMs = timeoutMs;
}

/** Test-only reset so isolated SDK tests never retain a process-global callback. */
export function _clearSessionResourcesForTest(): void {
  cleanupsBySession.clear();
  nextCleanupId = 1;
  cleanupTimeoutMs = DEFAULT_CLEANUP_TIMEOUT_MS;
}
