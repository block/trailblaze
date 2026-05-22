import type { TrailblazeClient, TrailblazeContext } from "@trailblaze/scripting";
import {
  LABELS,
  requireSessionContext,
  textIsVisible,
  tryOrFalse,
} from "./contacts_ios_shared";

export type DismissKeyboardIfPresentArgs = Record<string, never>;

/**
 * Conditional UI handler: if the iOS soft-keyboard is visible, dismiss it;
 * otherwise no-op. Returning success in both branches lets callers compose this
 * tool unconditionally at the top of a workflow without sprinkling try/catch
 * at every call site.
 *
 * iOS doesn't expose a reliable "is the keyboard up?" property on the host
 * driver, so this probes for the "return" / "search" Cancel chip that Contacts
 * surfaces alongside the keyboard. If that's visible, tapping it collapses
 * the keyboard back into the navigation chrome.
 *
 * Critical pattern for iOS — the keyboard occludes hit-testing for any view it
 * overlaps, so leaving it up from a previous step often breaks the next tap.
 */
export async function contacts_ios_dismissKeyboardIfPresent(
  _args: DismissKeyboardIfPresentArgs,
  ctx: TrailblazeContext | undefined,
  client: TrailblazeClient,
): Promise<string> {
  requireSessionContext(ctx);

  if (!(await textIsVisible(client, LABELS.searchCancel))) {
    return "No keyboard visible — nothing to dismiss.";
  }

  const dismissed = await tryOrFalse(() =>
    client.tools.tapOnElementWithText({ text: LABELS.searchCancel }),
  );
  return dismissed
    ? "Dismissed iOS keyboard via Cancel chip."
    : "Cancel chip disappeared between probe and tap — no action taken.";
}
