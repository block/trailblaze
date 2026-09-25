package xyz.block.trailblaze.capture.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class IosSimulatorMemoryProbeTest {

  private val bundleId = "com.example.sample"

  /** `xcrun simctl spawn <udid> launchctl list`, trimmed to the interesting rows. */
  private val launchctl = """
    PID	Status	Label
    5950	0	UIKitApplication:$bundleId[97f1][rb-legacy]
    -	0	UIKitApplication:com.example.other[1a2b][rb-legacy]
    412	0	com.apple.SpringBoard
  """.trimIndent()

  /** `footprint -p 5950` on macOS. */
  private val footprint = """
    IosSampleApp [5950]: 64-bit    Footprint: 20 MB (16384 bytes per page)

         Dirty      Clean  Reclaimable    Regions    Category
           ---        ---          ---        ---    ---
       5600 KB        0 B      2624 KB          4    MALLOC_SMALL
       1200 KB     320 KB          0 B          2    MALLOC_TINY
       3072 KB      12 MB          0 B         40    __DATA
        512 KB        0 B          0 B          3    CoreAnimation
  """.trimIndent()

  /** A scripted simulator: [livePids] is who answers `footprint`; the launchctl listing is fixed. */
  private class FakeSimulator(val launchctl: String, val footprint: String) {
    val commands = mutableListOf<List<String>>()
    var livePids = setOf(5950)
    var launchctlOverride: String? = null

    fun run(command: List<String>): String? {
      commands += command
      return when (command.first()) {
        "xcrun" -> launchctlOverride ?: launchctl
        "footprint" -> if (command[2].toInt() in livePids) footprint else null
        else -> error("unexpected: $command")
      }
    }
  }

  private fun simulator() = FakeSimulator(launchctl, footprint)

  private fun probe(running: Boolean = true, commands: MutableList<List<String>> = mutableListOf()): IosSimulatorMemoryProbe {
    val sim = simulator()
    if (!running) sim.launchctlOverride = launchctl.lines().filterNot { it.contains(bundleId) }.joinToString("\n")
    return IosSimulatorMemoryProbe { command -> sim.run(command).also { commands.clear(); commands += sim.commands } }
  }

  @Test
  fun `finds the app's pid in launchctl and ignores other apps and stopped jobs`() {
    assertEquals(5950, IosSimulatorMemoryProbe.parseLaunchctlPid(launchctl, bundleId))
    assertNull(IosSimulatorMemoryProbe.parseLaunchctlPid(launchctl, "com.example.other"), "a '-' pid is not running")
    assertNull(IosSimulatorMemoryProbe.parseLaunchctlPid(launchctl, "com.example.missing"))
  }

  @Test
  fun `reads the footprint headline and the dirty column of each category`() {
    val sample = assertNotNull(IosSimulatorMemoryProbe.parseFootprint(footprint))
    assertEquals(20 * 1024, sample.footprintKb)
    assertEquals(mapOf("MALLOC_SMALL" to 5600L, "MALLOC_TINY" to 1200L, "__DATA" to 3072L, "CoreAnimation" to 512L), sample.categories)
    assertEquals(6800, sample.mallocKb, "the MALLOC_* categories are the app's own heap")
    assertNull(IosSimulatorMemoryProbe.parseFootprint("footprint: no such process\n"))
  }

  @Test
  fun `a running app becomes a simulator-sourced snapshot with footprint and RSS`() {
    val commands = mutableListOf<List<String>>()
    val snapshot = assertNotNull(probe(commands = commands).read("UDID-1", bundleId, forceGc = true))
    assertEquals(5950, snapshot.pid)
    assertEquals(20 * 1024, snapshot.iosApp?.footprintKb)
    assertNull(snapshot.iosApp?.rssKb, "RSS is not read: it is not in the event and would cost a ps per reading")
    assertEquals(20 * 1024, snapshot.usedKb, "footprint is the headline number on iOS")
    assertNull(snapshot.app, "no Android-shaped sample on iOS")
    assertNull(snapshot.device, "the Mac's RAM is not the simulated device's")
    assertNull(snapshot.gcForced, "nothing to force under ARC")
    assertEquals(MemoryProbe.SOURCE_SIMULATOR, snapshot.source)
    assertEquals(listOf("xcrun", "simctl", "spawn", "UDID-1", "launchctl", "list"), commands[0])
    assertEquals(listOf("footprint", "-p", "5950"), commands[1])
    assertEquals(2, commands.size, "one launchctl listing and one footprint — nothing else")
  }

  @Test
  fun `the pid is remembered, so later readings skip launchctl until that process is gone`() {
    val sim = simulator()
    val probe = IosSimulatorMemoryProbe(sim::run)
    assertEquals(5950, probe.read("UDID-1", bundleId, forceGc = false)?.pid)
    sim.commands.clear()
    assertEquals(5950, probe.read("UDID-1", bundleId, forceGc = false)?.pid)
    assertEquals(listOf(listOf("footprint", "-p", "5950")), sim.commands, "the second reading is footprint alone")

    // The trail force-restarted the app: 5950 is dead, launchctl now lists 6001.
    sim.livePids = setOf(6001)
    sim.launchctlOverride = launchctl.replace("5950", "6001")
    sim.commands.clear()
    val restarted = assertNotNull(probe.read("UDID-1", bundleId, forceGc = false))
    assertEquals(6001, restarted.pid)
    assertEquals(listOf("footprint", "xcrun", "footprint"), sim.commands.map { it.first() }, "dead pid → launchctl → new pid")
    sim.commands.clear()
    assertEquals(6001, probe.read("UDID-1", bundleId, forceGc = false)?.pid)
    assertEquals(listOf(listOf("footprint", "-p", "6001")), sim.commands, "the new pid is remembered in turn")
  }

  @Test
  fun `an app that dies stays unresolved until launchctl lists it again`() {
    val sim = simulator()
    val probe = IosSimulatorMemoryProbe(sim::run)
    probe.read("UDID-1", bundleId, forceGc = false)
    sim.livePids = emptySet()
    sim.launchctlOverride = launchctl.lines().filterNot { it.contains(bundleId) }.joinToString("\n")
    val gone = assertNotNull(probe.read("UDID-1", bundleId, forceGc = false))
    assertNull(gone.pid)
    assertNull(gone.iosApp)
    sim.commands.clear()
    probe.read("UDID-1", bundleId, forceGc = false)
    assertEquals(listOf("xcrun"), sim.commands.map { it.first() }, "nothing remembered: only the listing runs while the app is down")
  }

  @Test
  fun `a footprint that will not read keeps the pid launchctl just found`() {
    // `footprint` can refuse — a sandbox denial, a process mid-exit, an output shape this parser
    // does not know. Nulling the pid then turns a live app into `process_died`, followed by
    // `process_started` on the next pass: a restart the report publishes and the app never had.
    val sim = FakeSimulator(launchctl, "footprint: could not read process\n")
    sim.livePids = setOf(5950) // `footprint` RUNS; its output is what cannot be read.
    val snapshot = assertNotNull(IosSimulatorMemoryProbe(sim::run).read("UDID-1", bundleId, forceGc = false))
    assertEquals(5950, snapshot.pid, "launchctl listed it, so it is running")
    assertNull(snapshot.iosApp, "there is simply no reading this time")
  }

  @Test
  fun `an app that is not running is a snapshot without a process`() {
    val snapshot = assertNotNull(probe(running = false).read("UDID-1", bundleId, forceGc = true))
    assertNull(snapshot.pid)
    assertNull(snapshot.iosApp)
    assertEquals(bundleId, snapshot.appId)
  }

  @Test
  fun `a simulator that will not answer at all is no reading, not an app that stopped`() {
    // `xcrun simctl spawn` fails when the simulator shut down, is still booting, or this host has
    // no Xcode. Answering with a pid-less snapshot there would state something the probe does not
    // know — and the stream reads a pid going away as the app dying.
    val asked = mutableListOf<List<String>>()
    val probe = IosSimulatorMemoryProbe { command -> asked += command; null }
    assertNull(probe.read("UDID-1", bundleId, forceGc = false))
    assertEquals(listOf(listOf("xcrun", "simctl", "spawn", "UDID-1", "launchctl", "list")), asked)
  }

  @Test
  fun `without an app id there is nothing to read on a simulator`() {
    assertNull(probe().read("UDID-1", null, forceGc = true))
  }
}
