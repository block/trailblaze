package xyz.block.trailblaze.util

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeFalse
import org.junit.Before

/**
 * [SimctlCommand.run] is how every one-shot simctl command runs. The contract: stdout and stderr come
 * back apart (callers use stdout as a path), stdin reaches the command, and a command that runs past
 * its timeout or whose caller is cancelled is killed rather than left running. A shell stands in for
 * simctl so the contract holds without Xcode.
 */
class SimctlCommandRunTest {

  @Before
  fun posixOnly() {
    assumeFalse("needs POSIX sh, cat and sleep", isWindows())
  }

  private fun sh(script: String) = listOf("sh", "-c", script)

  @Test
  fun `returns stdout and stderr separately with the exit code`() {
    val result = SimctlCommand.runCommand(sh("printf 'out\\n'; printf 'err\\n' >&2; exit 3"), timeoutSeconds = 30)

    assertEquals(3, result.exitCode)
    assertEquals("out\n", result.stdout)
    assertEquals("err\n", result.stderr)
    assertEquals("out\nerr", result.output)
    assertFalse(result.success)
    assertFalse(result.timedOut)
  }

  @Test
  fun `writes stdin to the command`() {
    val result = SimctlCommand.runCommand(listOf("cat"), timeoutSeconds = 30, stdin = "pasted text")

    assertTrue(result.success)
    assertEquals("pasted text", result.stdout)
  }

  @Test
  fun `adds the environment it is given to the command's`() {
    val result = SimctlCommand.runCommand(
      sh("printf '%s' \"${'$'}SIMCTL_CHILD_TB_TEST\""),
      timeoutSeconds = 30,
      environment = mapOf("SIMCTL_CHILD_TB_TEST" to "from env"),
    )

    assertEquals("from env", result.stdout)
  }

  @Test
  fun `closes stdin when there is none, so a reader of it finishes`() {
    val result = SimctlCommand.runCommand(listOf("cat"), timeoutSeconds = 30)

    assertTrue(result.success, "cat would wait on an open stdin until the timeout")
    assertEquals("", result.stdout)
  }

  @Test
  fun `kills a command that prints past the output cap and keeps the first bytes`() {
    val pidFile = File.createTempFile("simctl-run-cap", ".pid").apply { deleteOnExit() }

    val result = SimctlCommand.runCommand(
      sh("printf '%s' $$ > ${pidFile.absolutePath}; exec yes abcdefgh"),
      timeoutSeconds = 30,
      maxOutputBytes = 1000,
    )

    assertTrue(result.outputCapped)
    assertEquals(-1, result.exitCode)
    assertEquals(1000, result.stdout.length)
    assertTrue(result.stdout.startsWith("abcdefgh\n"))
    assertProcessGone(pidFile)
  }

  @Test
  fun `kills a command that runs past its timeout`() {
    val pidFile = File.createTempFile("simctl-run-timeout", ".pid").apply { deleteOnExit() }

    val result = SimctlCommand.runCommand(sh("printf '%s' $$ > ${pidFile.absolutePath}; exec sleep 600"), timeoutSeconds = 1)

    assertTrue(result.timedOut)
    assertEquals(-1, result.exitCode)
    assertProcessGone(pidFile)
  }

  @Test
  fun `kills the command when the waiting thread is interrupted, and the interrupt propagates`() {
    val pidFile = File.createTempFile("simctl-run-interrupt", ".pid").apply { deleteOnExit() }
    var thrown: Throwable? = null
    val runner = thread {
      try {
        SimctlCommand.runCommand(sh("printf '%s' $$ > ${pidFile.absolutePath}; exec sleep 600"), timeoutSeconds = 600)
      } catch (e: Throwable) {
        thrown = e
      }
    }
    awaitPid(pidFile)

    runner.interrupt()
    runner.join(TimeUnit.SECONDS.toMillis(30))

    assertFalse(runner.isAlive)
    assertTrue(thrown is InterruptedException, "expected the interrupt to propagate, got $thrown")
    assertProcessGone(pidFile)
  }

  @Test
  fun `cancelling a suspending run kills the command`() {
    val pidFile = File.createTempFile("simctl-run-cancel", ".pid").apply { deleteOnExit() }

    runBlocking {
      // Off runBlocking's thread, which the blocking awaitPid below never yields.
      val run = async(Dispatchers.Default) {
        SimctlCommand.runCommandSuspending({ sh("printf '%s' $$ > ${pidFile.absolutePath}; exec sleep 600") }, timeoutSeconds = 600)
      }
      awaitPid(pidFile)
      run.cancel()
      run.join()
    }

    assertProcessGone(pidFile)
  }

  private fun awaitPid(pidFile: File): Long {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
    while (System.nanoTime() < deadline) {
      pidFile.readText().trim().toLongOrNull()?.let { return it }
      Thread.sleep(20)
    }
    error("the command never started")
  }

  private fun assertProcessGone(pidFile: File) {
    val pid = awaitPid(pidFile)
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
    while (ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) {
      assertTrue(System.nanoTime() < deadline, "process $pid was left running")
      Thread.sleep(20)
    }
  }

  @Test
  fun `a timeout names simctl however it was launched`() {
    assertEquals("simctl", SimctlCommand.toolName(listOf("xcrun", "simctl", "launch", "UDID", "app")))
    assertEquals("simctl", SimctlCommand.toolName(listOf("/Applications/Xcode.app/Contents/Developer/usr/bin/simctl", "list")))
    assertEquals("plutil", SimctlCommand.toolName(listOf("plutil", "-extract", "deviceType", "raw", "-o", "-", "device.plist")))
  }
}
