import type { TrailblazeContext } from "@trailblaze/scripting";

export const PLAYWRIGHT_SAMPLE_HOME =
  "./examples/playwright-native/sample-app/index.html";

/**
 * Throws if no live Trailblaze session context was injected — the inline-example
 * tools require a real session to drive the device.
 */
export function requireSessionContext(
  ctx: TrailblazeContext | undefined,
): asserts ctx is TrailblazeContext {
  if (!ctx) {
    throw new Error("This inline example requires a live Trailblaze session context.");
  }
}

/** Returns `value` if it's a non-empty string; otherwise the `fallback`. */
export function nonEmptyString(value: unknown, fallback: string): string {
  return typeof value === "string" && value.length > 0 ? value : fallback;
}

/** Returns `value` if it's a positive integer; otherwise the `fallback`. */
export function positiveInteger(value: unknown, fallback: number): number {
  return typeof value === "number" && Number.isInteger(value) && value > 0 ? value : fallback;
}
