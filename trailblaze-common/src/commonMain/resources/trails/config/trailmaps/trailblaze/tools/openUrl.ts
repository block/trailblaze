import { trailblaze } from "@trailblaze/scripting";

interface OpenUrlInput {
  /** The URL to open that starts with https */
  url: string;
}

/**
 * Opens the browser to the provided URL.
 *
 * Android fires a `VIEW` intent in the `BROWSABLE` category with `am start -W` through
 * `android_adbShell`, which runs both from the host and on the device; the category limits it to
 * the activities a browser link may open, as a tapped link would, and `-W` returns once the
 * handling app has drawn its first frame. iOS uses `ios_openUrl`, the iOS
 * driver's own open-link action. Either way the OS routes the URL to whichever app handles it.
 *
 * Web (Playwright) is intentionally out of scope: `openUrl` is not part of any web toolset
 * (`navigation` / `core_interaction` declare android + ios-host drivers only).
 *
 * This does NOT interpolate `{{var}}` in the URL. Current callers pass literal URLs;
 * add `ctx.memory.interpolate(input.url)` if a trail ever needs a tokenized one.
 */
export const openUrl = trailblaze.tool<OpenUrlInput>(
  { description: "Opens the browser to the provided url.", supportedPlatforms: ["android", "ios"] },
  async (input, ctx) => {
    const url = String(input.url ?? "").trim();
    if (!url) {
      throw new Error("openUrl requires a non-empty `url` argument.");
    }
    if (ctx.device.platform === "ios") {
      await ctx.tools.ios_openUrl({ url });
      return `Opened ${url}`;
    }
    // `am start` exits 0 even when no activity resolves the intent and reports the failure on
    // stdout instead, so read it.
    const output = await ctx.tools.android_adbShell({
      command: ["am", "start", "-W", "-a", "android.intent.action.VIEW", "-c", "android.intent.category.BROWSABLE", "-d", url],
    });
    if (/^Error:/m.test(output)) {
      throw new Error(`openUrl could not open ${url}: ${output.trim()}`);
    }
    return `Opened ${url}`;
  },
);
