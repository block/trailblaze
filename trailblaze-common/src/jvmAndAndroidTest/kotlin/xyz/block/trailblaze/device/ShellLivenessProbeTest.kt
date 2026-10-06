package xyz.block.trailblaze.device

import xyz.block.trailblaze.util.UiAutomationHandleErrors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins [readShellCheckingLiveness]: a shell that silently answers nothing is reconnected without
 * ever running the command twice, and one a reconnect cannot bring back fails with the signature the
 * host relaunches the runner on.
 */
class ShellLivenessProbeTest {

  /**
   * A UiAutomation shell behind a cached handle. A dead connection answers every command `""`, the
   * way `UiAutomation.executeShellCommand` does when its connection's binder has died, and runs
   * nothing. Dropping the handle reconnects, which brings the shell back only when [reconnectHeals].
   */
  private class FakeShell(
    var dead: Boolean = false,
    private val reconnectHeals: Boolean = true,
    private val handleDrops: Boolean = true,
    /** Kills the connection right after the next command runs, before the probe. */
    var dieAfterNextCommand: Boolean = false,
    private val reconnectThrows: RuntimeException? = null,
  ) {
    val commands = mutableListOf<String>()
    val ran = mutableListOf<String>()
    val budgets = mutableListOf<Long>()
    var handleDropsAttempted = 0
    var clock = 0L
    private var reconnectPending = false

    fun read(command: String, @Suppress("UNUSED_PARAMETER") loggable: String, budgetMs: Long): String {
      if (reconnectPending) {
        reconnectPending = false
        reconnectThrows?.let { throw it }
        if (reconnectHeals) dead = false
      }
      commands += command
      budgets += budgetMs
      clock += 100
      if (dead) return ""
      ran += command
      if (dieAfterNextCommand) {
        dieAfterNextCommand = false
        dead = true
      }
      return when {
        command.startsWith("echo ") -> "$SHELL_LIVENESS_TOKEN\n"
        command.startsWith("input ") -> ""
        else -> "output of $command"
      }
    }

    fun dropHandle(): Boolean {
      handleDropsAttempted++
      if (handleDrops) reconnectPending = true
      return handleDrops
    }

    fun exec(command: String, timeoutMs: Long = 1_000): String = readShellCheckingLiveness(
      shellCommand = command,
      loggableCommand = command,
      timeoutMs = timeoutMs,
      nowMs = { clock },
      read = ::read,
      dropHandle = ::dropHandle,
      log = {},
    )
  }

  /** BACK lands, then the connection dies before the probe: BACK must not be pressed again. */
  @Test
  fun aCommandThatTookEffectBeforeTheShellWentSilentIsNotRunAgain() {
    val shell = FakeShell(dieAfterNextCommand = true)

    val failure = assertFailsWith<IllegalStateException> { shell.exec("input keyevent 4") }

    assertEquals(listOf("input keyevent 4", "echo $SHELL_LIVENESS_TOKEN"), shell.ran)
    assertTrue(failure.message!!.contains("may or may not have run"), failure.message)
  }

  /** The runner keeps going: the step fails, and the next command gets the fresh connection. */
  @Test
  fun aSilentShellAReconnectBringsBackFailsTheCommandWithoutRestartingTheRunner() {
    val shell = FakeShell(dead = true)

    val failure = assertFailsWith<IllegalStateException> { shell.exec("setprop debug.x false") }

    assertFalse(UiAutomationHandleErrors.isStaleHandleSignature(failure.message), failure.message)
    assertFalse(UiAutomationHandleErrors.isNonRecoverableStaleHandleSignature(failure.message), failure.message)
    assertEquals(1, shell.handleDropsAttempted)
    assertEquals(
      listOf("setprop debug.x false", "echo $SHELL_LIVENESS_TOKEN", "echo $SHELL_LIVENESS_TOKEN"),
      shell.commands,
    )
    assertEquals("output of setprop debug.x false", shell.exec("setprop debug.x false"))
  }

  /** The runner's `am instrument` has died: reconnecting reaches the same dead connection. */
  @Test
  fun aSilentShellAReconnectCannotBringBackFailsWithTheSignatureTheHostRelaunchesOn() {
    val shell = FakeShell(dead = true, reconnectHeals = false)

    val failure = assertFailsWith<IllegalStateException> { shell.exec("setprop debug.x false") }

    assertTrue(UiAutomationHandleErrors.isNonRecoverableStaleHandleSignature(failure.message), failure.message)
    assertEquals(1, shell.commands.count { it == "setprop debug.x false" })
  }

  @Test
  fun aReconnectThatThrowsFailsWithTheSignatureTheHostRelaunchesOn() {
    val shell = FakeShell(dead = true, reconnectThrows = RuntimeException("Error while connecting UiAutomation"))

    val failure = assertFailsWith<IllegalStateException> { shell.exec("setprop debug.x false") }

    assertTrue(UiAutomationHandleErrors.isNonRecoverableStaleHandleSignature(failure.message), failure.message)
    assertTrue(failure.message!!.contains("Error while connecting UiAutomation"), failure.message)
  }

  @Test
  fun aHandleThatCannotBeDroppedFailsWithTheSignatureTheHostRelaunchesOn() {
    val shell = FakeShell(dead = true, handleDrops = false)

    val failure = assertFailsWith<IllegalStateException> { shell.exec("setprop debug.x false") }

    assertTrue(UiAutomationHandleErrors.isNonRecoverableStaleHandleSignature(failure.message), failure.message)
    assertEquals(listOf("setprop debug.x false", "echo $SHELL_LIVENESS_TOKEN"), shell.commands)
  }

  @Test
  fun anEmptyAnswerFromALiveShellIsReturnedWithoutReconnecting() {
    val shell = FakeShell()

    assertEquals("", shell.exec("input keyevent 4"))
    assertEquals(0, shell.handleDropsAttempted)
    assertEquals(listOf("input keyevent 4", "echo $SHELL_LIVENESS_TOKEN"), shell.commands)
  }

  /**
   * The budget starts when this is called, which the caller does once it holds the UiAutomation
   * monitor: time spent queued for the monitor does not come out of the command's read.
   */
  @Test
  fun timeSpentBeforeTheCallDoesNotComeOutOfTheBudget() {
    val shell = FakeShell()
    shell.clock = 4_000

    assertEquals("output of getprop x", shell.exec("getprop x", timeoutMs = 4_000))
    assertEquals(listOf(4_000L), shell.budgets)
  }

  /** Every read, the post-reconnect probe's included, draws on the one budget the caller sized. */
  @Test
  fun everyReadDrawsOnWhatIsLeftOfTheOneBudget() {
    val shell = FakeShell(dead = true)

    assertFailsWith<IllegalStateException> { shell.exec("setprop debug.x false", timeoutMs = 1_000) }

    assertEquals(listOf(1_000L, 900L, 800L), shell.budgets)
  }

  @Test
  fun aBudgetSpentByTheCommandSkipsTheProbeAndReturnsTheEmptyAnswer() {
    val shell = FakeShell(dead = true)

    assertEquals("", shell.exec("setprop debug.x false", timeoutMs = 100))
    assertEquals(listOf("setprop debug.x false"), shell.commands)
    assertEquals(0, shell.handleDropsAttempted)
  }

  /** No budget to check the fresh connection: fail as an ordinary drop; the next command checks. */
  @Test
  fun aBudgetSpentBeforeTheFreshProbeFailsWithoutRestartingTheRunner() {
    val shell = FakeShell(dead = true, reconnectHeals = false)

    val failure = assertFailsWith<IllegalStateException> { shell.exec("setprop debug.x false", timeoutMs = 200) }

    assertFalse(UiAutomationHandleErrors.isNonRecoverableStaleHandleSignature(failure.message), failure.message)
    assertEquals(listOf("setprop debug.x false", "echo $SHELL_LIVENESS_TOKEN"), shell.commands)
    assertEquals(1, shell.handleDropsAttempted)
  }
}
