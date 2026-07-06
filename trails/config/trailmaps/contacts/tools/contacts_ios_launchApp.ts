// TypeScript orchestrator replacing the `contacts_ios_launchApp.trailhead.yaml` sidecar. That
// yaml wrapped the framework's generic built-in `launchApp` tool with fixed args under a new id —
// `launchApp` itself launches any app, so a trailhead's fixed destination can't live on its own
// spec — so it needs its own thin file. Mirrors the sibling `contacts_android_launchApp.ts`.

import { trailblaze } from "@trailblaze/scripting";

const APP_ID = "com.apple.MobileAddressBook";

/**
 * Launch the iOS Contacts app and land on the top-level alphabetical contacts list, regardless of
 * which screen the device is currently on. Use as the first step of any Contacts iOS trail.
 */
export const contacts_ios_launchApp = trailblaze.tool(
  {
    supportedPlatforms: ["ios"],
    requiresContext: true,
    trailhead: { to: "contacts/ios/list" },
  },
  async (_input, ctx) => {
    await ctx.tools.launchApp({ appId: APP_ID, launchMode: "FORCE_RESTART" });
    return `Launched ${APP_ID} (FORCE_RESTART).`;
  },
);
