import { trailblaze } from "@trailblaze/scripting";
import { LABELS, textIsVisible, tryOrFalse } from "./contacts_ios_shared";

/**
 * Dismiss the iOS keyboard by tapping Cancel, which also exits search; does
 * nothing if Cancel isn't showing. Not for a new/edit contact form, where
 * Cancel discards the form.
 */
// Implementation notes:
// iOS doesn't expose a reliable "is the keyboard up?" property on the host
// driver, so this probes for the "return" / "search" Cancel chip that Contacts
// surfaces alongside the keyboard. If that's visible, tapping it collapses the
// keyboard back into the navigation chrome.
//
// Critical pattern for iOS — the keyboard occludes hit-testing for any view it
// overlaps, so leaving it up from a previous step often breaks the next tap.
export const contacts_ios_dismissKeyboardIfPresent = trailblaze.tool(
  { supportedPlatforms: ["ios"], requiresContext: true },
  async (_input, ctx) => {
    if (!(await textIsVisible(ctx, LABELS.searchCancel))) {
      return "No keyboard visible — nothing to dismiss.";
    }

    const dismissed = await tryOrFalse(() =>
      ctx.tools.tapOnElementWithText({ text: LABELS.searchCancel }),
    );
    return dismissed
      ? "Dismissed iOS keyboard via Cancel chip."
      : "Cancel chip disappeared between probe and tap — no action taken.";
  },
);
