package xyz.block.trailblaze.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import xyz.block.trailblaze.cli.WindowsCallerShell.Process

class WindowsCallerShellTest {

  private val cmd = "C:\\WINDOWS\\system32\\cmd.exe"
  private val pwsh = "C:\\Program Files\\PowerShell\\7\\pwsh.exe"
  private val gitBash = "C:\\Program Files\\Git\\usr\\bin\\bash.exe"
  private val launchedByPowerShell =
    "$cmd /c \"\"C:\\Users\\me\\.trailblaze\\bin\\trailblaze.cmd\" snapshot\""

  @Test
  fun `typed at a cmd prompt, the prompt's own cmd exe is the shell`() {
    val ancestors = listOf(Process(100, cmd), Process(50, "C:\\Windows\\explorer.exe"))
    assertEquals(100, WindowsCallerShell.shellPid("\"$cmd\" ", ancestors))
    assertEquals(100, WindowsCallerShell.shellPid("$cmd /k \"C:\\VS\\VsDevCmd.bat\"", ancestors))
  }

  @Test
  fun `run from PowerShell, the shell is what started the throwaway cmd exe`() {
    val ancestors = listOf(Process(100, cmd), Process(50, pwsh), Process(10, pwsh))
    assertEquals(50, WindowsCallerShell.shellPid(launchedByPowerShell, ancestors))
  }

  @Test
  fun `run from Git Bash, the shell is the terminal's bash above the per-command forks`() {
    val gitBashLauncher = "C:\\Program Files\\Git\\bin\\bash.exe"
    val viaCmd = "$cmd /d /s /c \"trailblaze.cmd snapshot\""
    // terminal bash (50) -> fork for the command (60) -> cmd.exe
    val typed = listOf(Process(100, cmd), Process(60, gitBash), Process(50, gitBash), Process(40, gitBashLauncher))
    assertEquals(50, WindowsCallerShell.shellPid(viaCmd, typed))
    // terminal bash (50) -> subshell for `$(...)` (55) -> fork for the command (60) -> cmd.exe
    val inSubshell = listOf(Process(100, cmd), Process(60, gitBash), Process(55, gitBash), Process(50, gitBash))
    assertEquals(50, WindowsCallerShell.shellPid(viaCmd, inSubshell))
  }

  @Test
  fun `the throwaway switch is found however the caller spells it`() {
    assertEquals(true, WindowsCallerShell.isThrowawayCmd("cmd.exe /d /s /C \"trailblaze.cmd\""))
    assertEquals(true, WindowsCallerShell.isThrowawayCmd("cmd /c\"trailblaze.cmd\""))
    assertEquals(false, WindowsCallerShell.isThrowawayCmd("\"C:\\tools/cmd\\cmd.exe\""))
    assertEquals(true, WindowsCallerShell.isThrowawayCmd("cmd.exe /r trailblaze.cmd"))
  }

  @Test
  fun `a slash c inside a persistent shell's startup command is not the throwaway switch`() {
    assertEquals(false, WindowsCallerShell.isThrowawayCmd("C:\\Windows\\System32\\cmd.exe /k \"init.cmd /config profile\""))
    assertEquals(false, WindowsCallerShell.isThrowawayCmd("\"C:\\Windows\\System32\\cmd.exe\" /d /k init.cmd /c"))
  }

  @Test
  fun `started without the launcher, the JVM's parent is the shell`() {
    assertEquals(100, WindowsCallerShell.shellPid(null, listOf(Process(100, pwsh), Process(50, null))))
  }

  @Test
  fun `a throwaway cmd exe whose starter is gone has no shell to pin to`() {
    assertNull(WindowsCallerShell.shellPid(launchedByPowerShell, listOf(Process(100, cmd))))
  }
}
