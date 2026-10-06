import { trailblaze } from "@trailblaze/scripting";
import {
  CONTACTS_APP_ID,
  LABELS,
  ensureContactsRoot,
  tryOrFalse,
} from "./contacts_ios_shared";

export interface OpenAppArgs {
  /** Dismiss a keyboard left over from a prior run. Default true. */
  dismissKeyboard?: boolean;
}

/**
 * Force-restart the iOS Contacts app, discarding any leftover draft or search,
 * and verify the contacts list is showing.
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
