package xyz.block.trailblaze.host.android

import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.inprocessidle.InProcessIdle
import xyz.block.trailblaze.util.AndroidHostAdbUtils
import xyz.block.trailblaze.util.Console

/** Device cleanup before an in-process harness connects. */
object InProcessInstrumentationCleanup {
  /**
   * Force-stops the app any OTHER instrumentation is still attached to. Called before a driver
   * connects: every in-process server listens on the
   * same device port as every other one, so an instrumentation left attached from an earlier run
   * answers this run's readiness probe in its place, or makes its server fail to bind.
   *
   * [connectingTestAppId] is never stopped: it is the one this connect launches or reuses. Nor is
   * the turbo helper: it serves its own port and is meant to stay attached beside a runner.
   * One `dumpsys` read when nothing else is attached, which is the usual case.
   *
   * Callers hold the device's connect lock, so a run being replaced cannot stop the instrumentation
   * its replacement just launched.
   */
  fun stopOtherAttached(
    deviceId: TrailblazeDeviceId,
    connectingTestAppId: String,
    log: (String) -> Unit = { Console.log(it) },
    /** Throws once the run is cancelled; asked before each force-stop. */
    checkActive: () -> Unit = {},
  ) {
    // Each call on a connection of its own: this runs interruptibly, and an interrupt on the
    // device's shared client closes it under any run already driving the device through it.
    val processes = shell(deviceId, listOf("dumpsys", "activity", "processes")) ?: return
    val attached = activeInstrumentations(processes).filter { it.substringBefore('/') != connectingTestAppId }
    if (attached.isEmpty()) return
    val targets = shell(deviceId, listOf("pm", "list", "instrumentation"))?.let(::instrumentationTargets) ?: return
    attached.forEach { component ->
      val target = targets[component] ?: return@forEach
      val testAppId = component.substringBefore('/')
      checkActive()
      log("Stopping $target: $testAppId is still attached to it and holds the device port.")
      shell(deviceId, listOf("am", "force-stop", target))
    }
  }

  private fun shell(deviceId: TrailblazeDeviceId, args: List<String>): String? =
    AndroidHostAdbUtils.execAdbShellCommandOnOwnConnection(deviceId, args, timeoutMs = SHELL_TIMEOUT_MS)

  private const val SHELL_TIMEOUT_MS = 10_000L

  /**
   * Components (`package/class`) of the instrumentations `dumpsys activity processes` lists as
   * active, except the turbo helper's, which holds no in-process server port.
   */
  internal fun activeInstrumentations(dumpsys: String): Set<String> =
    ACTIVE_INSTRUMENTATION.findAll(dumpsys)
      .filter { it.groupValues[2] != InProcessIdle.INSTRUMENTATION_CLASS }
      .map { fullComponent("${it.groupValues[1]}/${it.groupValues[2]}") }
      .toSet()

  /**
   * Instrumentation component to target package, from `pm list instrumentation`. Keyed by the
   * whole component, so another declaration in the same test APK cannot stand in for the active
   * one, and a target that is not a package name is dropped: it goes to a device shell unquoted.
   */
  internal fun instrumentationTargets(pmList: String): Map<String, String> =
    pmList.lineSequence().mapNotNull { line ->
      val component = line.substringAfter("instrumentation:", "").substringBefore(' ')
      val target = line.substringAfter("(target=", "").substringBefore(')')
      if ('/' !in component || !ANDROID_PACKAGE_NAME.matches(target)) null else fullComponent(component) to target
    }.toMap()

  /**
   * [component] with a leading-`.` class expanded to its package. `pm list instrumentation` prints
   * a runner inside its own test package in short form (`pkg/.Runner`), which `dumpsys` may print
   * in full, so both sides are compared in full.
   */
  private fun fullComponent(component: String): String {
    val pkg = component.substringBefore('/')
    val cls = component.substringAfter('/')
    return if (cls.startsWith('.')) "$pkg/$pkg$cls" else component
  }

  private val ANDROID_PACKAGE_NAME = Regex("""^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$""")

  private val ACTIVE_INSTRUMENTATION = Regex("""ActiveInstrumentation\{\S+ \{([^/}\s]+)/([^}\s]+)\}""")
}
