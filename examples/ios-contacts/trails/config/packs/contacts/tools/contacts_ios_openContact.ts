import type { TrailblazeClient, TrailblazeContext } from "@trailblaze/scripting";
import {
  nonEmptyString,
  requireSessionContext,
} from "./contacts_ios_shared";

const DEFAULT_CONTACT_NAME = "John Appleseed";

export interface OpenContactArgs {
  /** Visible name of the contact to open (e.g. "Albert Einstein"). */
  name?: string;
  /**
   * Visible text to assert on the contact detail screen after opening. Defaults
   * to `name`, which works for the common case where the contact's display
   * name is what shows in the navbar.
   */
  expectedHeading?: string;
}

/**
 * Trailhead tool: opens a known contact by name. Composes the search tool so
 * the iOS Contacts pull-down-to-search interaction stays in one place — this
 * tool just states the workflow ("get me to this contact") and delegates the
 * how.
 *
 * If the contact doesn't exist the underlying search will throw, which is the
 * desired behavior — callers who want a "create if missing" pattern should
 * wrap this in their own scripted tool with a `tryOrFalse` branch.
 */
export async function contacts_ios_openContact(
  args: OpenContactArgs,
  ctx: TrailblazeContext | undefined,
  client: TrailblazeClient,
): Promise<string> {
  requireSessionContext(ctx);

  const name = nonEmptyString(args?.name, DEFAULT_CONTACT_NAME);
  const expectedHeading = nonEmptyString(args?.expectedHeading, name);

  // Search owns the search-and-tap dance; we own the post-open assertion. The
  // search tool deliberately doesn't assert on a destination heading so callers
  // can verify against whatever string they actually care about — `name` (the
  // tap target) and `expectedHeading` (the navbar text) are independently
  // configurable here for partial-query → full-name flows.
  await client.tools.contacts_ios_searchContacts({
    query: name,
    rowText: name,
    openFirstResult: true,
  });
  await client.tools.assertVisibleWithAccessibilityText({
    accessibilityText: expectedHeading,
  });

  return `Opened contact "${name}" and verified heading "${expectedHeading}".`;
}
