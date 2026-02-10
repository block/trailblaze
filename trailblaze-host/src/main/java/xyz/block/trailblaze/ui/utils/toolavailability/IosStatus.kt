package xyz.block.trailblaze.ui.utils.toolavailability

/** Describes the availability status of iOS tooling (Xcode / Command Line Tools). */
sealed class IosStatus {
  /** `xcrun` and `xcodebuild` are on PATH and ready to use. */
  data object Available : IosStatus()

  /**
   * Xcode.app exists on disk but the command-line tools aren't configured (e.g. `xcode-select`
   * hasn't been pointed at it, or the license hasn't been accepted).
   *
   * @param xcodePath The path to the Xcode.app bundle that was found.
   */
  data class XcodeInstalledNotConfigured(val xcodePath: String) : IosStatus()

  /** Xcode could not be found on this machine. */
  data object NotInstalled : IosStatus()
}
