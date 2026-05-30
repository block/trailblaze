package xyz.block.trailblaze.devices

/**
 * Process-wide port-namespace string used by [TrailblazeDevicePort.getPortForDevice] to
 * disambiguate daemons that drive different physical devices reporting the same `instanceId`.
 *
 * Two parallel JVM daemons on one host, each tunneled to a different remote ADB server, see
 * an emulator named `emulator-5554` on both sides. Without a discriminator, both daemons hash
 * to the same on-device port — and to the same `adb forward` host-side port — and collide.
 *
 * The JVM actual reads `ANDROID_ADB_SERVER_PORT` (the env var the daemon already uses to pick
 * its ADB server), so distinct daemons naturally produce distinct hashes without any new
 * configuration. Android (on-device APK) and wasmJs return `""`; on-device code reads the
 * actual port from an instrumentation arg, not from this hash.
 *
 * Read at first access and cached for the lifetime of the JVM (single-shot evaluation matches
 * the existing `AndroidHostAdbUtils` env-read pattern; restart the daemon to pick up changes).
 */
expect object HostPortNamespace {
  val current: String
}
