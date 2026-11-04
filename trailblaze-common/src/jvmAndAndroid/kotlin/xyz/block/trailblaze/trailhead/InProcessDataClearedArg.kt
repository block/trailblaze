package xyz.block.trailblaze.trailhead

/**
 * Whether the host cleared the app's data (`pm clear`) before starting this in-process run: the
 * `trailblaze.inProcessDataCleared` instrumentation arg, `true` or `false`. A host-driven run always
 * sends it, so an in-process `launchApp` REINSTALL can tell a cleared start from an app that still
 * holds an earlier run's data. Absent when no host started the run (a device farm), where every
 * test starts on a fresh install.
 */
object InProcessDataClearedArg {
  const val KEY = "trailblaze.inProcessDataCleared"
}
