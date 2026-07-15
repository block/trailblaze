import { trailblaze } from "@trailblaze/scripting";

/**
 * Launch the Contacts app on whichever platform the session's device runs, landing on
 * the platform's contacts list. Cross-platform front door: trails and agents call this
 * one tool and the platform impls (`contacts_ios_launchApp`, `contacts_android_launchApp`)
 * do the work.
 *
 * The trailhead metadata intentionally stays on the platform impls rather than here:
 * each platform lands on a different waypoint (`contacts/ios/list` vs
 * `contacts/android/list-populated`), and a single cross-platform trailhead would have
 * no fixed destination — the map would render it as a dynamic trailhead with no entry
 * edge, un-anchoring the very layout the per-platform trailheads exist to anchor.
 */
export const contacts_launchApp = trailblaze.tool(
  {
    supportedPlatforms: ["android", "ios"],
    requiresContext: true,
  },
  async (_input, ctx) => {
    switch (ctx.device?.platform) {
      case "ios":
        return await ctx.tools.contacts_ios_launchApp({});
      case "android":
        return await ctx.tools.contacts_android_launchApp({});
      default:
        throw new Error(
          `contacts_launchApp supports android and ios; session device platform is ${ctx.device?.platform ?? "unknown"}.`,
        );
    }
  },
);
