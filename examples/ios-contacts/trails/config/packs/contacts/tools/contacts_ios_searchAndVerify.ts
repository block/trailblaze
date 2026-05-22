import type { TrailblazeClient, TrailblazeContext } from "@trailblaze/scripting";
import { nonEmptyString, requireSessionContext } from "./contacts_ios_shared";

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
 * Composition example — does NOT call any iOS-primitive tool directly. Instead,
 * delegates to two existing scripted tools in this pack:
 *
 *   1. `contacts_ios_searchContacts` — types + taps the first match.
 *   2. `contacts_ios_verifyContactStructure` — asserts name + (optional) fields.
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
export async function contacts_ios_searchAndVerify(
  args: SearchAndVerifyArgs,
  ctx: TrailblazeContext | undefined,
  client: TrailblazeClient,
): Promise<string> {
  requireSessionContext(ctx);

  const query = nonEmptyString(args?.query, "John Appleseed");
  const expectedName = nonEmptyString(args?.expectedName, query);
  const requireFields = Array.isArray(args?.requireFields)
    ? args.requireFields.filter(
        (value): value is string => typeof value === "string" && value.length > 0,
      )
    : [];

  await client.tools.contacts_ios_searchContacts({
    query,
    rowText: expectedName,
    openFirstResult: true,
  });

  await client.tools.contacts_ios_verifyContactStructure({
    name: expectedName,
    requireFields,
  });

  return requireFields.length > 0
    ? `Searched for "${query}", opened "${expectedName}", and verified fields [${requireFields.join(", ")}].`
    : `Searched for "${query}", opened "${expectedName}", and verified detail screen.`;
}
