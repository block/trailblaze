package xyz.block.trailblaze.mcp.android.ondevice.rpc

/**
 * Stable capability ids advertised by the Android on-device RPC runner.
 *
 * An id belongs here only when the HOST decides something from it. A request whose own failure
 * already tells the host what to do needs no entry: see [GetMemoryInfoRequest], where an older
 * runner's 404 is the version signal.
 */
object OnDeviceRunnerCapabilities {
  const val DEVICE_CLASSIFIER_OVERRIDE = "device-classifier-override"

  val ALL: List<String> = listOf(DEVICE_CLASSIFIER_OVERRIDE)
}
