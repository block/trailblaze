import { trailblaze } from "@trailblaze/scripting";
import { nonEmptyString } from "./wikipedia_shared";

export interface SearchAndVerifyArgs {
  /** Query to type into the header search box. */
  query?: string;
  /**
   * Heading text to assert on the opened article. Defaults to `query`, which
   * works for exact-match articles like "Python (programming language)".
   */
  expectedHeading?: string;
  /**
   * Whether to also scroll-and-assert the References section is present.
   * Defaults to false because not every article has one.
   */
  requireReferences?: boolean;
}

/**
 * Search Wikipedia for a topic AND verify the resulting article is
 * well-formed — heading + body, optionally References — in a single tool
 * call. Use this whenever the task combines "search for X" with "verify
 * the article rendered correctly", e.g. "search Wikipedia for Python and
 * confirm the article loaded with a body and References section". Composes
 * the existing `wikipedia_web_searchAndOpenFirstResult` and
 * `wikipedia_web_verifyArticleStructure` tools so callers get both behaviors
 * with one round-trip.
 */
// Composition example — does NOT call any `web_*` builtin directly. Instead
// delegates to two existing scripted tools in this trailmap:
//   1. `wikipedia_web_searchAndOpenFirstResult` — types + submits + asserts the
//      destination article's heading.
//   2. `wikipedia_web_verifyArticleStructure` — asserts heading + body +
//      (optionally) References scroll.
// Any scripted tool registered on the trailmap is reachable via
// `ctx.tools.<toolName>(args)` from any *other* scripted tool in the same
// trailmap — build small focused primitives once, then assemble higher-level
// workflows without copying their bodies. The composition runs inside one
// QuickJS invocation, so the agent only pays one round-trip's worth of latency.
export const wikipedia_web_searchAndVerify = trailblaze.tool<SearchAndVerifyArgs>(
  { supportedPlatforms: ["web"], requiresContext: true },
  async (input, ctx) => {
    const query = nonEmptyString(input.query, "Trailblazer");
    const expectedHeading = nonEmptyString(input.expectedHeading, query);
    const requireReferences = input.requireReferences === true;

    await ctx.tools.wikipedia_web_searchAndOpenFirstResult({
      query,
      expectedHeading,
      openFirstResult: true,
    });

    await ctx.tools.wikipedia_web_verifyArticleStructure({
      expectedHeading,
      requireReferences,
    });

    return requireReferences
      ? `Searched for "${query}", landed on "${expectedHeading}", and verified heading+body+References.`
      : `Searched for "${query}", landed on "${expectedHeading}", and verified heading+body.`;
  },
);
