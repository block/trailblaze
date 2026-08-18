package xyz.block.trailblaze.devices

/**
 * iOS: `""`, same as Android and wasmJs. This discriminator only disambiguates parallel *host*
 * daemons tunneled to different ADB servers; on-device code is handed its port directly.
 */
actual object HostPortNamespace {
  actual val current: String = ""
}
