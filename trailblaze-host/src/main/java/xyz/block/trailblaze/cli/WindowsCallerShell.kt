package xyz.block.trailblaze.cli

import java.io.Console

/**
 * Answers, on Windows, the two questions the bash launcher answers for the JVM everywhere else:
 * which process is the caller's shell (`TRAILBLAZE_SHELL_PID`, from `$PPID`) and whether a person
 * is typing at it (`TRAILBLAZE_INTERACTIVE`, from `[ -t 0 ]`). cmd.exe can answer neither, so
 * [CliCallerContext.callerEnv] asks here when the launcher left them unset.
 */
internal object WindowsCallerShell {

  /** Set by `trailblaze.cmd` to `%CMDCMDLINE%`: the command line of the cmd.exe running it. */
  const val LAUNCHER_CMDLINE_ENV = "TRAILBLAZE_LAUNCHER_CMDLINE"

  fun derivedEnv(name: String): String? = when (name) {
    "TRAILBLAZE_SHELL_PID" -> shellPid
    "TRAILBLAZE_INTERACTIVE" -> if (isInteractive) "1" else "0"
    else -> null
  }

  private val shellPid: String? by lazy {
    val ancestors = generateSequence(ProcessHandle.current().parent().orElse(null)) { it.parent().orElse(null) }
      .take(MAX_ANCESTORS)
      .map { Process(it.pid(), it.info().command().orElse(null)) }
      .toList()
    shellPid(System.getenv(LAUNCHER_CMDLINE_ENV), ancestors)?.toString()
  }

  private val isInteractive: Boolean by lazy {
    val console = System.console() ?: return@lazy false
    // JDK 22+ hands out a Console even when redirected; `isTerminal` (also 22+) says whether it is one.
    runCatching { Console::class.java.getMethod("isTerminal").invoke(console) as Boolean }.getOrDefault(true)
  }

  /** One process above the JVM: its PID and, when Windows reports it, its executable path. */
  data class Process(val pid: Long, val command: String?)

  /**
   * The shell to pin this terminal's device to, given the JVM's [ancestors] nearest first.
   *
   * `trailblaze.cmd` runs inside a cmd.exe, and that cmd.exe starts the JVM. Typed at a cmd prompt,
   * the batch file runs in the prompt's own cmd.exe, so the JVM's parent is the shell. Run from
   * PowerShell, Git Bash or any other program, the batch file gets a throwaway `cmd.exe /c`, and the
   * shell is the process that started it.
   *
   * Git Bash (MSYS) is the exception: it starts every native program from a forked copy of itself,
   * and a subshell (`$(trailblaze snapshot)`) adds another, each living only as long as that one
   * command. So a run of processes all running the same shell executable counts as one shell, and
   * the pin belongs to the outermost - the terminal's shell. A script run from that terminal shares
   * its pin, where on macOS and Linux the script would get one of its own.
   *
   * Without [launcherCmdLine] the JVM was started some other way (`java -jar` typed directly), and
   * its parent is the shell.
   */
  fun shellPid(launcherCmdLine: String?, ancestors: List<Process>): Long? {
    val parent = ancestors.getOrNull(0) ?: return null
    if (launcherCmdLine == null || !isThrowawayCmd(launcherCmdLine)) return parent.pid
    val starter = ancestors.getOrNull(1) ?: return null
    if (!isPosixShell(starter.command)) return starter.pid
    return ancestors.drop(1)
      .takeWhile { it.command.equals(starter.command, ignoreCase = true) }
      .last()
      .pid
  }

  private fun isPosixShell(command: String?): Boolean =
    command?.substringAfterLast('\\')?.substringAfterLast('/')?.lowercase() in POSIX_SHELLS

  private val POSIX_SHELLS = setOf("bash.exe", "sh.exe", "dash.exe", "zsh.exe")

  // Enough to climb a Git Bash subshell inside a script inside a terminal, with room to spare.
  private const val MAX_ANCESTORS = 16

  /**
   * True for a cmd.exe started to run one command (`/c`, or its synonym `/r`) rather than to take
   * input (`/k`, or no command). Reads only cmd.exe's own switches, which come after its executable
   * and before the command, so a `/c` inside the command (`/k "init.cmd /config"`) is not one.
   */
  fun isThrowawayCmd(cmdLine: String): Boolean {
    val line = cmdLine.trimStart()
    // Skip the executable, quoted or not.
    var i = if (line.startsWith('"')) {
      line.indexOf('"', 1).let { if (it < 0) line.length else it + 1 }
    } else {
      line.indexOfFirst { it.isWhitespace() }.let { if (it < 0) line.length else it }
    }
    while (true) {
      while (i < line.length && line[i].isWhitespace()) i++
      if (i + 1 >= line.length || line[i] != '/') return false
      // The command may run straight on from the switch (`/cdir`, `/c"x"`), so only its letter counts.
      when (line[i + 1].lowercaseChar()) {
        'c', 'r' -> return true
        'k' -> return false
      }
      while (i < line.length && !line[i].isWhitespace()) i++
    }
  }
}
