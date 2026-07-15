import { trailblaze } from "@trailblaze/scripting";

/**
 * Open a contact's QuickContact card from any device state via the system `ACTION_VIEW`
 * contacts intent, resolving the lowest contact `_id` on-device so no id needs to be
 * known up front. Force-stops the app first so the card opens fresh.
 *
 * Trailhead: lands on `contacts/android/contact-detail`. Requires at least one contact
 * in the device DB (the living dataset's seeded contacts satisfy this).
 */
// Composed from the dual-mode `android_adbShell` primitive like the sibling
// `contacts_android_launchApp` (see there for the rationale). The id lookup runs inside
// a single on-device `sh -c` pipeline because `android_adbShell` consumers don't parse
// stdout host-side — `content query` orders by `_id`, grep/cut extract the first id, and
// `am start` views it. `content://com.android.contacts` is the ContactsContract
// authority (stable across contacts apps), not the app package; the intent is pinned to
// the resolved package with `-p` so a second contacts app never surfaces a chooser.
export const contacts_android_openContactCard = trailblaze.tool(
  {
    supportedPlatforms: ["android"],
    requiresContext: true,
    trailhead: { to: "contacts/android/contact-detail" },
  },
  async (_input, ctx) => {
    const appId = ctx.target?.resolveAppId({ defaultAppId: "com.google.android.contacts" });
    if (!appId) {
      throw new Error("contacts_android_openContactCard could not resolve an Android app id from ctx.target.");
    }

    await ctx.tools.android_adbShell({
      command: ["am", "force-stop", appId],
    });
    await ctx.tools.android_adbShell({
      command: [
        "sh", "-c",
        'am start -a android.intent.action.VIEW ' +
          '-d "content://com.android.contacts/contacts/$(' +
          'content query --uri content://com.android.contacts/contacts --projection _id --sort "_id ASC"' +
          ' | grep -oE "_id=[0-9]+" | head -1 | cut -d= -f2)" ' +
          `-p ${appId}`,
      ],
    });

    return `Opened the first contact's card in ${appId} (ACTION_VIEW).`;
  },
);
