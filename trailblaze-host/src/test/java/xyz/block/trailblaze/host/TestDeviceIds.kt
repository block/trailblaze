package xyz.block.trailblaze.host

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDevicePort.getTrailblazeOnDeviceSpecificPort

/**
 * Makes the port a test's device id hashes to belong to THIS JVM, so two builds can run the same
 * test class at the same time.
 *
 * A device's port is a hash of its id, and that determinism is the contract: the fixture binds the
 * port and an `OnDeviceRpcClient` built from the same id finds it with nothing wired between them.
 * With a hardcoded id, though, determinism is also machine-wide — two JVMs running the class at
 * once (two worktrees building in parallel, or a CI agent running two modules) hash to one port,
 * and the loser fails. Measured 2026-09-22: three `DesktopYamlRunnerLaunchGateTest` cases died on
 * "Port 54504 was still held by another socket 60000ms into start()" with nothing wrong in the
 * code under test.
 *
 * Scoping the *device id* rather than the port namespace is what keeps the fixture and the client
 * agreeing: both derive from this one id through the production hash, which is left untouched.
 * (`HostPortNamespace` is the other seam, but only the server side could be pointed at a test
 * namespace — `OnDeviceRpcClient` derives its port with the default, and changing that would be
 * changing production port derivation to suit a test.)
 */
val JVM_TEST_DEVICE_SCOPE: String = "-jvm${ProcessHandle.current().pid()}"

/**
 * A device id for a test that binds or connects to a device-derived port. Pass the same id to the
 * fixture and to the client under test.
 *
 * Resolved once per name and cached, so every caller in this JVM gets the same id — a second
 * resolution that landed elsewhere would put the client and the server on different ports.
 */
fun jvmScopedDeviceId(
  instanceId: String,
  platform: TrailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
): TrailblazeDeviceId = resolvedDeviceIds.computeIfAbsent("$instanceId|$platform") {
  resolveScopedDeviceId(instanceId, platform, ::isPortFree)
}

/** Whether this id came from [jvmScopedDeviceId] in this process, so its port is this JVM's alone. */
fun TrailblazeDeviceId.isScopedToThisJvm(): Boolean = instanceId.contains(JVM_TEST_DEVICE_SCOPE)

/**
 * Picks the first scoped id whose port nothing already owns.
 *
 * Per-JVM scoping alone leaves a residue worth spending five lines on: the hash range (52530-59529)
 * overlaps the OS ephemeral range and this machine's long-lived listeners, so a scope that lands on
 * an occupied port turns EVERY test in the class into a 60s bind wait and then a failure — the same
 * symptom as the collision this file removes, with no other JVM involved. Measured on a developer
 * Mac: 1 of 28 JVMs landed on such a port (55555, owned by a process `lsof` won't attribute without
 * root). A hardcoded id was a one-time bet on that; a per-process one re-rolls every run, so the
 * bet has to be checked rather than taken.
 *
 * [portIsFree] is a parameter so the choice can be exercised with a hand-written answer instead of
 * a port this machine happens to own. Falls back to the first candidate when every one looks taken,
 * which is not a real state — it keeps the failure naming a stable port rather than the last of a
 * search nobody asked for.
 */
internal fun resolveScopedDeviceId(
  instanceId: String,
  platform: TrailblazeDevicePlatform,
  portIsFree: (Int) -> Boolean,
): TrailblazeDeviceId {
  val candidates = (0 until FREE_PORT_ATTEMPTS).map { attempt ->
    TrailblazeDeviceId(
      instanceId = "$instanceId$JVM_TEST_DEVICE_SCOPE-$attempt",
      trailblazeDevicePlatform = platform,
    )
  }
  return candidates.firstOrNull { portIsFree(it.getTrailblazeOnDeviceSpecificPort()) }
    ?: candidates.first()
}

/**
 * Enough that landing on an occupied port every time is not a case worth designing for: each
 * candidate is an independent hash, and this machine owns a few dozen of the 7000 ports in range.
 */
private const val FREE_PORT_ATTEMPTS = 5

private val resolvedDeviceIds = ConcurrentHashMap<String, TrailblazeDeviceId>()

/** Loopback-scoped to match the bind [MockRpcServer] actually makes. */
private fun isPortFree(port: Int): Boolean =
  try {
    ServerSocket(port, 0, InetAddress.getByName("127.0.0.1")).close()
    true
  } catch (_: IOException) {
    false
  }
