import { trailblaze } from "@trailblaze/scripting";
import { filterNonEmptyStrings, nonEmptyString, textIsVisible } from "./contacts_ios_shared";

const DEFAULT_NAME = "John Appleseed";

export interface VerifyContactStructureArgs {
  /** Contact name expected in the heading. */
  name?: string;
  /**
   * Field labels the contact must show, e.g. "phone", "email". Each is a
   * regex matched against a label's whole text. Empty skips field checks.
   */
  requireFields?: string[];
}

/**
 * On an already-open iOS contact detail screen, assert the contact name and
 * each required field label are visible.
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
