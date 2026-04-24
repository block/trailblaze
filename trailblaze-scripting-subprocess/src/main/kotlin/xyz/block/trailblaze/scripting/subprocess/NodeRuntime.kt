package xyz.block.trailblaze.scripting.subprocess

import java.io.File

/**
 * Resolved TypeScript runtime that Trailblaze will spawn subprocess MCP servers under.
 *
 * Per the Decision 038 scope devlog (§ Runtime detection) — prefer `bun`; fall back to `tsx`
 * (which itself uses Node under the hood). If neither is on `PATH` at detection time,
 * [NodeRuntimeDetector.cached] throws [NoCompatibleTsRuntimeException] with an install hint.
 */
sealed interface NodeRuntime {

  /** Absolute path to the resolved runtime binary. */
  val binary: File

  /** Build the full argv for spawning [scriptFile] under this runtime. */
  fun argv(scriptFile: File): List<String>

  /** `bun run <script>` — the preferred runtime when present. */
  data class Bun(override val binary: File) : NodeRuntime {
    override fun argv(scriptFile: File): List<String> =
      listOf(binary.absolutePath, "run", scriptFile.absolutePath)
  }

  /** `tsx <script>` — Node + TypeScript loader fallback. */
  data class Tsx(override val binary: File) : NodeRuntime {
    override fun argv(scriptFile: File): List<String> =
      listOf(binary.absolutePath, scriptFile.absolutePath)
  }
}

/**
 * Thrown when neither `bun` nor `tsx` is on `PATH` at session startup.
 *
 * Message includes a concrete install hint so the author can resolve without needing to dig
 * into the devlogs.
 */
class NoCompatibleTsRuntimeException :
  IllegalStateException(
    "No compatible TypeScript runtime found on PATH. " +
      "Install bun (https://bun.sh) or install tsx globally (`npm install -g tsx`).",
  )

/**
 * Caches a single runtime detection per JVM. Scripted tool sessions share the result — PATH
 * changes mid-daemon aren't a supported operating mode, so one detection is enough.
 */
object NodeRuntimeDetector {

  /**
   * Detects the runtime on first access. Subsequent accesses return the same instance.
   * Throws [NoCompatibleTsRuntimeException] on first access if neither runtime is present.
   */
  val cached: NodeRuntime by lazy { detect(::findOnPath) }

  /**
   * Pure detection — scans [pathResolver] for `bun` first, then `tsx`. Exposed for tests so
   * they can inject a stub PATH lookup without relying on the developer environment.
   */
  internal fun detect(pathResolver: (name: String) -> File?): NodeRuntime {
    pathResolver("bun")?.let { return NodeRuntime.Bun(it) }
    pathResolver("tsx")?.let { return NodeRuntime.Tsx(it) }
    throw NoCompatibleTsRuntimeException()
  }

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
