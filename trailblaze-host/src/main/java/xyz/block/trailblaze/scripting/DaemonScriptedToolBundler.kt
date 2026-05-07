package xyz.block.trailblaze.scripting

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.config.project.PackScriptedToolFile
import xyz.block.trailblaze.config.project.TrailblazePackManifest
import xyz.block.trailblaze.ui.TrailblazeDesktopUtil
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Daemon-init-time bundler for inline scripted tools declared in pack manifests.
 *
 * Walks each pack's `target.tools:` list, resolves the per-tool YAML descriptor to a
 * [PackScriptedToolFile], reads the referenced `script:` source, and runs `esbuild`
 * with the same flag set as the build-time
 * `BundleAuthorToolsTask` plugin (`build-logic/src/main/kotlin/TrailblazeAuthorToolBundleTasks.kt:267-278`).
 *
 * Output is cached by SHA-256 of the source `.ts` file bytes; a second call with
 * unchanged source returns the existing cached path without re-bundling. This
 * matches the "TS no longer needs to be valid JS" relaxation tracked in #2749 —
 * authors edit the `.ts`, the daemon picks up the change at restart, the bundler
 * produces a fresh cache entry, downstream consumers (Sub-PR-A3) pick up the new
 * bundle without any other plumbing.
 *
 * **Esbuild binary resolution is the caller's responsibility.** This class takes
 * the binary as a constructor argument and leaves discovery (PATH lookup,
 * configured location, env var, etc.) to the wiring code in Sub-PR-A3. The
 * build-time `defaultEsbuildBinary()` helper in the Gradle plugin is layout-aware
 * for the framework root and isn't appropriate for daemon-time use.
 */
class DaemonScriptedToolBundler(
  private val esbuildBinary: File,
  private val cacheDir: File = File(
    TrailblazeDesktopUtil.getDefaultAppDataDirectory(),
    TrailblazeDesktopUtil.SCRIPTED_BUNDLES_CACHE_SUBDIR,
  ),
) {

  /**
   * Bundles every entry in each pack's `target.tools:` list. Returns a map from
   * the tool's declared `name` (in its YAML descriptor) to the cached bundle
   * path on disk. Idempotent on unchanged source bytes.
   *
   * @param packs Discovered pack manifests.
   * @param packBaseDirs The on-disk base directory for each pack (so the loader
   *   can resolve the per-tool YAML paths declared in `target.tools:`). A pack
   *   missing from this map contributes nothing to the result.
   */
  suspend fun bundleAll(
    packs: List<TrailblazePackManifest>,
    packBaseDirs: Map<TrailblazePackManifest, File>,
  ): Map<String, File> = withContext(Dispatchers.IO) {
    val result = LinkedHashMap<String, File>()
    for (pack in packs) {
      val toolPaths = pack.target?.tools.orEmpty()
      if (toolPaths.isEmpty()) continue
      val baseDir = packBaseDirs[pack] ?: continue
      for (toolYamlRelPath in toolPaths) {
        val toolYamlFile = File(baseDir, toolYamlRelPath)
        if (!toolYamlFile.isFile) {
          throw IOException(
            "Pack '${pack.id}': scripted-tool descriptor '$toolYamlRelPath' not found at " +
              "${toolYamlFile.absolutePath}.",
          )
        }
        val descriptor = decodeDescriptor(toolYamlFile)
        // `script:` paths in the descriptor are documented to resolve against the JVM working
        // directory, not the pack directory. See the kdoc on PackScriptedToolFile.script. The
        // bundler honors that contract verbatim — Sub-PR-A3 will revisit if/when pack-relative
        // resolution lands.
        val scriptFile = File(descriptor.script).let { if (it.isAbsolute) it else it.absoluteFile }
        if (!scriptFile.isFile) {
          throw IOException(
            "Pack '${pack.id}': tool '${descriptor.name}' references script '${descriptor.script}' " +
              "(resolved to ${scriptFile.absolutePath}), but no such file exists.",
          )
        }
        // Fail loudly on duplicate tool names across packs. A LinkedHashMap put would
        // silently overwrite the earlier entry, and the per-tool dispatch downstream
        // would route to whichever bundle won the race — confusing at best, broken at
        // worst when the two tools have different schemas. TrailblazePackBundler enforces
        // the same uniqueness guarantee, so this just keeps the runtime in sync with the
        // build-time invariant.
        val existing = result[descriptor.name]
        if (existing != null) {
          throw IOException(
            "Duplicate scripted-tool name '${descriptor.name}' across packs. " +
              "Each pack manifest's `target.tools:` entry must declare a globally unique " +
              "name. Previously bundled at ${existing.absolutePath}; conflicting source " +
              "is ${scriptFile.absolutePath}.",
          )
        }
        result[descriptor.name] = bundleOneInternal(scriptFile, descriptor.name)
      }
    }
    result
  }

  /**
   * Bundles a single source file. Used by [bundleAll] and exposed as a unit-test seam.
   * Honors the cache: a cache hit returns the existing bundle file without spawning esbuild.
   *
   * @param scriptPath user's `.ts` file (or `.js` — bundling treats them the same).
   * @param toolName the registered tool name. Used as both the named-export key the synthesized
   *   wrapper imports from the user's file AND the registry key under which the wrapper
   *   self-registers on `globalThis.__trailblazeTools`. Convention: the user's `.ts` exports a
   *   function whose name matches `toolName`.
   */
  suspend fun bundleOne(scriptPath: File, toolName: String): File = withContext(Dispatchers.IO) {
    bundleOneInternal(scriptPath, toolName)
  }

  private fun bundleOneInternal(scriptPath: File, toolName: String): File {
    if (!scriptPath.isFile) {
      throw IOException("Scripted-tool source not found: ${scriptPath.absolutePath}")
    }
    // Defense-in-depth: validate the tool name even though `InlineScriptToolConfig`'s init
    // block already enforces the same pattern at YAML decode time. The bundler can be
    // invoked from test fixtures or future programmatic paths that bypass the config-class
    // construction site, so re-checking here keeps the wrapper synthesis below safe in
    // isolation. Source-of-truth pattern lives on `InlineScriptToolConfig.TOOL_NAME_PATTERN`
    // so any future tightening is a one-place change.
    require(InlineScriptToolConfig.TOOL_NAME_PATTERN.matches(toolName)) {
      "Invalid scripted-tool name '$toolName' for ${scriptPath.absolutePath}: must match " +
        "${InlineScriptToolConfig.TOOL_NAME_PATTERN} (letters, digits, _, -, ., starting with a " +
        "letter or _). Update the per-tool YAML descriptor's `name:` field to use a supported " +
        "character set."
    }
    // SHA over (source bytes + tool name) so two tools that point at the same .ts but register
    // under different names get distinct cache entries — the synthesized wrapper differs in
    // the registry key and the named-import even if the user's code bytes are identical.
    val sourceBytes = scriptPath.readBytes()
    val sha = sha256Hex(sourceBytes + toolName.toByteArray(Charsets.UTF_8))
    val outFile = File(cacheDir, "$sha.bundle.js")
    if (outFile.isFile && outFile.length() > 0L) {
      return outFile
    }
    // Sweep stale wrapper files left by previous abrupt JVM exits (SIGKILL, OOM, hard daemon
    // crash) — the `finally { wrapperFile.delete() }` below only runs on normal returns and
    // exception propagation, not on hard process termination. Without this, a crashed daemon
    // leaves `.trailblaze-wrapper-*.ts` files alongside the user's source where they can
    // accidentally end up in the user's commits. Best-effort: ignore delete failures so a
    // race with another daemon's currently-active wrapper doesn't surface as a bundling
    // error here.
    scriptPath.parentFile
      ?.listFiles { f -> f.name.startsWith(WRAPPER_FILENAME_PREFIX) && f.name.endsWith(".ts") }
      ?.forEach { runCatching { it.delete() } }
    if (!cacheDir.isDirectory && !cacheDir.mkdirs()) {
      throw IOException("Could not create scripted-bundles cache dir: ${cacheDir.absolutePath}")
    }
    if (!esbuildBinary.isFile) {
      throw IOException(
        "esbuild binary not found at ${esbuildBinary.absolutePath}. The daemon needs a " +
          "working esbuild executable to bundle inline scripted tools.",
      )
    }
    // Write esbuild output to a tmp file and atomically rename into place on success.
    // Without this, an esbuild process killed mid-write (Ctrl-C, OOM, daemon SIGKILL)
    // would leave a partial `<sha>.bundle.js` behind that the next session's cache hit
    // (`outFile.isFile && outFile.length() > 0L`) would happily return as a "good"
    // bundle. Using a unique tmp file per attempt also makes concurrent writes from two
    // daemons targeting the same SHA benign (last-writer-wins on the rename, content is
    // identical because content-addressed by SHA-256).
    // Synthesize a wrapper entry file co-located with the user's script. The wrapper:
    //   1. Imports the named export `<toolName>` from the user's script.
    //   2. Builds a `client` object that wraps `__trailblazeCall` (the host-installed async
    //      JS binding documented on `QuickJsToolHost.HOST_CALL_BINDING`). The user's handler
    //      receives this `client` as its third arg and uses it for `client.callTool(...)`
    //      composition with host-side Kotlin tools.
    //   3. Self-registers under `globalThis.__trailblazeTools[toolName]` with a `handler`
    //      that forwards `(args, ctx)` to the user's function with `client` injected. This
    //      is the registry shape `QuickJsToolHost.callTool` looks up at dispatch time.
    //
    // Without this wrapper, an `esbuild --bundle --format=iife` of the user's `.ts` produces
    // a self-contained IIFE that runs the user's module-init code but never registers the
    // exported function — the host then fails dispatch with `Tool not registered: <name>`.
    // This is the daemon-time analog of the build-time `BundleAuthorToolsTask`'s
    // SDK-aliasing approach, simplified for the named-export shape #2750 introduced.
    // Per-attempt unique filename via createTempFile so two concurrent bundling attempts
    // for the same SHA (parallel sessions, two daemons racing) can't collide on the wrapper
    // file — without this, one process's `finally { delete() }` could pull the file out
    // from under the other process's esbuild invocation, surfacing as intermittent
    // "could not resolve" or partial-read failures. Co-located with the user's script so
    // esbuild's relative-import resolution finds the named export.
    val wrapperFile = File.createTempFile("$WRAPPER_FILENAME_PREFIX$sha-", ".ts", scriptPath.parentFile)
    wrapperFile.writeText(synthesizeWrapper(scriptPath, toolName))
    val tmpFile = File.createTempFile("$sha.", ".bundle.js.tmp", cacheDir)
    try {
      runEsbuild(entry = wrapperFile, output = tmpFile, userSource = scriptPath)
      if (!tmpFile.isFile || tmpFile.length() == 0L) {
        throw IOException(
          "esbuild reported success but ${tmpFile.absolutePath} is missing or empty " +
            "(source: ${scriptPath.absolutePath}).",
        )
      }
      // ATOMIC_MOVE may fall back to a copy on filesystems that don't support rename
      // semantics (rare on the OSes we run); REPLACE_EXISTING covers the concurrent-
      // write race where another daemon already produced the same-SHA file first.
      try {
        java.nio.file.Files.move(
          tmpFile.toPath(),
          outFile.toPath(),
          java.nio.file.StandardCopyOption.ATOMIC_MOVE,
          java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        )
      } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
        java.nio.file.Files.move(
          tmpFile.toPath(),
          outFile.toPath(),
          java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        )
      }
    } catch (e: Throwable) {
      // Clean up the tmp file on any failure path so the cache dir doesn't accumulate
      // half-built bundles. Best-effort — if delete fails the file is still tmp-suffixed
      // and won't be picked up as a cache hit.
      tmpFile.delete()
      throw e
    } finally {
      // Always delete the synthesized wrapper file. We co-locate it with the user's script
      // (so esbuild's relative-import resolution finds the named export naturally) and that
      // means a leak would clutter the user's source tree — best-effort cleanup keeps the
      // tree tidy regardless of whether bundling succeeded.
      wrapperFile.delete()
    }
    return outFile
  }

  /**
   * Render the synthetic wrapper TS source for [toolName] sourced from [scriptPath]. The
   * relative import resolves the user's named export when esbuild walks the wrapper as the
   * entry point (the wrapper is co-located with the user's script in [bundleOneInternal]).
   *
   * **Tool names with non-identifier characters.** Tool names can contain hyphens
   * (`clock-android-launchApp`) — `WorkspaceClientDtsGenerator` / `TrailblazePackBundler`
   * already accept them. A direct `import { foo-bar as __h } from "..."` is invalid TS
   * because hyphens aren't legal identifiers. Workaround: namespace import, then
   * bracket-access the export by string key. Bracket access works for any string,
   * including hyphens / dots / digits-leading. The runtime check below also turns a
   * misspelled export name into a clear error instead of a `TypeError: undefined is not
   * a function` at first dispatch.
   */
  internal fun synthesizeWrapper(scriptPath: File, toolName: String): String {
    val userImport = "./${scriptPath.name}"
    val toolNameLiteral = jsStringLiteral(toolName)
    return buildString {
      appendLine("// Synthetic entry generated by DaemonScriptedToolBundler.")
      appendLine("// Imports the author's `$toolName` named export from `${scriptPath.name}`,")
      appendLine("// builds a `client` shim over the host's `__trailblazeCall` binding, and")
      appendLine("// registers a handler on `globalThis.__trailblazeTools[\"$toolName\"]` so")
      appendLine("// QuickJsToolHost.callTool can dispatch it.")
      // Namespace import + bracket-access by string key. Tolerates hyphens, dots, and any
      // other non-identifier characters in `toolName`. See kdoc on this method.
      appendLine("import * as __userModule from \"$userImport\";")
      appendLine()
      appendLine("const __userHandler = __userModule[$toolNameLiteral];")
      appendLine("if (typeof __userHandler !== \"function\") {")
      appendLine("  throw new Error(")
      appendLine("    \"Scripted tool \" + $toolNameLiteral + \" must export a function with that exact name. \" +")
      appendLine("    \"Found: \" + (typeof __userHandler) + \". Check the per-tool YAML descriptor's `name:` matches a named export in `${scriptPath.name}`.\"")
      appendLine("  );")
      appendLine("}")
      appendLine()
      // `__trailblazeCall` is installed as an async function on QuickJS by the host —
      // see `QuickJsToolHost.HOST_CALL_BINDING`. It returns the result-JSON string.
      //
      // **Failure propagation.** The host binding reports execution failures as a normal
      // JSON envelope, NOT a transport exception:
      //  - `SessionScopedHostBinding.errorEnvelope(...)` returns `{isError:true, error:...}`
      //    for unknown tools, missing context, decode failures, the inner tool throwing,
      //    etc.
      //  - `TrailblazeToolResult.Error.*` is serialized as a discriminated union with
      //    `type` containing "Error".
      //
      // If the shim returned the parsed envelope verbatim, the user's
      // `await client.callTool("launchApp", ...)` would resolve to an error envelope they
      // typically don't check, the outer scripted tool would return a Success message, and
      // the trail would proceed against state the inner call never produced. Detect both
      // shapes here and throw with the error message — this gives authors the same "errors
      // bubble up via try/catch" contract that fetch/`await`-style JS code expects.
      appendLine("const __client = {")
      appendLine("  callTool: async (name, args) => {")
      appendLine("    const argsJson = JSON.stringify(args == null ? {} : args);")
      appendLine("    const resultJson = await __trailblazeCall(name, argsJson);")
      appendLine("    const result = JSON.parse(resultJson);")
      appendLine("    if (result && result.isError === true) {")
      appendLine("      throw new Error(\"client.callTool('\" + name + \"') failed: \" + (result.error || result.errorMessage || \"(no error message)\"));")
      appendLine("    }")
      appendLine("    if (result && typeof result.type === \"string\" && result.type.indexOf(\"Error\") >= 0) {")
      appendLine("      throw new Error(\"client.callTool('\" + name + \"') failed: \" + (result.errorMessage || result.message || result.type));")
      appendLine("    }")
      appendLine("    return result;")
      appendLine("  },")
      appendLine("};")
      appendLine()
      // Normalize user return values into the `{content: [...]}` envelope `QuickJsToolHost`
      // parses. Authors typically `return "Launched X."` from their handlers; the host
      // (per `QuickJsToolResultEnvelope`) expects `{content: [{type: "text", text: ...}]}`.
      // Without this normalization, a bare-string return surfaces as
      // `Element class JsonLiteral is not a JsonObject` at the result-parse step.
      // - undefined / null → empty envelope (success, no message)
      // - already-shaped envelope (`.content` is an array) → pass through unchanged
      // - string → wrap as a single text content part
      // - any other shape → JSON.stringify and wrap as a single text content part so the
      //   author still sees their value in trail logs (and a typo doesn't silently fail)
      appendLine("function __normalizeResult(result) {")
      appendLine("  if (result == null) return { content: [] };")
      appendLine("  if (typeof result === 'object' && Array.isArray(result.content)) return result;")
      appendLine("  if (typeof result === 'string') return { content: [{ type: 'text', text: result }] };")
      appendLine("  return { content: [{ type: 'text', text: JSON.stringify(result) }] };")
      appendLine("}")
      appendLine()
      appendLine("globalThis.__trailblazeTools = globalThis.__trailblazeTools || {};")
      appendLine("globalThis.__trailblazeTools[$toolNameLiteral] = {")
      appendLine("  handler: async (args, ctx) => {")
      appendLine("    const result = await __userHandler(args, ctx, __client);")
      appendLine("    return __normalizeResult(result);")
      appendLine("  },")
      appendLine("};")
    }
  }

  private fun runEsbuild(entry: File, output: File, userSource: File) {
    // Followup #2749: unify esbuild flags with BundleAuthorToolsTask.
    // The flag set below mirrors `BundleAuthorToolsTask:267-278`. Risk #4 in the
    // 2749 plan documents that duplicating these constants is acceptable for A1;
    // a follow-up will extract a shared util consumed by both the Gradle plugin
    // and this daemon-time bundler so flag changes can't drift between them.
    val argv = listOf(
      esbuildBinary.absolutePath,
      entry.absolutePath,
      "--bundle",
      "--platform=neutral",
      "--format=iife",
      "--target=es2020",
      "--main-fields=module,main",
      "--external:node:process",
      "--outfile=${output.absolutePath}",
    )
    val proc = ProcessBuilder(argv)
      .directory(entry.parentFile)
      .redirectErrorStream(true)
      .start()
    // 2 minutes mirrors `BundleAuthorToolsTask` — esbuild itself is fast on a typical
    // single-tool entry but we keep the same upper bound for parity.
    if (!proc.waitFor(2, TimeUnit.MINUTES)) {
      val partialLog = drainProcessOutput(proc)
      proc.destroyForcibly()
      proc.waitFor(10, TimeUnit.SECONDS)
      throw IOException(
        "esbuild did not finish within 2 minutes bundling scripted-tool source " +
          "${userSource.absolutePath} (synthesized wrapper: ${entry.absolutePath}).\n" +
          "esbuild stderr/stdout (combined):\n$partialLog",
      )
    }
    val log = drainProcessOutput(proc)
    if (proc.exitValue() != 0) {
      // The author cares about THEIR source path, not the synthesized wrapper. Surface both
      // — the wrapper path is useful when an esbuild error references a line in the wrapper
      // (the relative `import { ... }` line, the `client` shim, etc.). Drop a hint to clean
      // up the wrapper if the failure leaves it behind so the author doesn't accidentally
      // commit it.
      throw IOException(
        "esbuild failed (exit ${proc.exitValue()}) bundling scripted-tool source " +
          "${userSource.absolutePath}.\n" +
          "Synthesized wrapper entry was ${entry.absolutePath} — line/column references in the " +
          "esbuild output below may point at the wrapper rather than your source.\n" +
          "esbuild stderr/stdout (combined):\n$log",
      )
    }
  }

  private fun drainProcessOutput(proc: Process): String =
    try {
      proc.inputStream.bufferedReader().use { it.readText() }
    } catch (_: Throwable) {
      ""
    }

  private fun decodeDescriptor(yamlFile: File): PackScriptedToolFile =
    yaml.decodeFromString(PackScriptedToolFile.serializer(), yamlFile.readText())

  /**
   * Encode an arbitrary string as a JS string literal. Just `\` and `"` need escaping for
   * a typical tool name (declared in YAML, restricted to a sane character set); the few
   * other code points that legally appear in a JS string literal but aren't bracket-safe
   * (newlines, U+2028/U+2029) are not realistic in tool names declared via YAML, so we
   * keep this minimal. If a future tool-name validator widens the allowed character set,
   * extend this in lockstep.
   */
  private fun jsStringLiteral(s: String): String {
    val escaped = s.replace("\\", "\\\\").replace("\"", "\\\"")
    return "\"$escaped\""
  }

  companion object {
    private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))

    /**
     * Filename prefix for synthesized wrapper `.ts` files we drop next to the user's script.
     * Used both at write-time ([File.createTempFile] prefix) and at sweep-time (sibling
     * cleanup of files left by abrupt JVM exits) — keeping the constant in one place means
     * the two stay in sync.
     */
    internal const val WRAPPER_FILENAME_PREFIX: String = ".trailblaze-wrapper-"

    private fun sha256Hex(bytes: ByteArray): String {
      val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
      val sb = StringBuilder(digest.size * 2)
      for (b in digest) {
        val v = b.toInt() and 0xFF
        sb.append(HEX[v ushr 4])
        sb.append(HEX[v and 0x0F])
      }
      return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
  }
}
