@file:JvmName("Trailblaze")

package xyz.block.trailblaze.desktop

import xyz.block.trailblaze.cli.TrailblazeCli

/**
 * Open source Trailblaze desktop application entry point.
 * 
 * Uses the shared CLI infrastructure from trailblaze-host.
 */
fun main(args: Array<String>) {
  TrailblazeCli.run(
    args = args,
    appProvider = { OpenSourceTrailblazeDesktopApp() },
    configProvider = { OpenSourceTrailblazeDesktopAppConfig() },
  )
}
