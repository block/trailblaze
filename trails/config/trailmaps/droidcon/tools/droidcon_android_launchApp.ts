import { trailblaze } from "@trailblaze/scripting";

/**
 * Fully resets the Droidcon events app and re-launches it via its explicit main activity,
 * so the next step starts from a clean app state on the event-list home screen.
 *
 * Three deliberate choices, each paid for during authoring:
 * - `pm clear` (not just `am force-stop`): the app persists the last-selected event and
 *   resumes straight into that event's agenda on relaunch, so a plain restart does NOT
 *   reliably land on the event-list home. Clearing app data does.
 * - `pm grant android.permission.POST_NOTIFICATIONS` after the clear: a cleared app
 *   re-triggers the notification-permission dialog on first launch; pre-granting keeps
 *   the dialog out of every trail (grant AFTER clear — clearing wipes prior grants).
 * - Explicit `-n <pkg>/.MainActivity` rather than the implicit MAIN/LAUNCHER `-p <pkg>`
 *   form: this app's launcher intent filter resolves with `isDefault=false`, so the
 *   implicit form fails with "unable to resolve Intent".
 *
 * Use this as the first step of any droidcon trail that wants a fresh launch state. No
 * arguments — the framework resolves the app id from the `droidcon` trailmap manifest's
 * `app_ids:` list against installed apps on the connected device.
 *
 * Trailhead: lands on the event-list home (`droidcon/android/home`) — search bar,
 * Upcoming/Past Events lists, and the Droidcon/FlutterCon/T3 top-level tab row.
 */
export const droidcon_android_launchApp = trailblaze.tool(
  {
    supportedPlatforms: ["android"],
    requiresContext: true,
    trailhead: { to: "droidcon/android/home" },
  },
  async (_input, ctx) => {
    const appId = ctx.target?.resolveAppId({ defaultAppId: "com.droidcon.droidconapp" });
    if (!appId) {
      throw new Error("droidcon_android_launchApp could not resolve an Android app id from ctx.target.");
    }

    await ctx.tools.android_adbShell({
      command: ["pm", "clear", appId],
    });
    await ctx.tools.android_adbShell({
      command: ["pm", "grant", appId, "android.permission.POST_NOTIFICATIONS"],
    });
    await ctx.tools.android_adbShell({
      command: ["am", "start", "-n", `${appId}/.MainActivity`],
    });

    return `Launched ${appId} fresh (pm clear + notification pre-grant + am start -n ${appId}/.MainActivity).`;
  },
);
