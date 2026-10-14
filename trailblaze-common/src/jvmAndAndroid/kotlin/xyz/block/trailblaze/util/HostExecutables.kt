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
