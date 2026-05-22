import type { TrailblazeClient, TrailblazeContext } from "@trailblaze/scripting";
import {
  CONTACTS_APP_ID,
  LABELS,
  ensureContactsRoot,
  requireSessionContext,
  tryOrFalse,
} from "./contacts_ios_shared";

export interface OpenAppArgs {
  /**
   * When true (default), tries to dismiss any keyboard left over from a prior
   * run by tapping the search/cancel chip. Safe to set false if you know the
   * app launches into a clean state.
   */
  dismissKeyboard?: boolean;
}

/**
 * Trailhead tool: force-restarts the Contacts app and asserts the list root is
 * visible. Gives downstream steps a deterministic starting point regardless of
 * what state a prior run left the app in (mid-edit, in search, on a sub-tab).
 *
 * Force-restart is deliberate — iOS will otherwise restore the app's last
 * scene, which on Contacts often means a still-open draft from the previous
 * trail. Restarting from scratch guarantees the same UI structure every run.
 */
export async function contacts_ios_openApp(
  args: OpenAppArgs,
  ctx: TrailblazeContext | undefined,
  client: TrailblazeClient,
): Promise<string> {
  requireSessionContext(ctx);
  const dismissKeyboard = args?.dismissKeyboard !== false;

  await ensureContactsRoot(client);

  let keyboardHandled = false;
  if (dismissKeyboard) {
    keyboardHandled = await tryOrFalse(() =>
      client.tools.contacts_ios_dismissKeyboardIfPresent({}),
    );
  }

  const suffix = !dismissKeyboard
    ? ""
    : keyboardHandled
      ? " (keyboard handled)"
      : " (no keyboard shown)";
  return `Opened ${CONTACTS_APP_ID} and verified "${LABELS.contactsListTitle}"${suffix}.`;
}
