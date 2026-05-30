import { trailblaze } from "@trailblaze/scripting";
import { filterNonEmptyStrings, nonEmptyString, textIsVisible } from "./contacts_ios_shared";

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
 * Verify the currently-open iOS contact detail screen conforms to an expected
 * shape — name heading visible, plus an optional list of required field
 * labels ("phone", "email", "home", etc.). Use this whenever the task is to
 * confirm a contact rendered correctly, check a contact has certain fields,
 * or assert a contact's shape. Pass an empty `requireFields` list for a
 * lightweight "did the detail screen render?" check.
 */
// Implementation notes:
// Composite assertion against the currently-open contact's detail screen.
// Branches by which fields the caller demands:
//   1. `name` heading visible — every contact has one. Always asserted.
//   2. For each entry in `requireFields`, asserts a row with that label text
//      is visible. Each field has its own assertion so individual failures
//      map back to a specific missing row.
export const contacts_ios_verifyContactStructure = trailblaze.tool<VerifyContactStructureArgs>(
  { supportedPlatforms: ["ios"], requiresContext: true },
  async (input, ctx) => {
    const name = nonEmptyString(input?.name, DEFAULT_NAME);
    const requireFields = filterNonEmptyStrings(input?.requireFields);

    await ctx.tools.assertVisibleWithAccessibilityText({ accessibilityText: name });

    if (requireFields.length === 0) {
      return `Verified contact "${name}" detail screen rendered.`;
    }

    const missing: string[] = [];
    for (const field of requireFields) {
      if (!(await textIsVisible(ctx, field))) {
        missing.push(field);
      }
    }
    if (missing.length > 0) {
      throw new Error(
        `contacts_ios_verifyContactStructure: contact "${name}" missing fields: ${missing.join(", ")}.`,
      );
    }
    return `Verified contact "${name}" with fields [${requireFields.join(", ")}].`;
  },
);
