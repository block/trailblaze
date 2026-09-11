package xyz.block.trailblaze.mcp.android.ondevice.rpc

/** Stable capability ids advertised by the Android on-device RPC runner. */
object OnDeviceRunnerCapabilities {
  const val DEVICE_CLASSIFIER_OVERRIDE = "device-classifier-override"

  val ALL: List<String> = listOf(DEVICE_CLASSIFIER_OVERRIDE)
}
