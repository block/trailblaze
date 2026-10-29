import { trailblaze } from "@trailblaze/scripting";

/**
 * Launch the Goose desktop app (Electron) and confirm it is connected and ready on the home / chat
 * screen.
 */
// The framework launches the Goose Electron app (see `target.electron:` in trailmap.yaml) and
// connects over CDP before this runs; `playwright_desktop_launchGoose` then confirms the connection
// is live and the app is ready. Use it as a trail's trailhead so a test starts from a known
// launched state wherever the agent currently is.
export const goose_launchDesktop = trailblaze.trailhead(
  {
    supportedPlatforms: ["web"],
    requiresHost: true,
    to: "goose/home",
  },
  async (_input, ctx) => {
    return await ctx.tools.playwright_desktop_launchGoose({});
  },
);
