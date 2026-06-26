import { trailblaze } from "@trailblaze/scripting";

/**
 * Opens the browser to the provided URL.
 *
 * Hands Maestro the same `openLink` command the former Kotlin `OpenUrlTrailblazeTool`
 * emitted, via the framework `maestro` tool reachable from scripted tools through
 * `ctx.tools.mobile_maestro(...)` (maestro is `surfaceToScriptedTools = true` and resolves through
 * the unfiltered framework-tool dispatch even though it isn't in any toolset). Maestro
 * implements `openLink` per platform, so a single call covers Android and iOS with no
 * per-platform branch.
 *
 * Web (Playwright) is intentionally out of scope: `openUrl` is not part of any web toolset
 * (`navigation` / `core_interaction` declare android + ios-host drivers only), so the web path
 * the old Kotlin tool had in `PlaywrightTrailblazeAgent` was never reachable via normal tool
 * advertising.
 *
 * Unlike the Kotlin tool, this does NOT interpolate `{{var}}` in the URL — the in-process
 * context exposes no memory snapshot. Both committed `openUrl` trails pass literal URLs;
 * revisit if a trail ever needs an interpolated URL here.
 */
interface OpenUrlInput {
  /** The URL to open that starts with https. */
  url: string;
}

export const openUrl = trailblaze.tool<OpenUrlInput>(
  { supportedPlatforms: ["android", "ios"] },
  async (input, ctx) => {
    const url = String(input.url ?? "").trim();
    if (!url) {
      throw new Error("openUrl requires a non-empty `url` argument.");
    }
    // `maestro` isn't in the SDK's curated `TrailblazeToolMap`, so this composes via the runtime
    // `ctx.tools` Proxy rather than a statically-typed binding — the same framework-tool
    // composition convention `clock_clearAlarms.ts` uses for `ctx.tools.android_adbShell(...)`.
    await ctx.tools.mobile_maestro({ commands: [{ openLink: url }] });
    return `Opened ${url}`;
  },
);
