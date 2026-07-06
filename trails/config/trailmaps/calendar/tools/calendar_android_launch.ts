// TypeScript orchestrator replacing the `calendar_android_launch.trailhead.yaml` sidecar. That
// yaml aliased `calendar_android_launchApp` under a shorter trailhead id — so it needs its own
// thin file rather than an inline `trailhead:` field on `calendar_android_launchApp` itself,
// which would register the trailhead under the wrong (aliased) name.

import { trailblaze } from "@trailblaze/scripting";

/** Launch Google Calendar fresh and land on the default day view. */
export const calendar_android_launch = trailblaze.tool(
  {
    supportedPlatforms: ["android"],
    requiresContext: true,
    trailhead: { to: "calendar/android/day_view" },
  },
  async (_input, ctx) => {
    return await ctx.tools.calendar_android_launchApp({});
  },
);
