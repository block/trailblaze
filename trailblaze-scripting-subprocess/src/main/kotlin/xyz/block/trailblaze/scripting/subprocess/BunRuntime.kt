package xyz.block.trailblaze.scripting.subprocess

import java.io.File
import xyz.block.trailblaze.util.BunBinaryResolver

/**
 * Resolved bun runtime that Trailblaze will spawn subprocess MCP servers under.
 *
 * Trailblaze is bun-only — no Node/tsx fallback. If `bun` is not on `PATH` at detection time,
 * [BunRuntimeDetector.cached] throws [NoBunRuntimeException] with an install hint.
 */
data class BunRuntime(val binary: File) {

  /** Build the full argv for spawning [scriptFile] under `bun run`. */
  fun argv(scriptFile: File): List<String> =
    listOf(binary.absolutePath, "run", scriptFile.absolutePath)
}

/**
 * Thrown when `bun` is not on `PATH` at session startup.
 *
 * Message includes a concrete install hint so the author can resolve without needing to dig
 * into the devlogs.
 */
class NoBunRuntimeException :
  IllegalStateException(
    "No bun runtime found on PATH. Install bun (https://bun.sh).",
  )

/**
 * Caches a single runtime detection per JVM. Scripted tool sessions share the result — PATH
 * changes mid-daemon aren't a supported operating mode, so one detection is enough.
 */
object BunRuntimeDetector {

  /**
   * Detects the runtime on first access. Subsequent accesses return the same instance.
   * Throws [NoBunRuntimeException] on first access if bun is not present.
   */
  val cached: BunRuntime by lazy { detect(pathResolver = ::findOnPath) }

  /**
   * Pure detection — asks [pathResolver] for [binaryName], the one name bun has on this host (see
   * [BunBinaryResolver.BUN_BINARY_NAME], which the other bun-resolving site uses too). Exposed for
   * tests so they can inject a stub PATH lookup without relying on the developer environment.
   */
  internal fun detect(
    binaryName: String = BunBinaryResolver.BUN_BINARY_NAME,
    pathResolver: (name: String) -> File?,
  ): BunRuntime = pathResolver(binaryName)?.let(::BunRuntime) ?: throw NoBunRuntimeException()

  /**
   * Resolves [name] against each entry of the `PATH` env var. Returns the first executable
   * match, or `null` if none found.
   */
  private fun findOnPath(name: String): File? {
    val path = System.getenv("PATH") ?: return null
    return path
      .split(File.pathSeparator)
      .asSequence()
      .mapNotNull { dir -> if (dir.isBlank()) null else File(dir, name) }
      .firstOrNull { it.isFile && it.canExecute() }
  }
}
