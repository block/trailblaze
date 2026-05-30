import { trailblaze } from "@trailblaze/scripting";
import { filterNonEmptyStrings, nonEmptyString } from "./contacts_ios_shared";

export interface SearchAndVerifyArgs {
  /** Name to search for. */
  query?: string;
  /**
   * Visible name expected on the opened contact's detail screen. Defaults to
   * `query`; pass explicitly when the query is a prefix and the destination
   * row carries a fuller name.
   */
  expectedName?: string;
  /**
   * Optional list of field labels the contact must surface. Forwarded to
   * `contacts_ios_verifyContactStructure`. Empty list skips field
   * assertions.
   */
  requireFields?: string[];
}

/**
 * Search Contacts for a name AND verify the resulting contact's detail screen
 * is well-formed in a single tool call. Use this whenever the task combines
 * "search for X" with "verify the contact has Y", e.g. "search for Albert
 * Einstein and confirm the contact has a phone number". Composes the
 * existing `contacts_ios_searchContacts` and
 * `contacts_ios_verifyContactStructure` tools so callers get both behaviors
 * with one round-trip.
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
