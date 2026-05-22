import type { TrailblazeClient, TrailblazeContext } from "@trailblaze/scripting";
import {
  ensureContactsRoot,
  nonEmptyString,
  requireSessionContext,
  textIsVisible,
} from "./contacts_ios_shared";

const DEFAULT_QUERY = "John";

export interface SearchContactsArgs {
  /** Query to type into the contacts list's pull-down search field. */
  query?: string;
  /**
   * Row text the tool taps after typing the query. Defaults to `query`. Pass
   * this explicitly when the query is a partial prefix of the row's visible
   * label (e.g. `query="alb"` + `rowText="Albert Einstein"`).
   */
  rowText?: string;
  /**
   * When true (default), taps the first visible matching row to open the
   * contact's detail screen. When false, leaves the search active so a caller
   * can verify the autocomplete-style suggestion list itself.
   */
  openFirstResult?: boolean;
}

/**
 * Drives the iOS Contacts pull-down search field. Two branches:
 *   1. `openFirstResult` true (default) — types the query and taps the first
 *      row matching `rowText`. Does NOT post-assert any specific heading; the
 *      caller (e.g. `contacts_ios_openContact`) owns the destination check.
 *   2. `openFirstResult` false — types the query but doesn't tap, so a caller
 *      can subsequently verify the inline suggestion list contents.
 *
 * Force-restarts Contacts via `ensureContactsRoot` first, so the swipe-down
 * gesture happens against a known list-root state rather than whatever
 * sub-screen a previous step might have left visible. Pulling search into a
 * separate "land on list, then search" primitive keeps the gesture sequence
 * stable across composing flows.
 *
 * When `openFirstResult: true` and the query yields no matches, the tool
 * throws a descriptive error rather than letting the subsequent tap fail with
 * a generic "element not found" — the no-results banner is the explicit
 * signal so callers can distinguish "wrong query" from "wrong row text".
 */
export async function contacts_ios_searchContacts(
  args: SearchContactsArgs,
  ctx: TrailblazeContext | undefined,
  client: TrailblazeClient,
): Promise<string> {
  requireSessionContext(ctx);

  const query = nonEmptyString(args?.query, DEFAULT_QUERY);
  const rowText = nonEmptyString(args?.rowText, query);
  const openFirstResult = args?.openFirstResult !== false;

  await ensureContactsRoot(client);

  // iOS Contacts hides the search field above the list root. A swipe-down
  // gesture scrolls it into view; once visible, the input is tappable by its
  // "Search" accessibility label and accepts text.
  await client.tools.swipe({ direction: "DOWN" });
  await client.tools.tapOnElementWithText({ text: "Search" });
  await client.tools.inputText({ text: query });

  if (!openFirstResult) {
    return `Typed "${query}" into Contacts search and stopped (no result tapped).`;
  }

  // Pre-flight: if iOS rendered the "No Results" banner, the next tap will
  // fail with a generic "element not found". Surface the no-results state
  // directly so the error message reflects the real cause.
  if (await textIsVisible(client, "No Results")) {
    throw new Error(
      `contacts_ios_searchContacts: query "${query}" returned no results.`,
    );
  }

  await client.tools.tapOnElementWithText({ text: rowText });
  return `Searched for "${query}" and opened the row matching "${rowText}".`;
}
