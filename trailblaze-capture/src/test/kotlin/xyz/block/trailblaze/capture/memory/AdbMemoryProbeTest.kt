package xyz.block.trailblaze.capture.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdbMemoryProbeTest {

  /** A scripted device shell that answers the probe's composite scripts the way a real shell would. */
  private class FakeShell {
    var pid: Int? = 17220
    var pssKb: Long = 36_940
    var gcPermitted = true
    var largeHeap = false

    /** A device that will not answer the limits script at all — it timed out, or refused. */
    var limitsReadable = true

    /** Replaces the `dumpsys meminfo` section, for dumps this parser does not recognise. */
    var dumpOverride: String? = null
    val commands = mutableListOf<String>()

    fun run(@Suppress("UNUSED_PARAMETER") deviceId: String, command: String): String? {
      commands += command
      val sep = AdbMemoryProbe.SEPARATOR
      return when {
        command == "cat /proc/meminfo" -> MeminfoFixtures.PROC_MEMINFO
        command.startsWith("getprop dalvik.vm.heapgrowthlimit") ->
          if (!limitsReadable) {
            null
          } else {
            "192m\n$sep\n512m\n$sep\n    flags=[ DEBUGGABLE HAS_CODE${if (largeHeap) " LARGE_HEAP" else ""} ALLOW_BACKUP ]\n"
          }
        command.startsWith("pid=\$(pidof ") -> buildString {
          appendLine(pid?.toString() ?: "")
          appendLine(sep)
          appendLine(MeminfoFixtures.PROC_MEMINFO)
          if (pid != null) {
            appendLine(sep)
            if (command.contains("kill -10") && gcPermitted) appendLine(AdbMemoryProbe.GC_OK)
            appendLine(sep)
            append(dumpOverride ?: MeminfoFixtures.dumpsysWith(pssKb))
          }
        }
        else -> error("unexpected command: $command")
      }
    }
  }

  @Test
  fun `a running app is read in one shell round trip with its GC forced, heap and limits`() {
    val shell = FakeShell()
    val snapshot = assertNotNull(AdbMemoryProbe(shell::run).read("emulator-5554", "com.example.app", forceGc = true))
    assertEquals(17220, snapshot.pid)
    assertEquals(36_940, snapshot.app?.totalPssKb)
    assertEquals(2_193, snapshot.app?.javaHeapUsedKb, "heap size − heap free from the Dalvik Heap row")
    assertEquals(true, snapshot.gcForced)
    assertEquals(196_608, snapshot.limits?.heapLimitKb, "192m growth limit applies without largeHeap")
    assertEquals(2_012_188, snapshot.device?.memTotalKb)
    assertEquals(MemoryProbe.SOURCE_ADB, snapshot.source)
    // One script for the reading, one (cached below) for the limits.
    assertEquals(2, shell.commands.size, shell.commands.toString())
    assertTrue(shell.commands[0].contains("run-as com.example.app kill -10"), shell.commands[0])
  }

  @Test
  fun `heap limits are read once per app`() {
    val shell = FakeShell()
    val probe = AdbMemoryProbe(shell::run)
    repeat(3) { assertNotNull(probe.read("emulator-5554", "com.example.app", forceGc = true)).limits }
    assertEquals(1, shell.commands.count { it.startsWith("getprop") })
  }

  @Test
  fun `a device that will not report its limits is asked once, not on every reading`() {
    // "Once per app" has to hold for the failure too. A device whose properties do not come back
    // otherwise pays an extra `getprop` and `dumpsys package` round trip on every single reading,
    // for the whole session, to be told the same nothing — and this probe exists to be cheap.
    val shell = FakeShell().apply { limitsReadable = false }
    val probe = AdbMemoryProbe(shell::run)
    repeat(3) {
      val snapshot = assertNotNull(probe.read("emulator-5554", "com.example.app", forceGc = true))
      assertNull(snapshot.limits, "no limits were readable, and none are invented")
    }
    assertEquals(1, shell.commands.count { it.startsWith("getprop") }, shell.commands.toString())
  }

  @Test
  fun `limits are remembered per device, not per package name`() {
    // One probe serves several devices, and the heap limit is a property of the pair: an emulator
    // with a 192m growth limit and a phone with 512m would otherwise report whichever answered
    // first for both.
    val shell = FakeShell()
    val probe = AdbMemoryProbe(shell::run)
    probe.read("emulator-5554", "com.example.app", forceGc = true)
    probe.read("emulator-5556", "com.example.app", forceGc = true)
    assertEquals(2, shell.commands.count { it.startsWith("getprop") }, shell.commands.toString())
  }

  @Test
  fun `a largeHeap app gets the large limit`() {
    val shell = FakeShell().apply { largeHeap = true }
    val snapshot = assertNotNull(AdbMemoryProbe(shell::run).read("emulator-5554", "com.example.app", forceGc = true))
    assertEquals(true, snapshot.limits?.largeHeap)
    assertEquals(524_288, snapshot.limits?.heapLimitKb)
  }

  @Test
  fun `a release build that refuses the signal reports gcForced false`() {
    val shell = FakeShell().apply { gcPermitted = false }
    val snapshot = assertNotNull(AdbMemoryProbe(shell::run).read("emulator-5554", "com.example.app", forceGc = true))
    assertEquals(false, snapshot.gcForced)
    assertNotNull(snapshot.app, "the reading itself still succeeds")
  }

  @Test
  fun `with GC off the script sends no signal and reports gcForced false`() {
    val shell = FakeShell()
    val snapshot = assertNotNull(AdbMemoryProbe(shell::run).read("emulator-5554", "com.example.app", forceGc = false))
    assertFalse(shell.commands[0].contains("kill -10"), shell.commands[0])
    assertEquals(false, snapshot.gcForced)
  }

  @Test
  fun `an app that is not running yields device memory only`() {
    val shell = FakeShell().apply { pid = null }
    val snapshot = assertNotNull(AdbMemoryProbe(shell::run).read("emulator-5554", "com.example.app", forceGc = true))
    assertNull(snapshot.pid)
    assertNull(snapshot.app)
    assertNull(snapshot.limits)
    assertNull(snapshot.gcForced)
    assertNotNull(snapshot.device)
    assertEquals(1, shell.commands.size, "no limits lookup for an app that is not running")
  }

  @Test
  fun `a dump the parser cannot read is not a dead process`() {
    val shell = FakeShell()
    val probe = AdbMemoryProbe(shell::run)
    val healthy = assertNotNull(probe.read("emulator-5554", "com.example.app", forceGc = false))
    // `dumpsys meminfo` answered, with a shape this parser does not know (the parser's own doc
    // allows for that on older builds) — which is not the same thing as the process being gone.
    shell.dumpOverride = "meminfo: a shape this parser does not recognise\n"
    val unreadable = assertNotNull(probe.read("emulator-5554", "com.example.app", forceGc = false))
    assertEquals(17220, unreadable.pid, "pidof answered, so the app is alive")
    assertNull(unreadable.app, "nothing parseable to report")
    assertNull(
      MemoryChangeDetector.changeReason(healthy, unreadable),
      "an unreadable dump must not be reported as the process dying",
    )
    // Control: a process that really is gone still reads as dead, so the assertion above is about
    // the unreadable dump and not about the detector having stopped noticing deaths.
    shell.pid = null
    shell.dumpOverride = null
    val gone = assertNotNull(probe.read("emulator-5554", "com.example.app", forceGc = false))
    assertEquals(MemoryChangeDetector.REASON_PROCESS_DIED, MemoryChangeDetector.changeReason(healthy, gone))
  }

  @Test
  fun `without an app id only proc meminfo is read`() {
    val shell = FakeShell()
    val snapshot = assertNotNull(AdbMemoryProbe(shell::run).read("emulator-5554", null, forceGc = true))
    assertEquals(listOf("cat /proc/meminfo"), shell.commands)
    assertNull(snapshot.appId)
    assertEquals(360_660, snapshot.device?.memAvailableKb)
  }

  @Test
  fun `a device that does not answer is no reading`() {
    assertNull(AdbMemoryProbe { _, _ -> null }.read("emulator-5554", "com.example.app", forceGc = true))
  }

  @Test
  fun `an app id carrying shell metacharacters never reaches the device shell`() {
    val shell = FakeShell()
    val snapshot = assertNotNull(
      AdbMemoryProbe(shell::run).read("emulator-5554", "x; touch /sdcard/pwn", forceGc = true),
    )
    // The sampler runs on its own timer, so an id it cannot vouch for must not be interpolated into
    // a command at all — asserted on the strings the shell was handed, not merely on the result.
    assertEquals(listOf("cat /proc/meminfo"), shell.commands)
    shell.commands.forEach { assertFalse(it.contains("touch"), "smuggled command reached the shell: $it") }
    // Degrades to the no-app reading rather than throwing: the device figures are still worth
    // recording, and a bad app id must not be what fails a trail.
    assertNull(snapshot.appId)
    assertEquals(360_660, snapshot.device?.memAvailableKb)
  }

  @Test
  fun `a valid app id still reaches the shell, so the guard is not rejecting every app`() {
    val shell = FakeShell()
    assertNotNull(AdbMemoryProbe(shell::run).read("emulator-5554", "com.example.app", forceGc = false))
    assertTrue(
      shell.commands.any { it.contains("pidof com.example.app") },
      "a valid app id must still be interpolated: ${shell.commands}",
    )
  }
}
