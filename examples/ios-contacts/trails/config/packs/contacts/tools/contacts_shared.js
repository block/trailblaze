export const DEFAULT_APP_ID = "com.apple.MobileAddressBook";

/**
 * Throws if no live Trailblaze session context was injected — the inline-example
 * tools require a real session to drive the device.
 *
 * @param {import("@trailblaze/scripting").TrailblazeContext | undefined} ctx
 * @returns {void}
 */
export function requireSessionContext(ctx) {
  if (!ctx) {
    throw new Error("This inline example requires a live Trailblaze session context.");
  }
}

/**
 * Returns `value` if it's a non-empty string; otherwise the `fallback`.
 *
 * @param {unknown} value
 * @param {string} fallback
 * @returns {string}
 */
export function nonEmptyString(value, fallback) {
  return typeof value === "string" && value.length > 0 ? value : fallback;
}
