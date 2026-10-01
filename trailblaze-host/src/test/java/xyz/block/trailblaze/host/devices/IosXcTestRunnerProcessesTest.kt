package xyz.block.trailblaze.host.devices

import java.util.concurrent.TimeUnit
import kotlin.streams.asSequence
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosXcTestRunnerProcessesTest {

  private val udid = "13AB7779-1EF4-4EFA-8BD2-696F9E7B9A52"

  /** The argv Maestro 2.6.1's `XCRunnerCLIUtils` starts a simulator's runner host with. */
  private fun maestroRunnerArgv(udid: String) = listOf(
    "/Applications/Xcode.app/Contents/Developer/usr/bin/xcodebuild",
    "test-without-building",
    "-xctestrun",
    "/var/folders/T/${udid}123/maestro-driver-ios-config.xctestrun",
    "-destination",
    "id=$udid",
    "-derivedDataPath",
    "/var/folders/T/maestro_xctestrunner_xcodebuild_output123",
  )

  private val spawned = mutableListOf<Process>()

  /** Hang containment, not a speed budget: turns a process that never exits into a failure. */
  private val hangGuardSeconds = 60L

  private fun exits(process: ProcessHandle): Boolean =
    runCatching { process.onExit().get(hangGuardSeconds, TimeUnit.SECONDS) }.isSuccess

  @AfterTest
  fun killSpawned() {
    spawned.forEach { process ->
      process.descendants().forEach { it.destroyForcibly() }
      process.destroyForcibly()
    }
  }

  @Test
  fun `a maestro runner host is recognized for its own device only`() {
    assertTrue(IosXcTestRunnerProcesses.isRunnerHost(maestroRunnerArgv(udid), udid))
    assertTrue(IosXcTestRunnerProcesses.isRunnerHost(maestroRunnerArgv(udid), udid = null))
    assertFalse(IosXcTestRunnerProcesses.isRunnerHost(maestroRunnerArgv("OTHER-UDID"), udid))
  }

  @Test
  fun `a destination naming the device among other keys still matches`() {
    val argv = maestroRunnerArgv(udid).map { if (it == "id=$udid") "platform=iOS Simulator,id=$udid" else it }
    assertTrue(IosXcTestRunnerProcesses.isRunnerHost(argv, udid))
  }

  @Test
  fun `an xcodebuild that is not maestro's runner is never taken for one`() {
    // Someone running their own UI tests against the same simulator.
    val handRun = maestroRunnerArgv(udid).map {
      if ("maestro_xctestrunner" in it) "/Users/me/Library/Developer/Xcode/DerivedData/MyApp" else it
    }
    assertFalse(IosXcTestRunnerProcesses.isRunnerHost(handRun, udid))
    // The marker elsewhere in the argv (here, the -xctestrun path) is not Maestro's derived data.
    val markerElsewhere = maestroRunnerArgv(udid).map {
      when {
        "maestro_xctestrunner" in it -> "/Users/me/Library/Developer/Xcode/DerivedData/MyApp"
        it.endsWith(".xctestrun") -> "/tmp/maestro_xctestrunner_xcodebuild_output/MyApp.xctestrun"
        else -> it
      }
    }
    assertFalse(IosXcTestRunnerProcesses.isRunnerHost(markerElsewhere, udid))
    val build = maestroRunnerArgv(udid).map { if (it == "test-without-building") "build" else it }
    assertFalse(IosXcTestRunnerProcesses.isRunnerHost(build, udid))
    val notXcodebuild = listOf("/usr/bin/simctl") + maestroRunnerArgv(udid).drop(1)
    assertFalse(IosXcTestRunnerProcesses.isRunnerHost(notXcodebuild, udid))
  }

  /**
   * An `sh` that keeps a `sleep` child, standing in for an xcodebuild and the `simctl diagnose` it
   * starts after a runner crash. `; wait` stops `sh` from exec-ing straight into the sleep.
   */
  private fun spawnTree(): Process =
    ProcessBuilder("sh", "-c", "sleep 60 & wait").start().also { process ->
      spawned += process
      val deadline = System.currentTimeMillis() + hangGuardSeconds * 1_000
      while (process.descendants().count() == 0L && System.currentTimeMillis() < deadline) Thread.sleep(20)
      check(process.descendants().count() > 0L) { "the stand-in tree never started its child" }
    }

  @Test
  fun `stopping a device's runner hosts takes their children and spares other devices`() {
    val thisDevice = spawnTree()
    val thisDeviceChildren = thisDevice.descendants().asSequence().toList()
    val otherDevice = spawnTree()
    val argvByPid = mapOf(
      thisDevice.pid() to maestroRunnerArgv(udid),
      otherDevice.pid() to maestroRunnerArgv("OTHER-UDID"),
    )

    val stopped = IosXcTestRunnerProcesses.stopRunnerHostsFor(
      udid = udid,
      processes = { sequenceOf(thisDevice.toHandle(), otherDevice.toHandle()) },
      argvOf = { argvByPid[it.pid()] },
    )

    assertEquals(listOf(thisDevice.pid()), stopped)
    assertTrue(thisDevice.waitFor(hangGuardSeconds, TimeUnit.SECONDS), "the runner host is still running")
    thisDeviceChildren.forEach { child ->
      assertTrue(exits(child), "child ${child.pid()} outlived its runner host")
    }
    assertTrue(otherDevice.isAlive, "another device's runner host was stopped")
    assertTrue(otherDevice.descendants().anyMatch { it.isAlive }, "another device's child was stopped")
  }

  /**
   * A same-port rebuild reattaches to this daemon's runner, so sparing its own host keeps that
   * runner warm — while another process's host for the device, which would relaunch its runner over
   * the reattached one, is still stopped.
   */
  @Test
  fun `a rebuild that reattaches spares its own runner host and stops everyone else's`() {
    val owner = ProcessBuilder("sh", "-c", "sh -c 'sleep 60 & wait' & sleep 60 & wait").start().also { spawned += it }
    val deadline = System.currentTimeMillis() + hangGuardSeconds * 1_000
    while (owner.descendants().count() < 3L && System.currentTimeMillis() < deadline) Thread.sleep(20)
    val ownHost = owner.children().asSequence().first { it.children().count() > 0L }
    val orphan = spawnTree()
    val runnerHosts = setOf(ownHost.pid(), orphan.pid())

    val stopped = IosXcTestRunnerProcesses.stopRunnerHostsFor(
      udid = udid,
      spareOwn = true,
      owner = owner.toHandle(),
      processes = { sequenceOf(ownHost, orphan.toHandle()) },
      argvOf = { if (it.pid() in runnerHosts) maestroRunnerArgv(udid) else listOf("sh") },
    )

    assertEquals(listOf(orphan.pid()), stopped)
    assertTrue(ownHost.isAlive, "the runner host a reattaching rebuild reuses was stopped")
    assertTrue(orphan.waitFor(hangGuardSeconds, TimeUnit.SECONDS), "another process's runner host is still running")
  }

  @Test
  fun `stopping a process's own runner hosts leaves ones it did not start`() {
    // `owner` stands in for the daemon JVM: its child `sh` is a runner host it started, and its own
    // `sleep` keeps it running after that host is gone.
    val owner = ProcessBuilder("sh", "-c", "sh -c 'sleep 60 & wait' & sleep 60 & wait").start().also { spawned += it }
    val deadline = System.currentTimeMillis() + hangGuardSeconds * 1_000
    while (owner.descendants().count() < 3L && System.currentTimeMillis() < deadline) Thread.sleep(20)
    val ownHost = owner.children().asSequence().first { it.children().count() > 0L }
    val notOwn = spawnTree()
    val runnerHosts = setOf(ownHost.pid(), notOwn.pid())

    val stopped = IosXcTestRunnerProcesses.stopRunnerHostsStartedBy(
      owner = owner.toHandle(),
      argvOf = { if (it.pid() in runnerHosts) maestroRunnerArgv(udid) else listOf("sh") },
    )

    assertEquals(listOf(ownHost.pid()), stopped)
    assertTrue(exits(ownHost), "its own runner host is still running")
    assertTrue(notOwn.isAlive, "a runner host another process started was stopped")
    assertTrue(owner.isAlive, "the owner itself was stopped")
  }
}
