package xyz.block.trailblaze.host.playwright

import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.playwright.tools.WebToolSetIds
import xyz.block.trailblaze.toolcalls.TrailblazeToolSetCatalog

/**
 * Resolved Playwright tool classes used for recording generation. Called from both the Native
 * and Electron descriptors; each passes its own driver type so the resolution is explicit at the
 * call site. Today the two drivers resolve to identical classes (pinned by
 * `WebToolSetCatalogTest`), but the parameter keeps this correct if the YAMLs ever diverge.
 */
internal fun resolveWebToolClasses(driverType: TrailblazeDriverType) = TrailblazeToolSetCatalog
  .resolveForDriver(driverType, WebToolSetIds.ALL)
  .toolClasses
