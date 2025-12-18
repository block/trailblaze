package xyz.block.trailblaze.ui

import xyz.block.trailblaze.desktop.TrailblazeDesktopAppConfig
import xyz.block.trailblaze.host.yaml.DesktopYamlRunner
import xyz.block.trailblaze.logs.server.TrailblazeMcpServer
import xyz.block.trailblaze.model.DesktopAppRunYamlParams

/**
 * Central Interface for The Trailblaze Desktop App
 */
abstract class TrailblazeDesktopApp(
  protected val desktopAppConfig: TrailblazeDesktopAppConfig,
) {

  abstract val desktopYamlRunner: DesktopYamlRunner

  abstract val trailblazeMcpServer: TrailblazeMcpServer

  abstract fun startTrailblazeDesktopApp()

}