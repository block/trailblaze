import type { TrailblazeClient, TrailblazeContext } from "@trailblaze/scripting";
import {
  nonEmptyString,
  requireSessionContext,
  textIsVisible,
} from "./contacts_ios_shared";

const DEFAULT_NAME = "John Appleseed";

export interface VerifyContactStructureArgs {
  /** Contact name to assert in the navbar / heading. */
  name?: string;
  /**
   * Optional list of additional field labels the contact must surface — common
   * values: "phone", "mobile", "email", "home", "work". Each entry is passed
   * to `assertVisibleWithAccessibilityText` as the `textRegex` value, so it's
   * interpreted as a regex against accessibility text (case-sensitive by
   * default — wrap with `(?i)` for case-insensitive). Empty list (the
   * default) skips the field-presence assertions.
   */
  requireFields?: string[];
}

/**
 * Composite assertion against the currently-open contact's detail screen.
 * Branches by which fields the caller demands:
 *
 *   1. `name` heading visible — every contact has one. Always asserted.
 *   2. For each entry in `requireFields`, asserts a row with that label text
 *      is visible. Each field has its own assertion so individual failures
 *      map back to a specific missing row.
 *
 * The `requireFields: []` default keeps this tool useful as a lightweight
 * "did the detail screen render?" check — pass labels when you want to
 * enforce a specific contact shape (e.g. "must have a phone AND an email").
 */
export async function contacts_ios_verifyContactStructure(
  args: VerifyContactStructureArgs,
  ctx: TrailblazeContext | undefined,
  client: TrailblazeClient,
): Promise<string> {
  requireSessionContext(ctx);

  const name = nonEmptyString(args?.name, DEFAULT_NAME);
  const requireFields = Array.isArray(args?.requireFields)
    ? args.requireFields.filter(
        (value): value is string => typeof value === "string" && value.length > 0,
      )
    : [];

  await client.tools.assertVisibleWithAccessibilityText({ accessibilityText: name });

  if (requireFields.length === 0) {
    return `Verified contact "${name}" detail screen rendered.`;
  }

  const missing: string[] = [];
  for (const field of requireFields) {
    if (!(await textIsVisible(client, field))) {
      missing.push(field);
    }
  }
  if (missing.length > 0) {
    throw new Error(
      `contacts_ios_verifyContactStructure: contact "${name}" missing fields: ${missing.join(", ")}.`,
    );
  }
  return `Verified contact "${name}" with fields [${requireFields.join(", ")}].`;
}
