package xyz.block.trailblaze.scripting

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import xyz.block.trailblaze.bundle.WorkspaceClientDtsGenerator
import xyz.block.trailblaze.util.Console

/**
 * Default subprocess timeout for [ScriptedToolDefinitionAnalyzer]. Overridable via
 * the `TRAILBLAZE_TOOL_ANALYZER_TIMEOUT_SECONDS` environment variable — mirrors the
 * convention `TRAILBLAZE_ADB_TIMEOUT_MS` sets for the dadb host path, which lets
 * slow CI agents extend the bound without code changes. Malformed env values fall
 * back to the constant and emit no warning (consistent with how `AndroidHostAdbUtils`
 * handles its own malformed-env case).
 *
 * Hoisted to file scope so it's resolvable from the analyzer's constructor default
 * parameter (Kotlin can't resolve companion-object members from a class's own
 * constructor defaults).
 */
private val DEFAULT_ANALYZER_TIMEOUT_SECONDS: Long =
  System.getenv("TRAILBLAZE_TOOL_ANALYZER_TIMEOUT_SECONDS")
    ?.toLongOrNull()
    ?.takeIf { it > 0 }
    ?: 60L

/**
 * Static-analysis pass that walks a trailmap's `tools/` directory, finds every
 * `export const <name> = trailblaze.tool<I, O>({ handler })` declaration, and extracts
 * the input/output type information as JSON Schema for downstream codegen, LLM
 * tool-registration, and ajv runtime validation.
 *
 * This is a **sibling** of [ScriptedToolImportAnalyzer], not a refinement of it. The
 * import analyzer answers "can this tool run on QuickJS or does it need the host?".
 * This analyzer answers "what's the typed surface of this tool?". They share the
 * "shell out to a TS toolchain subprocess, parse the JSON, return a structured
 * verdict" pattern but never need each other's data.
 *
 * **How it works.** Invokes a bun shim (`sdks/typescript/tools/extract-tool-defs.mjs`)
 * that uses the TypeScript compiler API to locate each `trailblaze.tool<I, O>(...)`
 * call expression in the file, then hands the two type parameters to
 * `ts-json-schema-generator`'s programmatic API to produce the corresponding JSON
 * Schemas. TSDoc comments above the export AND on each interface field flow through
 * to the schema's `description` fields — they're a load-bearing part of the
 * LLM-tool-calling contract.
 *
 * **What gets returned.**
 *
 *  - A [ScriptedToolDefinition] per tool, carrying `name`, `sourcePath`, `description`,
 *    `inputSchema`, `outputSchema`, and the line number of the `export const` for error
 *    reporting.
 *  - An empty list when [trailmapToolsDir] has no `.ts` files (or doesn't exist) — the
 *    "this trailmap doesn't author scripted tools" case is normal and not an error.
 *
 * **Supported TypeScript subset.** Primitives, arrays, objects, optionals, enums,
 * literal unions, discriminated unions, `Record<string, T>`, AND `Date` (which the
 * generator natively converts to `{ "type": "string", "format": "date-time" }`) all
 * round-trip cleanly. The `Date` round-trip is pinned in the analyzer's test
 * fixtures so a future generator change that altered the conversion would surface
 * loudly.
 *
 * **Unsupported constructs** (`Map`/`Set`, `bigint`, `unknown`/`any`, conditional /
 * mapped / template-literal types, and function-typed fields under
 * `functions: "fail"`) cause `ts-json-schema-generator` to throw with a descriptive
 * error; the shim captures the message and surfaces it through
 * [ScriptedToolDefinitionException] pointing at the offending source file.
 *
 * **Failure handling.** A missing bun binary, a missing shim, a missing trailmap
 * directory, or an empty trailmap all collapse to an empty result so callers can decide
 * policy. Subprocess errors during AST walk OR schema generation throw
 * [ScriptedToolDefinitionException] with the file path and the underlying message —
 * authors can react to the failure rather than have it silently degrade their trailmap's
 * typed surface.
 */
open class ScriptedToolDefinitionAnalyzer(
  private val bunBinary: File,
  private val extractorShim: File,
  private val sdkDir: File,
  private val subprocessTimeoutSeconds: Long = DEFAULT_ANALYZER_TIMEOUT_SECONDS,
  /**
   * Provider for the workspace-local directory under which per-trailmap subprocess
   * outputs are cached, so subsequent runs over byte-identical inputs short-circuit
   * the bun + `ts-json-schema-generator` walk. Returning `null` (the default)
   * disables caching — the analyzer always re-runs the subprocess. Pass
   * [ScriptedToolDefinitionCache.resolveWorkspaceCacheDir] to opt in (or a
   * fixed-path lambda in tests).
   *
   * **Re-evaluated on every [analyze] call**, not captured at construction, so a
   * long-lived analyzer (e.g. one held by a daemon singleton) follows Trail Runner
   * workspace switches — entries land under the workspace that's current when the
   * analysis runs instead of piling up under the daemon's launch directory.
   *
   * The `TRAILBLAZE_TOOL_ANALYZER_NO_CACHE=1` env var (read once at JVM start)
   * bypasses cache lookup AND writes even when this returns non-null — useful for
   * debugging suspected stale-cache scenarios without nuking the directory.
   */
  private val cacheDirProvider: () -> File? = { null },
  /**
   * Forces the cache off even when [cacheDirProvider] returns non-null. Production
   * default is the value of the `TRAILBLAZE_TOOL_ANALYZER_NO_CACHE` env var, captured
   * once at JVM start via [ScriptedToolDefinitionCache.noCacheFromEnv]. Tests inject
   * `true` explicitly to exercise the bypass without mutating process env, which
   * Java doesn't expose cleanly anyway.
   */
  private val disableCache: Boolean = ScriptedToolDefinitionCache.noCacheFromEnv,
) {

  /**
   * The dependency key covers the SDK/shim/generator inputs only — it's independent of
   * where the cache lives — so it's computed once per analyzer and shared across every
   * cache dir [cacheDirProvider] resolves over the analyzer's lifetime.
   */
  private val dependencyKey: String by lazy {
    ScriptedToolDefinitionCache.computeDependencyKey(sdkDir, extractorShim)
  }

  /**
   * Resolve the cache for THIS analyze call. Called once per [analyze] so lookup and
   * write within one analysis always target the same directory, even if a workspace
   * switch lands mid-run.
   */
  private fun cacheForCurrentWorkspace(): ScriptedToolDefinitionCache? {
    if (disableCache) return null
    val cacheDir = cacheDirProvider() ?: return null
    return ScriptedToolDefinitionCache(cacheRoot = cacheDir, dependencyKey = dependencyKey)
  }

  /**
   * Walk every `.ts` file under [trailmapToolsDir] (recursively) and return the typed
   * tool definitions discovered in each.
   *
   * Returns an empty list when [trailmapToolsDir] doesn't exist, isn't a directory, or
   * contains no `.ts` files. Test files (`*.test.ts`) and declaration files
   * (`*.d.ts`) are filtered out — the analyzer's contract is "tool authoring files
   * only", consistent with how `bun test` and `tsc` discover tool source per trailmap.
   * Trailmap-local `.d.ts` files still participate in the cache content key (their
   * bytes shape the extracted schemas via import resolution) — see [ToolSourceScan].
   *
   * **Recurses into subdirectories** so trailmaps that organize their tools under
   * folders (e.g. `tools/mcp/foo.ts`, `tools/helpers/bar.ts`) are fully covered.
   * The `.trailblaze/` directory under `tools/` (the framework-generated typed-
   * binding artifacts emitted by [xyz.block.trailblaze.host.PerTrailmapClientDtsEmitter])
   * is skipped — its contents are codegen output, not author source.
   *
   * @throws ScriptedToolDefinitionException when the subprocess fails to launch,
   *   times out, exits with non-zero status, or returns a malformed envelope. The
   *   exception's [ScriptedToolDefinitionException.errors] carries per-tool
   *   diagnostics so the caller can surface them all at once rather than
   *   one-at-a-time. When the shim reports per-tool errors AND extracted some
   *   tools cleanly, the exception's [ScriptedToolDefinitionException.partialTools]
   *   carries those successful extractions so callers can decide policy.
   */
  open suspend fun analyze(trailmapToolsDir: File): List<ScriptedToolDefinition> = withContext(Dispatchers.IO) {
    if (!trailmapToolsDir.isDirectory) return@withContext emptyList()

    val scan = scanToolSources(trailmapToolsDir)
    val tsFiles = scan.toolFiles
    if (tsFiles.isEmpty()) return@withContext emptyList()

    // Cache lookup happens BEFORE the bun/shim presence check — a cache hit serves
    // a fully-formed result that doesn't depend on either binary, and the only thing
    // bun/shim need to do on a hit is "not run." This lets a workspace that already
    // has cache entries continue to function after bun is uninstalled (or while a
    // CI agent is being rebuilt) without forcing every daemon caller through the
    // "degraded — bun missing" branch.
    val cache = cacheForCurrentWorkspace()
    val contentKey = cache?.let {
      ScriptedToolDefinitionCache.computeContentKey(
        trailmapToolsDir = trailmapToolsDir,
        tsFiles = tsFiles,
        dependencyKey = it.dependencyKey,
        declarationFiles = scan.declarationFiles,
      )
    }
    if (cache != null && contentKey != null) {
      // A cached mixed-outcome run rethrows (via unwrap) exactly like the live run it
      // replaces — same exception shape, same per-tool diagnostics, same partial tools.
      cache.lookup(trailmapToolsDir, contentKey)?.let { hit ->
        return@withContext hit.unwrap(trailmapToolsDir, tsFiles)
      }
    }

    if (!bunBinary.isFile) return@withContext emptyList()
    if (!extractorShim.isFile) return@withContext emptyList()

    val argv = buildList {
      add(bunBinary.absolutePath)
      add(extractorShim.absolutePath)
      tsFiles.forEach { add(it.absolutePath) }
    }

    val proc: Process = try {
      ProcessBuilder(argv)
        // Run with the SDK directory as cwd so bun's module resolution finds
        // `ts-json-schema-generator` + `typescript` under `<sdkDir>/node_modules/`.
        // Without this, the shim's `import ts from "typescript"` fails when invoked
        // against a tool file living anywhere outside the SDK tree.
        .directory(sdkDir)
        .redirectErrorStream(false)
        .start()
    } catch (e: Throwable) {
      // Permission errors, missing-binary races (`isFile` lied), corrupted exec
      // bit, etc. — all surface here. Honor the documented contract by wrapping
      // them in our typed exception so callers don't have to know about every
      // ProcessBuilder failure mode the JVM can produce.
      throw ScriptedToolDefinitionException(
        message = "extract-tool-defs.mjs failed to launch via " +
          "${bunBinary.absolutePath} (cwd=${sdkDir.absolutePath}): ${e.message ?: e::class.simpleName}",
        errors = emptyList(),
        cause = e,
      )
    }

    // Drain stdout/stderr on background DAEMON threads BEFORE waitFor — the shim
    // can emit tens of KB of JSON for a trailmap with many discriminated-union types,
    // and a full pipe buffer would deadlock the process. Daemon flag keeps the
    // JVM from hanging at shutdown if a runaway subprocess never closes its
    // streams (Copilot review on PR #3323). On the timeout path we also close
    // the streams explicitly to force-unblock the readers.
    val stdoutBuffer = StringBuilder()
    val stderrBuffer = StringBuilder()
    val stdoutThread = Thread {
      proc.inputStream.bufferedReader().forEachLine { stdoutBuffer.append(it).append('\n') }
    }.apply { isDaemon = true }
    val stderrThread = Thread {
      proc.errorStream.bufferedReader().forEachLine { stderrBuffer.append(it).append('\n') }
    }.apply { isDaemon = true }
    stdoutThread.start()
    stderrThread.start()

    val finished = try {
      proc.waitFor(subprocessTimeoutSeconds, TimeUnit.SECONDS)
    } catch (e: InterruptedException) {
      proc.destroyForcibly()
      Thread.currentThread().interrupt()
      throw ScriptedToolDefinitionException(
        message = "extract-tool-defs.mjs wait interrupted after " +
          "${tsFiles.size} file(s) under ${trailmapToolsDir.absolutePath}.",
        errors = emptyList(),
        cause = e,
      )
    }
    if (!finished) {
      proc.destroyForcibly()
      proc.waitFor(5, TimeUnit.SECONDS)
      // Close the streams BEFORE joining so the drain threads' `forEachLine`
      // unblocks promptly. Without this, the drain threads can sit indefinitely
      // on a half-closed pipe (the kernel may not deliver EOF to a reader of a
      // killed-process's pipe until the OS reaps it) and `join(5_000)` returns
      // with the threads still running, leaving them as silent JVM background
      // workers reading from a now-defunct fd.
      runCatching { proc.inputStream.close() }
      runCatching { proc.errorStream.close() }
      stdoutThread.join(5_000)
      stderrThread.join(5_000)
      throw ScriptedToolDefinitionException(
        message = "extract-tool-defs.mjs timed out after ${subprocessTimeoutSeconds}s walking " +
          "${tsFiles.size} file(s) under ${trailmapToolsDir.absolutePath}.",
        errors = emptyList(),
      )
    }
    stdoutThread.join()
    stderrThread.join()

    val exit = proc.exitValue()
    val stdout = stdoutBuffer.toString().trim()
    val stderr = stderrBuffer.toString().trim()

    if (exit != 0) {
      // Shim crashed before writing the JSON envelope (uncaught throw on the
      // bun side, missing `node_modules/ts-json-schema-generator`, etc.).
      // Surface a focused error pointing at stderr — that's where bun's actual
      // failure message lives. Truncate stderr to keep the exception message
      // readable when something inside the shim throws a megabyte of stack.
      throw ScriptedToolDefinitionException(
        message = "extract-tool-defs.mjs exited with code $exit. " +
          "stderr=${truncate(stderr, MAX_STREAM_IN_MESSAGE)}",
        errors = emptyList(),
      )
    }

    val envelope = try {
      JSON_LENIENT.decodeFromString(ExtractorEnvelope.serializer(), stdout)
    } catch (e: Throwable) {
      throw ScriptedToolDefinitionException(
        message = "extract-tool-defs.mjs produced unparseable output (exit=$exit). " +
          "stderr=${truncate(stderr, MAX_STREAM_IN_MESSAGE)} " +
          "stdout=${truncate(stdout, MAX_STREAM_IN_MESSAGE)}",
        errors = emptyList(),
        cause = e,
      )
    }

    val outcome = AnalyzerOutcome(
      tools = envelope.tools.map { it.toDefinition() },
      errors = envelope.errors.map { rawErr ->
        ScriptedToolDefinitionError(
          file = rawErr.file,
          toolName = rawErr.name,
          message = rawErr.message,
        )
      },
    )
    // Persist the outcome — mixed ones (per-tool errors + partial tools) included. The cache
    // is keyed by file CONTENT, so an entry can never outlive the broken source — the author's
    // next save recomputes under a fresh key. Without this, a COMMITTED per-tool error (an
    // unsupported construct that lives in the repo for weeks) defeats the cache for its
    // whole trailmap and every catalog build re-pays the full subprocess walk.
    //
    // One known exception to "errors are always content-derived": the shim's own
    // failed-to-read-file error is environmental, and if the file was readable moments earlier
    // when the content key was computed, that transient is cached under a clean key and replays
    // until the file's bytes change. Deliberately unguarded — re-checking readability here
    // wouldn't catch a transient that already passed, the window is a race between two reads
    // milliseconds apart, and TRAILBLAZE_TOOL_ANALYZER_NO_CACHE covers triage.
    if (cache != null && contentKey != null) {
      cache.put(trailmapToolsDir, contentKey, outcome)
    }
    outcome.unwrap(trailmapToolsDir, tsFiles)
  }

  /**
   * Return the outcome's tools, or — for a mixed-outcome run (per-tool [AnalyzerOutcome.errors]
   * alongside partially-extracted tools) — throw the [ScriptedToolDefinitionException] documented
   * on [analyze]. Identical whether the outcome came from a live subprocess walk or a cache hit
   * that replayed one.
   */
  private fun AnalyzerOutcome.unwrap(trailmapToolsDir: File, tsFiles: List<File>): List<ScriptedToolDefinition> {
    if (errors.isEmpty()) return tools
    throw ScriptedToolDefinitionException(
      message = "extract-tool-defs.mjs reported ${errors.size} error(s) walking " +
        "${tsFiles.size} file(s) under ${trailmapToolsDir.absolutePath}.",
      errors = errors,
      partialTools = tools,
    )
  }

  /**
   * One recursive walk under [trailmapToolsDir], split into the two roles a `.ts` file
   * can play for the analyzer:
   *
   *  - [toolFiles] — author-source `.ts` files (excluding `*.test.ts`, `*.d.ts`, and
   *    anything under the framework-generated `.trailblaze/` directory). These are the
   *    subprocess's argv AND cache-key inputs.
   *  - [declarationFiles] — trailmap-local `*.d.ts` files. Never subprocess inputs (the
   *    shim's per-file TypeScript Program follows imports into them on its own), but a
   *    tool's schema closure can include one, so their bytes participate in the cache
   *    content key — otherwise a `.d.ts` edit could replay a stale cached schema
   *    (code review). The framework-emitted `trailblaze-client.d.ts` is excluded:
   *    it's codegen *derived from* the tool sources already hashed, and rewriting it
   *    after every build would churn the key for identical inputs.
   *
   * Both lists are sorted by absolute path so the analyzer produces deterministic
   * output regardless of filesystem-listing order.
   */
  private data class ToolSourceScan(
    val toolFiles: List<File>,
    val declarationFiles: List<File>,
  )

  private fun scanToolSources(trailmapToolsDir: File): ToolSourceScan {
    val results = mutableListOf<File>()
    val declarations = mutableListOf<File>()
    val stack = ArrayDeque<File>().apply { add(trailmapToolsDir) }
    while (stack.isNotEmpty()) {
      val dir = stack.removeLast()
      val children = dir.listFiles() ?: continue
      for (child in children) {
        // Reject symlinks at the boundary, before any name-based filtering or
        // recursion. Two failure modes this prevents:
        //
        //  1. **Symlink loops** (`tools/link → ../tools/`) — the stack-based
        //     walk would re-enqueue the same directory indefinitely until the
        //     heap was exhausted.
        //  2. **Skip-evasion** — the `.trailblaze` / `node_modules` skips below
        //     match by directory NAME, so a `tools/artifacts → /…/.trailblaze`
        //     symlink would silently bypass them and feed framework-generated
        //     `.d.ts` (or thousands of node_modules `.ts` files) into the
        //     analyzer's argv.
        //
        // The trailmap author's authentic `tools/` tree is a regular directory of
        // regular files; legitimate use cases for a symlinked tool source are
        // rare enough that "doesn't follow" is the safer default. Authors with
        // a real need can `cp` the linked content into the trailmap's tree.
        if (Files.isSymbolicLink(child.toPath())) continue
        if (child.isDirectory) {
          // Skip the legacy `<trailmapDir>/tools/.trailblaze/` subtree — pre-rename
          // framework versions wrote generated `.d.ts` there. Post-rename, the
          // PerTrailmapClientDtsEmitter writes to `tools/trailblaze-client.d.ts`
          // (filtered as a `.d.ts` below). Kept here defensively in case a stale
          // legacy directory still exists on a developer's machine before the
          // migration cleanup in WorkspaceClientDtsGenerator.writeRendered runs.
          // Also skip `node_modules` so a trailmap-local install doesn't drag
          // thousands of dep files into the analyzer's argv.
          if (child.name == ".trailblaze" || child.name == "node_modules") continue
          stack.add(child)
          continue
        }
        if (!child.isFile) continue
        val name = child.name
        if (!name.endsWith(".ts")) continue
        if (name.endsWith(".d.ts")) {
          if (name != WorkspaceClientDtsGenerator.GENERATED_FILE_NAME) declarations += child
          continue
        }
        if (name.endsWith(".test.ts")) continue
        results += child
      }
    }
    return ToolSourceScan(
      toolFiles = results.sortedBy { it.absolutePath },
      declarationFiles = declarations.sortedBy { it.absolutePath },
    )
  }

  // No diagnostic here on purpose: [analyze] extracts EVERY `.ts` in the dir, so logging the
  // uncaptured-spec footgun at this layer would fire for tools that carry a full YAML descriptor
  // (which supplies the gate the dropped spec would have) and aren't even being enriched — pure
  // noise. The [uncapturedSpec] data signal is carried forward instead; the enrichment layer, which
  // knows each descriptor's context, decides severity: a hard error for descriptor-less tools (spec
  // is the only metadata source) and a targeted warning for a partial descriptor that ends up
  // genuinely un-gated.
  private fun RawToolDefinition.toDefinition(): ScriptedToolDefinition =
    ScriptedToolDefinition(
      name = name,
      sourcePath = sourcePath,
      line = line,
      description = description,
      inputSchema = inputSchema,
      outputSchema = outputSchema,
      spec = spec,
      uncapturedSpec = uncapturedSpec,
    )

  private fun truncate(s: String, maxLen: Int): String =
    if (s.length <= maxLen) s else s.substring(0, maxLen) + "…[truncated]"

  @Serializable
  private data class ExtractorEnvelope(
    val tools: List<RawToolDefinition> = emptyList(),
    val errors: List<RawError> = emptyList(),
  )

  @Serializable
  private data class RawToolDefinition(
    val name: String,
    @SerialName("sourcePath") val sourcePath: String,
    val line: Int,
    val description: String? = null,
    val inputSchema: JsonObject,
    val outputSchema: JsonObject,
    /**
     * The `(spec, handler)` overload's spec object as captured by the analyzer's
     * inline-literal extraction. Absent when the author used the bare-handler
     * overload OR when the spec's fields were all unresolvable expressions
     * (spread, identifier reference, etc.) — see the "Inline-literal only"
     * caveat in `extract-tool-defs.mjs`.
     */
    val spec: JsonObject? = null,
    /**
     * Emitted by the shim only in the dangerous case: the `(spec, handler)` overload was used with
     * a non-inline spec reference, so [spec] is `null` and the whole spec was dropped. Defaults
     * `false` for bare-handler / inline-literal calls (shim omits the key).
     */
    val uncapturedSpec: Boolean = false,
  )

  @Serializable
  private data class RawError(
    val file: String,
    val name: String? = null,
    val message: String,
  )

  companion object {
    private val JSON_LENIENT = Json { ignoreUnknownKeys = true }

    /** Truncate cap for stderr/stdout spans embedded in exception messages. */
    private const val MAX_STREAM_IN_MESSAGE = 2_000

    /**
     * Walk ancestors of CWD looking for the SDK directory that carries both the
     * shim script and the installed `ts-json-schema-generator` node_modules tree.
     * The "marker" is the shim file itself — its presence under
     * `<candidate>/tools/extract-tool-defs.mjs` is the proof that the SDK tree
     * is intact.
     *
     * Per ancestor, two candidate sub-paths are probed:
     *  - `sdks/typescript` — the canonical layout (the repo's root has
     *    `sdks/typescript/` directly).
     *  - `opensource/sdks/typescript` — a nested layout, where the SDK lives
     *    under an `opensource/` sub-directory. Without this
     *    fallback, every walk-up from inside an `opensource/examples/<trailmap>/`
     *    workspace would have to be backstopped by `TRAILBLAZE_SDK_DIR` to
     *    work, which is brittle in CI and counterintuitive in IDEs.
     *
     * `TRAILBLAZE_SDK_DIR` env var overrides the walk-up when set — useful for
     * environments where the SDK doesn't sit directly above CWD (e.g. an
     * installed CLI whose source tree lives elsewhere on disk). The env var
     * also wins over a successful walk-up, so an explicit override always
     * takes precedence.
     */
    fun resolveSdkDir(): File? {
      System.getenv("TRAILBLAZE_SDK_DIR")?.takeIf { it.isNotBlank() }?.let { explicit ->
        val candidate = File(explicit)
        val shim = File(candidate, "tools/extract-tool-defs.mjs")
        if (shim.isFile) return candidate
      }
      val subPaths = listOf("sdks/typescript", "opensource/sdks/typescript")
      var current: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
      while (current != null) {
        for (subPath in subPaths) {
          val candidate = File(current, subPath)
          val shim = File(candidate, "tools/extract-tool-defs.mjs")
          if (shim.isFile) return candidate
        }
        current = current.parentFile
      }
      // No SDK source tree on disk — the common case for an installed CLI. Fall back to the
      // self-contained analyzer shim bundled into the framework JAR, extracted to a stable
      // cache dir. Returns null only when the JAR didn't ship the bundle (a dev build that
      // skipped `bundleScriptedToolAnalyzerShim`), preserving the prior "analyzer
      // unavailable" degradation.
      return resolveBundledAnalyzerSdkDir()
    }

    /**
     * JAR-resource path of the self-contained analyzer shim — `extract-tool-defs.mjs` with
     * `ts-json-schema-generator` + `typescript` bundled in by `:trailblaze-models`'s
     * `bundleScriptedToolAnalyzerShim` task. Absent in dev builds that skipped that task.
     */
    internal const val BUNDLED_ANALYZER_SHIM_RESOURCE: String =
      "trails/config/analyzer/extract-tool-defs.mjs"

    /**
     * JAR-resource path of the TypeScript `lib*.d.ts` payload that accompanies the bundled
     * shim, zipped with each file keyed by its path relative to the SDK root.
     *
     * The bundle inlines TypeScript's *code* but not its standard library: those are `.d.ts`
     * data files that TypeScript locates relative to its own `__filename`, which `bun build`
     * freezes to the build machine's absolute path. Shipping the libs and redirecting that
     * frozen path at them ([ANALYZER_SDK_ROOT_PLACEHOLDER]) is what lets an installed CLI
     * resolve `Record`, `Partial`, `Pick` and every other lib-declared type in a tool's I/O
     * types instead of failing extraction on them.
     *
     * SISTER-IMPL-TAG: analyzer-bundle-lib-payload — see `:trailblaze-models`'s
     * `bundleScriptedToolAnalyzerShim`, which writes this resource.
     */
    internal const val BUNDLED_ANALYZER_TS_LIB_RESOURCE: String =
      "trails/config/analyzer/ts-lib.zip"

    /**
     * Token standing in for the SDK root that `bun build` baked into the bundle's CJS
     * `__dirname` / `__filename` literals. Replaced at extraction time with
     * [bundledTsLibRoot], under which the shipped libs are unpacked at their original
     * SDK-relative paths — so the frozen paths resolve without the bundle knowing anything
     * about where it ended up.
     *
     * SISTER-IMPL-TAG: analyzer-bundle-lib-payload.
     */
    internal const val ANALYZER_SDK_ROOT_PLACEHOLDER: String = "__TRAILBLAZE_ANALYZER_SDK_ROOT__"

    /** Directory under a bundled-shim cache root holding the unpacked TypeScript libs. */
    internal fun bundledTsLibRoot(cacheRoot: File): File = File(cacheRoot, "ts-lib")

    /** Per-process memo of the extracted bundled-shim dir. The bundle is fixed for a given
     *  CLI build, so the ~7 MB resource is read + validated at most once per JVM (a CLI
     *  upgrade is a new process, which re-validates). `@Volatile` + idempotent extraction
     *  make a benign double-extract under a thread race harmless. */
    @Volatile
    private var bundledAnalyzerSdkDirMemo: File? = null

    /**
     * Final fallback for [resolveSdkDir]: extract the framework-bundled, self-contained
     * analyzer shim from the JAR to the per-user cache dir and return it. The bundle inlines
     * all of its npm deps, so the extracted shim runs under `bun` with no `node_modules` —
     * which is what lets an installed CLI (no SDK source tree) analyze typed tools out of the
     * box. Memoized per process.
     *
     * Returns null when the JAR doesn't carry the bundle (dev build), so callers degrade to
     * the "analyzer unavailable" message rather than crashing.
     */
    internal fun resolveBundledAnalyzerSdkDir(): File? {
      bundledAnalyzerSdkDirMemo?.let { return it }
      val dir = extractBundledAnalyzerShim(
        File(System.getProperty("user.home") ?: ".", ".trailblaze/analyzer"),
      )
      if (dir != null) bundledAnalyzerSdkDirMemo = dir
      return dir
    }

    /**
     * Extract the bundled shim resource to [cacheRoot] and return the dir, or null. Best-effort:
     * a missing resource (dev build), an *empty* resource (a stripped/corrupt build — guarded so
     * we don't leave a zero-byte shim that the marker would make [analyzerToolingAvailable]
     * accept), or any I/O error all yield null + a diagnostic, never a thrown exception — the
     * caller then degrades to "analyzer unavailable". Split out (no memo) so it's unit-testable
     * with an explicit dir.
     */
    internal fun extractBundledAnalyzerShim(cacheRoot: File): File? = try {
      val bytes = ScriptedToolDefinitionAnalyzer::class.java.classLoader
        ?.getResourceAsStream(BUNDLED_ANALYZER_SHIM_RESOURCE)
        ?.use { it.readBytes() }
      when {
        bytes == null -> null
        bytes.isEmpty() -> {
          Console.info(
            "[ScriptedToolDefinitionAnalyzer] bundled analyzer shim resource is empty — " +
              "skipping (typed-tool analysis unavailable from the bundled shim).",
          )
          null
        }
        else -> extractBundledShim(bytes, cacheRoot, tsLibArchive = readBundledTsLibArchive()).also {
          Console.info("[ScriptedToolDefinitionAnalyzer] using JAR-bundled analyzer shim at $it")
        }
      }
    } catch (e: Exception) {
      Console.info(
        "[ScriptedToolDefinitionAnalyzer] failed to extract bundled analyzer shim " +
          "(${e.message ?: e.javaClass.simpleName}) — typed-tool analysis unavailable.",
      )
      null
    }

    /** Read the shipped TypeScript lib archive, or null when the JAR didn't carry one. */
    private fun readBundledTsLibArchive(): ByteArray? = ScriptedToolDefinitionAnalyzer::class.java
      .classLoader
      ?.getResourceAsStream(BUNDLED_ANALYZER_TS_LIB_RESOURCE)
      ?.use { it.readBytes() }
      ?.takeIf { it.isNotEmpty() }

    /**
     * Write [shimBytes] to `<cacheRoot>/tools/extract-tool-defs.mjs` (the layout
     * [resolveExtractorShim] expects) and return [cacheRoot]. Skip-write-if-content-matches
     * keeps the cached shim's mtime stable across runs of the same framework build. Split
     * out so it's unit-testable without a JAR on the classpath.
     *
     * [tsLibArchive] is the zipped TypeScript `lib*.d.ts` payload
     * ([BUNDLED_ANALYZER_TS_LIB_RESOURCE]). It's unpacked under [bundledTsLibRoot], and every
     * [ANALYZER_SDK_ROOT_PLACEHOLDER] in the shim is rewritten to that directory so the
     * bundle's frozen build-machine paths resolve to the shipped libs. A null archive (an
     * older or stripped build) writes the shim verbatim: the placeholder stays put and lib
     * resolution stays broken, which is no worse than not shipping libs at all — but it's
     * reported, because the failure it produces downstream (`Unhandled error while creating
     * Base Type` on any `Record` / `Partial` / `Pick`) points nowhere near the cause.
     */
    internal fun extractBundledShim(
      shimBytes: ByteArray,
      cacheRoot: File,
      tsLibArchive: ByteArray? = null,
    ): File {
      val resolvedBytes = if (tsLibArchive != null) {
        val libRoot = bundledTsLibRoot(cacheRoot)
        extractTsLibArchive(tsLibArchive, libRoot)
        shimBytes.toString(Charsets.UTF_8)
          .replace(ANALYZER_SDK_ROOT_PLACEHOLDER, libRoot.absolutePath)
          .toByteArray(Charsets.UTF_8)
      } else {
        if (ANALYZER_SDK_ROOT_PLACEHOLDER in shimBytes.toString(Charsets.UTF_8)) {
          Console.info(
            "[ScriptedToolDefinitionAnalyzer] bundled analyzer shim expects a TypeScript lib " +
              "payload ($BUNDLED_ANALYZER_TS_LIB_RESOURCE) that this build didn't ship — types " +
              "declared in TypeScript's standard library (Record, Partial, Pick, …) will fail " +
              "to extract.",
          )
        }
        shimBytes
      }

      val shimFile = File(cacheRoot, "tools/extract-tool-defs.mjs")
      val stale = !shimFile.isFile ||
        shimFile.length() != resolvedBytes.size.toLong() ||
        !shimFile.readBytes().contentEquals(resolvedBytes)
      if (stale) {
        shimFile.parentFile.mkdirs()
        // Staged like the lib files: this is the file `bun` executes, so a concurrent reader
        // catching it half-written is the worst version of this failure.
        writeAtomically(shimFile, resolvedBytes)
      }
      // Marker so [analyzerToolingAvailable] recognizes this dir as the self-contained
      // bundle — it has no `node_modules/` (the deps are inlined into the shim), which the
      // source-tree preflight would otherwise reject. Written LAST: it's the gate other
      // callers check, so it must not appear before the payload it vouches for.
      val marker = File(cacheRoot, BUNDLED_ANALYZER_MARKER_FILENAME)
      if (!marker.isFile) {
        marker.parentFile.mkdirs()
        writeAtomically(
          marker,
          "Trailblaze self-contained scripted-tool analyzer shim bundle.\n".toByteArray(),
        )
      }
      return cacheRoot
    }

    /**
     * Unpack the zipped TypeScript lib payload under [libRoot], preserving each entry's
     * SDK-relative path (`node_modules/typescript/lib/lib.es5.d.ts`, and the same tail for any
     * nested copy) — those tails are what the bundle's rewritten paths point at.
     *
     * Entries whose content already matches are left alone, so re-running against the same
     * build rewrites nothing and the extracted mtimes stay stable. Equality is by **content**,
     * not size: two TypeScript releases can ship a same-length `lib.*.d.ts`, and a size-only
     * check would leave the old one in place and silently resolve types against the wrong
     * standard library.
     *
     * Each file is staged to a sibling and renamed into place. This cache is shared
     * process-wide (`~/.trailblaze/analyzer`), so a second CLI writing it while this one's
     * `bun` subprocess reads could otherwise hand the compiler a half-written `.d.ts` — a
     * plausible-looking partial file is worse to debug than a missing one.
     */
    internal fun extractTsLibArchive(archiveBytes: ByteArray, libRoot: File) {
      val canonicalRoot = libRoot.canonicalFile
      java.util.zip.ZipInputStream(archiveBytes.inputStream()).use { zip ->
        while (true) {
          val entry = zip.nextEntry ?: break
          if (entry.isDirectory) {
            zip.closeEntry()
            continue
          }
          val target = File(canonicalRoot, entry.name)
          // Refuse entries that escape the extraction root. The archive is ours, but an
          // unpacker that trusts entry names is the kind of thing that stops being true later.
          if (!target.canonicalFile.toPath().startsWith(canonicalRoot.toPath())) {
            Console.info(
              "[ScriptedToolDefinitionAnalyzer] skipping analyzer lib entry outside the " +
                "extraction root: ${entry.name}",
            )
            zip.closeEntry()
            continue
          }
          val bytes = zip.readBytes()
          zip.closeEntry()
          // Length first so an obvious mismatch skips reading the file back.
          if (target.isFile &&
            target.length() == bytes.size.toLong() &&
            target.readBytes().contentEquals(bytes)
          ) {
            continue
          }
          target.parentFile?.mkdirs()
          writeAtomically(target, bytes)
        }
      }
    }

    /**
     * Write [bytes] to [target] via a staged sibling so a concurrent reader sees either the old
     * file or the new one, never a partial write.
     *
     * The staging name comes from [java.nio.file.Files.createTempFile], not from a name derived
     * from the PID: [resolveBundledAnalyzerSdkDir]'s memo deliberately tolerates a two-thread
     * double-extract, and two threads of one process would share any per-process name. They'd
     * then interleave writes into the same staged file, and the loser's move would fail once the
     * winner renamed it away — turning a previously harmless race into "analyzer unavailable".
     * [ScriptedToolDefinitionCache.put] hit exactly this and resolved it the same way.
     *
     * The temp file is created in [target]'s own directory so the move is a same-filesystem
     * rename rather than a cross-device copy.
     */
    private fun writeAtomically(target: File, bytes: ByteArray) {
      val parent = target.parentFile
      var staged: File? = null
      try {
        staged = java.nio.file.Files
          .createTempFile(parent.toPath(), "${target.name}.", ".tmp")
          .toFile()
        staged.writeBytes(bytes)
        try {
          java.nio.file.Files.move(
            staged.toPath(),
            target.toPath(),
            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
          )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
          // Filesystems that can't promise atomicity (cross-device tmpfs in CI, some network
          // mounts) still get the staged write, which is strictly better than writing the
          // target in place: a reader sees old-or-new rather than a half-written file.
          java.nio.file.Files.move(
            staged.toPath(),
            target.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
          )
        }
        staged = null // ownership transferred to the target by the rename.
      } finally {
        staged?.delete()
      }
    }

    /** Marker file written into the bundled-shim cache dir by [extractBundledShim]; its
     *  presence means the shim is self-contained (deps inlined), so no `node_modules/`
     *  tree is required to run it. */
    internal const val BUNDLED_ANALYZER_MARKER_FILENAME: String = ".trailblaze-bundled-analyzer"

    /**
     * True when [sdkDir] can actually drive the extractor shim: either it's a real SDK
     * source tree with `node_modules/ts-json-schema-generator` installed, OR it's the
     * framework-bundled self-contained shim dir (deps inlined — see [extractBundledShim]).
     * Callsites gate on this before constructing an analyzer so a shim with no resolvable
     * deps isn't invoked and then fail per-trailmap with `ERR_MODULE_NOT_FOUND`.
     */
    fun analyzerToolingAvailable(sdkDir: File): Boolean =
      File(sdkDir, "node_modules/ts-json-schema-generator").isDirectory ||
        File(sdkDir, BUNDLED_ANALYZER_MARKER_FILENAME).isFile

    /**
     * Convenience: resolve the shim file under the SDK tree (or under
     * [explicitSdkDir] when the caller already knows the SDK root).
     */
    fun resolveExtractorShim(explicitSdkDir: File? = null): File? {
      val sdk = explicitSdkDir ?: resolveSdkDir() ?: return null
      val shim = File(sdk, "tools/extract-tool-defs.mjs")
      return shim.takeIf { it.isFile }
    }
  }
}

/**
 * One typed scripted tool extracted from a trailmap's `tools/` directory by
 * [ScriptedToolDefinitionAnalyzer.analyze].
 *
 *  - [name] — the identifier on the `export const`, which is also the tool's
 *    registered MCP name (per the SDK contract).
 *  - [sourcePath] — absolute path to the `.ts` file the tool was declared in.
 *  - [line] — 1-indexed line of the `export const` for error reporting.
 *  - [description] — TSDoc comment on the exported binding (NOT on the input/output
 *    interfaces — those descriptions are embedded in the JSON Schemas).
 *  - [inputSchema] / [outputSchema] — JSON Schema (draft-07-ish, the dialect
 *    `ts-json-schema-generator` produces) for the tool's `I` and `O` type
 *    parameters. Authors compose against these via ajv at the dispatch boundary
 *    and the LLM client uses them as the function-call schema.
 */
data class ScriptedToolDefinition(
  val name: String,
  val sourcePath: String,
  val line: Int,
  val description: String?,
  val inputSchema: JsonElement,
  val outputSchema: JsonElement,
  /**
   * Structured-config spec the author declared on the typed `(spec, handler)` overload
   * (`TrailblazeTypedToolSpec` in the TypeScript SDK).
   *
   * Captured by the analyzer's inline-literal extraction — keys are the
   * `TrailblazeTypedToolSpec` field names (`description`, `supportedPlatforms`,
   * `requiresContext`, `requiresHost`, `supportedDrivers`, `trailhead`) and values are the
   * JSON-compatible literals the author wrote at the call site (strings, string arrays,
   * booleans, and — for `trailhead` — a nested `{to, dynamic}` object).
   *
   * `null` when the author used the bare-handler overload OR when the spec's fields
   * were all unresolvable expressions (object spread, identifier reference, function
   * call). Callers should default each missing field to the framework default (false
   * for booleans, empty for the platform/driver gates which the runtime treats as
   * "unrestricted").
   *
   * Downstream consumers translate the gate fields into the namespaced `_meta` JSON
   * (`trailblaze/supportedPlatforms`, etc.) the runtime reads — see
   * [xyz.block.trailblaze.scripting.AnalyzerScriptedToolEnrichment.mergeMeta] for the
   * canonical projection. The `description` field is the exception: it's the tool's
   * primary descriptor, so enrichment routes it into the resolved description (YAML >
   * spec > TSDoc) rather than into `_meta`.
   */
  val spec: JsonObject? = null,
  /**
   * True when the author used the `(spec, handler)` overload but passed a non-inline reference
   * the analyzer can't read (`const SPEC = {...}`, `Specs.foo`, a factory call), so the ENTIRE
   * spec ([spec] is therefore `null`) was dropped — silently un-gating the tool's
   * `supportedPlatforms` / `surfaceToLlm` / `requiresHost`. Callers MUST surface this: a warning in
   * general, and a hard error where the spec is the only metadata source (a descriptor-less `.ts`).
   * The fix is always to inline the spec object literal at the `trailblaze.tool(...)` call site.
   */
  val uncapturedSpec: Boolean = false,
) {
  init {
    // Forcing function: the analyzer caller treats schemas as JSON objects (passes
    // them into ajv, embeds them in MCP advertisements, prints them in debug
    // surfaces). A non-object top-level schema would only happen if the generator
    // emitted a bare `true`/`false` schema, which it doesn't for named type
    // references — pin the assumption here so a future change to the generator
    // surfaces loudly rather than producing confusing downstream failures.
    require(inputSchema is JsonObject) {
      "inputSchema for tool '$name' is not a JSON object: ${inputSchema::class.simpleName}"
    }
    require(outputSchema is JsonObject) {
      "outputSchema for tool '$name' is not a JSON object: ${outputSchema::class.simpleName}"
    }
  }

  /** Convenience accessor for callers that have already-narrowed `JsonObject` needs. */
  val inputSchemaObject: JsonObject get() = inputSchema.jsonObject
  val outputSchemaObject: JsonObject get() = outputSchema.jsonObject
}

/**
 * Per-(file, tool) error from the extractor shim — surfaces unsupported TS
 * constructs, malformed declarations, and `ts-json-schema-generator` failures.
 */
data class ScriptedToolDefinitionError(
  /** Absolute path to the `.ts` file the error originated from. */
  val file: String,
  /** Tool name if the error was attributable to a specific export; null otherwise. */
  val toolName: String?,
  /** Human-readable single-line message — the head of the underlying error. */
  val message: String,
)

/**
 * One analyzer run's full outcome over a trailmap's tools dir: the cleanly-extracted [tools]
 * plus any per-tool [errors] from the same run. Produced by a live subprocess walk and
 * round-tripped through [ScriptedToolDefinitionCache], so a cache hit replays exactly what
 * the original run reported — a non-empty [errors] makes
 * [ScriptedToolDefinitionAnalyzer.analyze] throw the same [ScriptedToolDefinitionException]
 * either way.
 */
internal data class AnalyzerOutcome(
  val tools: List<ScriptedToolDefinition>,
  val errors: List<ScriptedToolDefinitionError>,
)

/**
 * Raised by [ScriptedToolDefinitionAnalyzer.analyze] when the shim subprocess fails
 * (timeout, non-zero exit, malformed JSON) OR returns per-tool errors. Carries the
 * structured [errors] list so callers can surface every diagnostic at once rather
 * than one-at-a-time.
 *
 * [partialTools] is populated when the shim reported per-tool errors AND extracted
 * other tools cleanly in the same run. It is always empty when the failure was at
 * the subprocess level (timeout, non-zero exit, malformed JSON) since the analyzer
 * has no signal about which tools, if any, were extractable.
 *
 * **Consumption policy is the caller's decision.** Two canonical patterns:
 *
 * Strict all-or-nothing (fail the whole batch on any error):
 * ```kotlin
 * try {
 *   val defs = analyzer.analyze(trailmapToolsDir)
 *   // every tool extracted cleanly
 * } catch (e: ScriptedToolDefinitionException) {
 *   abortWith(e.errors)
 * }
 * ```
 *
 * Best-effort (emit healthy tools, log broken ones):
 * ```kotlin
 * val defs = try {
 *   analyzer.analyze(trailmapToolsDir)
 * } catch (e: ScriptedToolDefinitionException) {
 *   e.errors.forEach { logBrokenTool(it) }
 *   e.partialTools  // empty for subprocess-level failures, populated for per-tool errors
 * }
 * ```
 *
 * The best-effort pattern is what
 * [xyz.block.trailblaze.cli.CheckCommand.emitScriptedToolDefinitionsDebug] uses
 * — see it for the canonical consumption shape.
 */
class ScriptedToolDefinitionException(
  message: String,
  val errors: List<ScriptedToolDefinitionError>,
  val partialTools: List<ScriptedToolDefinition> = emptyList(),
  cause: Throwable? = null,
) : RuntimeException(message, cause)
