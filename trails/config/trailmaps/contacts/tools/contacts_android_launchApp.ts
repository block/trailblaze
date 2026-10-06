import { trailblaze } from "@trailblaze/scripting";

/**
 * Force-stop and relaunch Google Contacts. App data is kept.
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
