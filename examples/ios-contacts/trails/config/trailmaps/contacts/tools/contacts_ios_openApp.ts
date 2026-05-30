import { trailblaze } from "@trailblaze/scripting";
import {
  CONTACTS_APP_ID,
  LABELS,
  ensureContactsRoot,
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
 * Open the iOS Contacts app from a cold start. Use this when the task is to
 * launch Contacts, open the Contacts app, go to Contacts, or otherwise begin
 * a Contacts flow. Force-restarts the app so a stale draft or in-progress
 * search from a prior run doesn't leak in. Verifies the contacts list root
 * rendered ("Contacts" navbar title visible). Optionally dismisses any leftover
 * keyboard so the next step sees the full list.
 */
// Implementation note: trailhead tool — gives downstream steps a deterministic
// starting point regardless of what state a prior run left the app in (mid-edit,
// in search, on a sub-tab). Force-restart is deliberate — iOS will otherwise
// restore the app's last scene, which on Contacts often means a still-open
// draft from the previous trail.
export const contacts_ios_openApp = trailblaze.tool<OpenAppArgs>(
  { supportedPlatforms: ["ios"], requiresContext: true },
  async (input, ctx) => {
    const dismissKeyboard = input?.dismissKeyboard !== false;

    await ensureContactsRoot(ctx);

    let keyboardHandled = false;
    if (dismissKeyboard) {
      keyboardHandled = await tryOrFalse(() =>
        ctx.tools.contacts_ios_dismissKeyboardIfPresent({}),
      );
    }

    const suffix = !dismissKeyboard
      ? ""
      : keyboardHandled
        ? " (keyboard handled)"
        : " (no keyboard shown)";
    return `Opened ${CONTACTS_APP_ID} and verified "${LABELS.contactsListTitle}"${suffix}.`;
  },
);
