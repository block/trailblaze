import { trailblaze } from "@trailblaze/scripting";

/**
 * Force-stops the Google Contacts app and re-launches it via the package's default
 * launcher activity, so the next step starts from a clean app state. Equivalent to a
 * Maestro `launchApp({ launchMode: FORCE_RESTART })` against `com.google.android.contacts`,
 * but composed entirely from the dual-mode `android_adbShell` primitive so the same tool
 * works on host- and on-device-dispatched scripted-tool sessions.
 *
 * Use this as the first step of any contacts trail that wants a fresh launch state. No
 * arguments — the framework resolves the app id from the `contacts` trailmap manifest's
 * `app_ids:` list against installed apps on the connected device.
 */
// Implementation notes — see the sibling clock_android_launchApp.ts for the full rationale
// on `android_adbShell` over the Maestro-shaped `launchApp`, on `am start` over `monkey`,
// and on the `ctx.target?.resolveAppId({ defaultAppId })` resolution order. `android_adbShell`
// is dual-mode (`requiresHost: false`), so this tool composes cleanly whether the daemon
// dispatches it on host or, in the future, on-device. `requiresContext: true` guarantees
// `ctx`; we still optional-chain `ctx.target` for target-less sessions.
export const contacts_android_launchApp = trailblaze.tool(
  { supportedPlatforms: ["android"], requiresContext: true },
  async (_input, ctx) => {
    const appId = ctx.target?.resolveAppId({ defaultAppId: "com.google.android.contacts" });
    if (!appId) {
      throw new Error("contacts_android_launchApp could not resolve an Android app id from ctx.target.");
    }

    await ctx.tools.android_adbShell({
      command: ["am", "force-stop", appId],
    });
    await ctx.tools.android_adbShell({
      command: [
        "am", "start",
        "-a", "android.intent.action.MAIN",
        "-c", "android.intent.category.LAUNCHER",
        "-p", appId,
      ],
    });

    return `Launched ${appId} (force-stop + am start MAIN/LAUNCHER).`;
  },
);
