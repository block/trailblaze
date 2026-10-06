// In `bundler/` so build-logic composes it too: the framework's own build script
// (`:trailblaze-common`'s `bundleFrameworkScriptedTools`) and this plugin's trailmap staging share
// one implementation of the generated `.tooldefs.json` format.
import java.io.File
import java.util.concurrent.TimeUnit
import org.gradle.api.GradleException

/**
 * Writes `<tool>.tooldefs.json` for a scripted `.ts` tool that has no descriptor YAML: what the
 * TypeScript analyzer (`sdks/typescript/tools/extract-tool-defs.mjs`) reads from the `.ts` — name,
 * description, input schema, spec. A runtime with no analyzer (a device, an installed CLI) reads
 * this file in place of a hand-written descriptor, so the `.ts` is the only place the tool is
 * declared.
 *
 * The reader is `GeneratedScriptedToolDefsFile` in `:trailblaze-common`; the format is the
 * analyzer's own output minus its machine- and edit-specific `sourcePath` / `line`.
 */
object ScriptedToolDefsExtraction {
  const val SUFFIX = ".tooldefs.json"

  /** `<stem>.tooldefs.json` for a tool source `<stem>.ts`, relative path preserved. */
  fun fileNameFor(toolsRelativeTsPath: String): String = toolsRelativeTsPath.removeSuffix(".ts") + SUFFIX

  /**
   * True when no descriptor YAML beside [source] names it in its `script:`, so the `.ts` is all
   * there is. Those are the tools that need a generated definitions file.
   */
  fun isDescriptorless(source: File): Boolean {
    val scriptLine = Regex("""(?m)^\s*script:\s*["']?\./${Regex.escape(source.name)}["']?\s*$""")
    return source.parentFile
      .listFiles { f -> f.isFile && f.name.endsWith(".yaml") }
      .orEmpty()
      .none { scriptLine.containsMatchIn(it.readText()) }
  }

  /**
   * Runs the analyzer over [sources] in one `bun` process and returns each source's
   * `.tooldefs.json` text. A source with no typed export is a shared helper module the tools import,
   * not a tool, so it gets no file. Fails on any analyzer error and on a spec the analyzer couldn't
   * read (a non-inline spec reference) — a descriptor-less tool has nothing else to fall back on, so
   * a half-read definition would ship an ungated tool.
   */
  fun extract(extractor: File, sdkDir: File, sources: List<File>, logFile: File): Map<File, String> {
    if (sources.isEmpty()) return emptyMap()
    logFile.parentFile.mkdirs()
    // Stdout to a file, not a pipe: reading a pipe to EOF would block past the timeout on a hung
    // extractor, and a full pipe buffer would stall it.
    val stdoutFile = File(logFile.parentFile, logFile.nameWithoutExtension + ".stdout.json")
    val proc = try {
      ProcessBuilder(listOf("bun", extractor.absolutePath) + sources.map { it.absolutePath })
        .directory(sdkDir)
        .redirectOutput(ProcessBuilder.Redirect.to(stdoutFile))
        .redirectError(ProcessBuilder.Redirect.to(logFile))
        .start()
    } catch (e: java.io.IOException) {
      throw GradleException(
        "Could not launch `bun` to read the tool definitions of descriptor-less scripted tools. " +
          "Put `bun` on PATH (`source bin/activate-hermit`, or https://bun.sh/). Cause: ${e.message}",
        e,
      )
    }
    if (!proc.waitFor(5, TimeUnit.MINUTES)) {
      proc.destroyForcibly()
      throw GradleException("Tool-definition extraction timed out. See ${logFile.absolutePath}.")
    }
    val stdout = stdoutFile.readText()
    @Suppress("UNCHECKED_CAST")
    val result = runCatching { groovy.json.JsonSlurper().parseText(stdout) as Map<String, Any?> }
      .getOrElse { throw GradleException("Tool-definition extraction printed no JSON. See ${logFile.absolutePath}.", it) }
    val errors = result["errors"] as? List<*> ?: emptyList<Any?>()
    if (proc.exitValue() != 0 || errors.isNotEmpty()) {
      throw GradleException(
        "Could not read the tool definitions of descriptor-less scripted tools: " +
          "${groovy.json.JsonOutput.toJson(errors)}. See ${logFile.absolutePath}.",
      )
    }
    @Suppress("UNCHECKED_CAST")
    val toolsBySource = (result["tools"] as List<Map<String, Any?>>)
      .groupBy { File(it["sourcePath"] as String).canonicalFile }
    return sources.mapNotNull { src -> toolsBySource[src.canonicalFile]?.let { src to it } }.associate { (src, tools) ->
      tools.firstOrNull { it["uncapturedSpec"] == true }?.let {
        throw GradleException(
          "${src.name}: the spec of '${it["name"]}' isn't an inline object literal, so its fields " +
            "can't be read at build time. Inline the spec, or add a descriptor YAML.",
        )
      }
      val defs = tools.map { tool -> tool.filterKeys { it != "sourcePath" && it != "line" } }
      src to groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(mapOf("tools" to defs))) + "\n"
    }
  }
}
