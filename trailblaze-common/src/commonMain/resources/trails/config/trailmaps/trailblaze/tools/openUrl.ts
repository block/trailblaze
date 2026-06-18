// openUrl — TypeScript conversion of the former Kotlin `OpenUrlTrailblazeTool`.
//
// Opens a URL by handing Maestro the same `openLink` command the Kotlin tool emitted, via the
// framework `maestro` tool — reachable from scripted tools through the type-safe
// `trailblaze.tools.maestro(...)` binding (maestro is `surfaceToScriptedTools = true` and resolves
// through the unfiltered framework-tool dispatch even though it isn't in any toolset). Maestro
// implements `openLink` per platform, so a single call covers Android and iOS with no per-platform
// branch.
//
// Web (Playwright) is intentionally out of scope: `openUrl` is not part of any web toolset
// (`navigation` / `core_interaction` declare android + ios-host drivers only), so the web path the
// old Kotlin tool had in `PlaywrightTrailblazeAgent` was never reachable via normal tool advertising.
//
// Unlike the Kotlin tool, this does NOT interpolate `{{var}}` in the URL — the on-device
// `@trailblaze/tools` context exposes no memory snapshot. Both committed `openUrl` trails pass
// literal URLs; revisit if a trail ever needs an interpolated URL here.
import { trailblaze } from "@trailblaze/tools";

trailblaze.tool(
  "openUrl",
  {
    description: "Opens the browser to the provided url.",
    inputSchema: {
      type: "object",
      properties: {
        url: {
          type: "string",
          description: "The URL to open that starts with https",
        },
      },
      required: ["url"],
    },
    _meta: { "trailblaze/supportedPlatforms": ["android", "ios"] },
  },
  async (args) => {
    const url = String((args.url as string | undefined) ?? "").trim();
    if (!url) {
      return {
        content: [{ type: "text" as const, text: "openUrl requires a non-empty `url` argument." }],
        isError: true,
      };
    }
    await trailblaze.tools.maestro({ commands: [{ openLink: url }] });
    return { content: [{ type: "text" as const, text: `Opened ${url}` }] };
  },
);
