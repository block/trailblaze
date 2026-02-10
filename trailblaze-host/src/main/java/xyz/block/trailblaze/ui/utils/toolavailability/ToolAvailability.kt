package xyz.block.trailblaze.ui.utils.toolavailability

data class ToolAvailability(
  val adbStatus: AdbStatus,
  val iosStatus: IosStatus,
) {
  /** Convenience — true when ADB is usable right now (on PATH). */
  val adbAvailable: Boolean
    get() = adbStatus is AdbStatus.Available

  /** Convenience — true when iOS tools are usable right now. */
  val iosToolsAvailable: Boolean
    get() = iosStatus is IosStatus.Available
}
