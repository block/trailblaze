import { trailblaze } from "@trailblaze/scripting";
import { filterNonEmptyStrings, nonEmptyString } from "./contacts_ios_shared";

export interface SearchAndVerifyArgs {
  /** Name to search for. */
  query?: string;
  /** Name of the result row to open and expect on its detail screen. Defaults to `query`. */
  expectedName?: string;
  /** Field labels the contact must show, e.g. "phone". Empty skips field checks. */
  requireFields?: string[];
}

/**
 * Search Contacts for a name, open the first match, and verify its detail
 * screen shows the name and any required fields, in one call.
 */
// Composition pattern (not LLM-facing):
// Does NOT call any iOS-primitive tool directly — delegates to two existing
// scripted tools in this trailmap:
//   1. `contacts_ios_searchContacts` — types + taps the first match.
//   2. `contacts_ios_verifyContactStructure` — asserts name + (optional) fields.
//
// Any scripted tool registered on the trailmap is reachable through
// `ctx.tools.<toolName>(args)` from any *other* scripted tool in the same
// trailmap. That lets you build small focused primitives once, then assemble
// higher-level workflows without copying their bodies. Each sub-tool's selector
// knowledge, retry behavior, and assertion shape stays in one place; this
// wrapper only chooses *which* primitives to run and what args to pass.
//
// The agent gets a single tool-call worth of latency from its perspective —
// the composition all happens inside one QuickJS invocation, no extra LLM
// round-trip per sub-tool.
export const contacts_ios_searchAndVerify = trailblaze.tool<SearchAndVerifyArgs>(
  { supportedPlatforms: ["ios"], requiresContext: true },
  async (input, ctx) => {
    const query = nonEmptyString(input?.query, "John Appleseed");
    const expectedName = nonEmptyString(input?.expectedName, query);
    const requireFields = filterNonEmptyStrings(input?.requireFields);

    await ctx.tools.contacts_ios_searchContacts({
      query,
      rowText: expectedName,
      openFirstResult: true,
    });

    await ctx.tools.contacts_ios_verifyContactStructure({
      name: expectedName,
      requireFields,
    });

    return requireFields.length > 0
      ? `Searched for "${query}", opened "${expectedName}", and verified fields [${requireFields.join(", ")}].`
      : `Searched for "${query}", opened "${expectedName}", and verified detail screen.`;
  },
);
