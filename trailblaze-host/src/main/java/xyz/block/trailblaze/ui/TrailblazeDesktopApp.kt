package xyz.block.trailblaze.ui

import xyz.block.trailblaze.desktop.TrailblazeDesktopAppConfig
import xyz.block.trailblaze.host.devices.HostAdbConfig
import xyz.block.trailblaze.host.devices.HostAdbConfig.AdbImplementation
import xyz.block.trailblaze.host.yaml.DesktopYamlRunner
import xyz.block.trailblaze.logs.server.TrailblazeMcpServer
import xyz.block.trailblaze.model.DesktopAppRunYamlParams

/**
 * Central Interface for The Trailblaze Desktop App
 */
abstract class TrailblazeDesktopApp(
  protected val desktopAppConfig: TrailblazeDesktopAppConfig,
) {
  init {
    // Initialize the ADB executor factory for host-side ADB operations
    // Using ADB_BINARY as the default (most compatible)
    // Switch to DADB for faster operations without process spawning
    HostAdbConfig.initialize(AdbImplementation.ADB_BINARY)
  }

  abstract val desktopYamlRunner: DesktopYamlRunner

  abstract val trailblazeMcpServer: TrailblazeMcpServer

  abstract val deviceManager: TrailblazeDeviceManager

  abstract fun startTrailblazeDesktopApp(headless: Boolean = false)
}
