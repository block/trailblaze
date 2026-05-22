import type { TrailblazeClient, TrailblazeContext } from "@trailblaze/scripting";
import { nonEmptyString, requireSessionContext } from "./wikipedia_shared";

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
 * Composition example — does NOT call any `web_*` builtin directly. Instead,
 * delegates to two existing scripted tools in this pack:
 *
 *   1. `wikipedia_web_searchAndOpenFirstResult` — types + submits + asserts
 *      the destination article's heading.
 *   2. `wikipedia_web_verifyArticleStructure` — asserts heading + body +
 *      (optionally) References scroll.
 *
 * Why this pattern matters: any scripted tool registered on the pack is
 * reachable through `client.tools.<toolName>(args)` from any *other* scripted
 * tool in the same pack. That lets you build small focused primitives once,
 * then assemble higher-level workflows without copying their bodies. Each
 * sub-tool's selector knowledge, retry behavior, and assertion shape stays in
 * one place; this wrapper only chooses *which* primitives to run and what
 * args to pass.
 *
 * The agent gets a single tool-call worth of latency from its perspective —
 * the composition all happens inside one QuickJS invocation, no extra LLM
 * round-trip per sub-tool.
 */
export async function wikipedia_web_searchAndVerify(
  args: SearchAndVerifyArgs,
  ctx: TrailblazeContext | undefined,
  client: TrailblazeClient,
): Promise<string> {
  requireSessionContext(ctx);

  const query = nonEmptyString(args?.query, "Trailblazer");
  const expectedHeading = nonEmptyString(args?.expectedHeading, query);
  const requireReferences = args?.requireReferences === true;

  await client.tools.wikipedia_web_searchAndOpenFirstResult({
    query,
    expectedHeading,
    openFirstResult: true,
  });

  await client.tools.wikipedia_web_verifyArticleStructure({
    expectedHeading,
    requireReferences,
  });

  return requireReferences
    ? `Searched for "${query}", landed on "${expectedHeading}", and verified heading+body+References.`
    : `Searched for "${query}", landed on "${expectedHeading}", and verified heading+body.`;
}
