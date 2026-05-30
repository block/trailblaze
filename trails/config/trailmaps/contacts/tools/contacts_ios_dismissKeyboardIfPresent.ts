import { trailblaze } from "@trailblaze/scripting";
import { LABELS, textIsVisible, tryOrFalse } from "./contacts_ios_shared";

/**
 * Dismiss any iOS soft-keyboard that's left visible. Use this whenever the task
 * is to close the keyboard, hide the keyboard, dismiss the search, or clear
 * the on-screen typing affordance. If no keyboard is currently visible the
 * tool is a no-op that returns successfully, so it's safe to call
 * unconditionally between steps that type into a field and steps that need to
 * tap something the keyboard would otherwise occlude.
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
