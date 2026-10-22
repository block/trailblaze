package xyz.block.trailblaze.cli

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import xyz.block.trailblaze.util.isWindows

/**
 * A daemon the CLI starts must not share the CLI's process group: a terminal's Ctrl-C signals that
 * whole group, and it killed the daemon's Playwright driver mid-trail before the CLI's run cancel
 * arrived, so a cancelled run ended Failed instead of Cancelled.
 */
class DaemonOwnSessionTest {

  private val perl: File? = File("/usr/bin/perl").takeIf { it.canExecute() }
  private val setsid: File? = findOnPath("setsid")

  @Test
  fun `the daemon spawn leads its own process group under the pid the startup claim tracks`() {
    assumeTrue("needs setsid or /usr/bin/perl", setsid != null || perl != null)

    assertLeadsItsOwnGroup(inOwnSession(PRINT_PGID, underCi = false))
  }

  @Test
  fun `perl starts it in its own session where there is no setsid, as on macOS`() {
    assumeTrue("needs /usr/bin/perl", perl != null)

    assertLeadsItsOwnGroup(inOwnSession(PRINT_PGID, setsid = null, perl = perl, underCi = false))
  }

  @Test
  fun `setsid starts it in its own session where it exists, as on Linux`() {
    assumeTrue("needs setsid on PATH", setsid != null)

    assertLeadsItsOwnGroup(inOwnSession(PRINT_PGID, setsid = setsid, perl = null, underCi = false))
  }

  @Test
  fun `a host with neither starts the daemon as before`() {
    assertEquals(PRINT_PGID, inOwnSession(PRINT_PGID, setsid = null, perl = null, underCi = false))
  }

  @Test
  fun `under CI the daemon stays in the job's process group so cancelling the job stops it`() {
    val tool = File("/usr/bin/true")

    assertEquals(PRINT_PGID, inOwnSession(PRINT_PGID, setsid = tool, perl = tool, underCi = true))
  }

  /**
   * The child reports its process group; leading its own group means that equals its pid, and that
   * pid must be [Process.pid] — the PID the startup claim records and reaps.
   */
  private fun assertLeadsItsOwnGroup(argv: List<String>) {
    assumeFalse("process groups are POSIX", isWindows())
    val child = ProcessBuilder(argv).redirectErrorStream(true).start()
    val output = child.inputStream.bufferedReader().readText().trim()
    child.waitFor(30, TimeUnit.SECONDS)

    assertEquals("${child.pid()} ${child.pid()}", output, "the child's pid and process group")
    assertNotEquals(processGroupOf(ProcessHandle.current().pid()), output.substringAfter(' '))
  }

  private fun processGroupOf(pid: Long): String {
    val ps = ProcessBuilder("ps", "-o", "pgid=", "-p", pid.toString()).start()
    return ps.inputStream.bufferedReader().readText().trim().also { ps.waitFor() }
  }

  private companion object {
    val PRINT_PGID = listOf("/bin/sh", "-c", "printf '%s %s\\n' \$\$ \"\$(ps -o pgid= -p \$\$ | tr -d ' ')\"")
  }
}
