import { trailblaze } from "@trailblaze/scripting";

/**
 * Jump straight into a blank "Create new contact" editor from any device state via the
 * system `ACTION_INSERT` contacts intent — the same entry other apps use for
 * "add to contacts". Force-stops the app first so the editor opens fresh rather than
 * on top of stale in-app state.
 *
 * Trailhead: lands on `contacts/android/new-contact-editor`.
 */
// Composed from the dual-mode `android_adbShell` primitive like the sibling
// `contacts_android_launchApp` (see there for the rationale). The intent is pinned to the
// resolved contacts package with `-p` so devices with more than one contacts app never
// surface a disambiguation chooser.
export const contacts_android_newContact = trailblaze.tool(
  {
    supportedPlatforms: ["android"],
    requiresContext: true,
    trailhead: { to: "contacts/android/new-contact-editor" },
  },
  async (_input, ctx) => {
    const appId = ctx.target?.resolveAppId({ defaultAppId: "com.google.android.contacts" });
    if (!appId) {
      throw new Error("contacts_android_newContact could not resolve an Android app id from ctx.target.");
    }

    await ctx.tools.android_adbShell({
      command: ["am", "force-stop", appId],
    });
    await ctx.tools.android_adbShell({
      command: [
        "am", "start",
        "-a", "android.intent.action.INSERT",
        "-t", "vnd.android.cursor.dir/contact",
        "-p", appId,
      ],
    });

    return `Opened the new-contact editor in ${appId} (ACTION_INSERT).`;
  },
);
