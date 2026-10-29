package xyz.block.trailblaze.util

import java.io.File
import java.io.IOException

/**
 * The command to hand a process builder to run the host tool [name] (`git`, ...).
 *
 * On Windows, the absolute path of the first `<name>.exe` in an absolute PATH directory: Windows
 * resolves a bare name from the JVM's working directory before PATH, so running from an untrusted
 * checkout would execute that checkout's own `git.exe`. Elsewhere [name] itself, which the OS looks
 * up on PATH only.
 *
 * @throws IOException on Windows when no PATH directory has `<name>.exe`.
 */
fun hostExecutable(name: String, windows: Boolean = isWindows(), pathEnv: String? = System.getenv("PATH")): String {
  if (!windows) return name
  val exe = "$name.exe"
  return pathEnv.orEmpty().split(File.pathSeparatorChar)
    .map { it.trim().removeSurrounding("\"") }
    .filter { it.isNotEmpty() && File(it).isAbsolute }
    .map { File(it, exe) }
    .firstOrNull { it.isFile }
    ?.absolutePath
    ?: throw IOException("$exe not found on PATH")
}

/**
 * [command] as a Windows command prompt would find it, for a process builder: a bare name with no
 * extension (`adb`, `sdkmanager`) becomes the absolute path of the first match in an absolute PATH
 * directory, trying each [pathExt] extension in order (`adb.exe`, `sdkmanager.bat`). Java alone
 * finds only `.exe`, and only after looking in its working directory, so a command written for
 * macOS or Linux would otherwise miss a `.bat` or `.cmd` tool.
 *
 * Returned unchanged: every command off Windows, a name with a directory or an extension
 * (`gradlew.bat`, `C:\tools\x.exe`), and a name no PATH directory has, so the OS still resolves
 * those as it always did.
 */
fun windowsCommand(
  command: String,
  windows: Boolean = isWindows(),
  pathEnv: String? = System.getenv("PATH"),
  pathExt: String? = System.getenv("PATHEXT"),
): String {
  if (!windows || !isBareCommand(command)) return command
  val extensions = (pathExt?.takeIf { it.isNotBlank() } ?: ".COM;.EXE;.BAT;.CMD")
    .split(';')
    .map { it.trim().lowercase() }
    // PATHEXT can also list association-backed types (`.vbs`, `.js`) that a process can't start.
    .filter { it in LAUNCHABLE_EXTENSIONS }
  return pathEnv.orEmpty().split(File.pathSeparatorChar)
    .map { it.trim().removeSurrounding("\"") }
    .filter { it.isNotEmpty() && File(it).isAbsolute }
    .flatMap { dir -> extensions.map { File(dir, command + it) } }
    .firstOrNull { it.isFile }
    ?.absolutePath
    ?: command
}

/**
 * [argv] as `exec` starts it: the command resolved by [windowsCommand] against [environment], the
 * variables the child process gets. Windows matches variable names case-insensitively, so a
 * caller's `Path` overrides the inherited `PATH`.
 *
 * @throws IOException for a bare name no PATH directory has. Passing it on would let Windows look
 *   in the working directory first.
 */
fun hostArgv(argv: List<String>, environment: Map<String, String>, windows: Boolean = isWindows()): List<String> {
  if (!windows || argv.isEmpty()) return argv
  fun variable(name: String) = environment.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
  val bare = argv.first()
  val command = windowsCommand(bare, windows = true, pathEnv = variable("PATH"), pathExt = variable("PATHEXT"))
  if (command == bare && isBareCommand(bare)) throw IOException("$bare not found on PATH")
  return listOf(command) + argv.drop(1)
}

/**
 * Why [argv] can't run with its arguments kept literal, or null when it can. Windows starts a
 * `.bat` or `.cmd` file through cmd.exe, which reads its path and arguments again: `%NAME%` expands and `&`,
 * `|`, `<`, `>` and `^` act as operators. Such a call is refused rather than run with different
 * arguments; a caller that wants cmd.exe's reading asks for it with `["cmd", "/c", ...]`.
 */
fun batchArgumentProblem(argv: List<String>, windows: Boolean = isWindows()): String? {
  val command = argv.firstOrNull() ?: return null
  if (!windows || !(command.endsWith(".bat", ignoreCase = true) || command.endsWith(".cmd", ignoreCase = true))) {
    return null
  }
  argv.forEachIndexed { index, arg ->
    val char = arg.firstOrNull { it in CMD_METACHARACTERS } ?: return@forEachIndexed
    val what = if (char == '\r' || char == '\n') "a line break" else "'$char'"
    val part = if (index == 0) "its path" else "argument $index"
    return "${File(command).name} is a batch file, so cmd.exe would reinterpret $part, " +
      "which contains $what. To run it that way on purpose, call [\"cmd\", \"/c\", ...] instead."
  }
  return null
}

private fun isBareCommand(command: String): Boolean =
  command.isNotEmpty() && command.none { it == '/' || it == '\\' || it == ':' || it == '.' }

private val LAUNCHABLE_EXTENSIONS = setOf(".com", ".exe", ".bat", ".cmd")

private const val CMD_METACHARACTERS = "%!&|<>^\"\r\n"
