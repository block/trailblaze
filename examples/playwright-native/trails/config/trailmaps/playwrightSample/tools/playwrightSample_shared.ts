export const PLAYWRIGHT_SAMPLE_HOME =
  "./examples/playwright-native/sample-app/index.html";

/** Returns `value` if it's a non-empty string; otherwise the `fallback`. */
export function nonEmptyString(value: unknown, fallback: string): string {
  return typeof value === "string" && value.length > 0 ? value : fallback;
}

/** Returns `value` if it's a positive integer; otherwise the `fallback`. */
export function positiveInteger(value: unknown, fallback: number): number {
  return typeof value === "number" && Number.isInteger(value) && value > 0 ? value : fallback;
}
