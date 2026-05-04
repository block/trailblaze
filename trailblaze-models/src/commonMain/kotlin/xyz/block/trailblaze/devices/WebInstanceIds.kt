package xyz.block.trailblaze.devices

/**
 * Well-known instance IDs for [TrailblazeDevicePlatform.WEB] devices.
 *
 * Web devices are virtual — any non-empty instance ID can be provisioned on
 * demand by the MCP bridge so that multiple parallel CLI commands can each
 * own their own Playwright browser. The constants here are reserved IDs the
 * runtime treats specially:
 *
 * - [PLAYWRIGHT_NATIVE]: the singleton browser that the desktop app launches
 *   via "Launch Browser" and that the CLI defaults to when only `--device web`
 *   is given (no instance suffix). Other IDs (e.g. `--device web/foo`) get
 *   their own browser instances independent of this one.
 */
object WebInstanceIds {
  const val PLAYWRIGHT_NATIVE: String = "playwright-native"
  const val PLAYWRIGHT_ELECTRON: String = "playwright-electron"
}
