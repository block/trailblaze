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
   * Workspace-local directory under which per-trailmap subprocess outputs are cached
   * so subsequent runs over byte-identical inputs short-circuit the bun + `ts-json-
   * schema-generator` walk. `null` (default) disables caching — the analyzer always
   * re-runs the subprocess. Set to `<workspace>/.trailblaze/cache/analyzer/` (see
   * [ScriptedToolDefinitionCache.resolveDefaultCacheDir]) to opt in, or pass an
   * explicit path in tests.
   *
   * The `TRAILBLAZE_TOOL_ANALYZER_NO_CACHE=1` env var (read once at JVM start)
   * bypasses cache lookup AND writes even when this is non-null — useful for
   * debugging suspected stale-cache scenarios without nuking the directory.
   */
  private val cacheDir: File? = null,
  /**
   * Forces the cache off even when [cacheDir] is non-null. Production default is
   * the value of the `TRAILBLAZE_TOOL_ANALYZER_NO_CACHE` env var, captured once at
   * JVM start via [ScriptedToolDefinitionCache.noCacheFromEnv]. Tests inject
   * `true` explicitly to exercise the bypass without mutating process env, which
   * Java doesn't expose cleanly anyway.
   */
  private val disableCache: Boolean = ScriptedToolDefinitionCache.noCacheFromEnv,
) {

  private val cache: ScriptedToolDefinitionCache? =
    if (cacheDir != null && !disableCache) {
      ScriptedToolDefinitionCache(
        cacheRoot = cacheDir,
        dependencyKey = ScriptedToolDefinitionCache.computeDependencyKey(sdkDir, extractorShim),
      )
    } else {
      null
    }

  /**
   * Walk every `.ts` file under [trailmapToolsDir] (recursively) and return the typed
   * tool definitions discovered in each.
   *
   * Returns an empty list when [trailmapToolsDir] doesn't exist, isn't a directory, or
   * contains no `.ts` files. Test files (`*.test.ts`) and declaration files
   * (`*.d.ts`) are filtered out — the analyzer's contract is "tool authoring files
   * only", consistent with how `bun test` and `tsc` discover tool source per trailmap.
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

    val tsFiles = collectToolFiles(trailmapToolsDir)
    if (tsFiles.isEmpty()) return@withContext emptyList()

    // Cache lookup happens BEFORE the bun/shim presence check — a cache hit serves
    // a fully-formed result that doesn't depend on either binary, and the only thing
    // bun/shim need to do on a hit is "not run." This lets a workspace that already
    // has cache entries continue to function after bun is uninstalled (or while a
    // CI agent is being rebuilt) without forcing every daemon caller through the
    // "degraded — bun missing" branch.
    val contentKey = cache?.let {
      ScriptedToolDefinitionCache.computeContentKey(trailmapToolsDir, tsFiles, it.dependencyKey)
    }
    if (cache != null && contentKey != null) {
      cache.lookup(trailmapToolsDir, contentKey)?.let { hit -> return@withContext hit }
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

    val cleanlyExtracted = envelope.tools.map { it.toDefinition() }
    if (envelope.errors.isNotEmpty()) {
      throw ScriptedToolDefinitionException(
        message = "extract-tool-defs.mjs reported ${envelope.errors.size} error(s) walking " +
          "${tsFiles.size} file(s) under ${trailmapToolsDir.absolutePath}.",
        errors = envelope.errors.map { rawErr ->
          ScriptedToolDefinitionError(
            file = rawErr.file,
            toolName = rawErr.name,
            message = rawErr.message,
          )
        },
        partialTools = cleanlyExtracted,
      )
    }

    // Persist the all-clean run to the cache. We intentionally don't cache the
    // mixed-outcome path above: an envelope with per-tool errors is a transient
    // author-editing state, not a steady-state result worth short-circuiting on the
    // next call (the author is presumably about to save again). Caching the
    // partial result would also surface the broken tool's diagnostics from cache
    // even after the .ts file changes, since the failed file's content wouldn't
    // be in the analyzer's input on a re-run.
    if (cache != null && contentKey != null) {
      cache.put(trailmapToolsDir, contentKey, cleanlyExtracted)
    }

    cleanlyExtracted
  }

  /**
   * Recursive walk under [trailmapToolsDir] that yields every author-source `.ts` file
   * (excluding `*.test.ts`, `*.d.ts`, and anything under the framework-generated
   * `.trailblaze/` directory). Results are sorted by absolute path so the analyzer
   * produces deterministic output regardless of filesystem-listing order.
   */
  private fun collectToolFiles(trailmapToolsDir: File): List<File> {
    val results = mutableListOf<File>()
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
        if (name.endsWith(".d.ts")) continue
        if (name.endsWith(".test.ts")) continue
        results += child
      }
    }
    return results.sortedBy { it.absolutePath }
  }

  private fun RawToolDefinition.toDefinition(): ScriptedToolDefinition =
    ScriptedToolDefinition(
      name = name,
      sourcePath = sourcePath,
      line = line,
      description = description,
      inputSchema = inputSchema,
      outputSchema = outputSchema,
      spec = spec,
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
     * Resolve the `bun` binary used to invoke the shim. Two resolution halves:
     *
     *  1. **`PATH` lookup** — covers a shell that's run `source bin/activate-hermit`
     *     (which puts the hermit-pinned `bin/bun` on PATH), a Homebrew install, or any
     *     globally-installed bun.
     *  2. **Repo `bin/` walk-up** — when bun isn't on PATH, walk up from CWD looking for
     *     the repo's hermit-pinned `bin/bun` symlink. This is the load-bearing fix for the
     *     **fresh-daemon** case: the `./trailblaze` wrapper spawns the daemon JVM with
     *     whatever PATH the calling shell had, and on a machine that already has JDK 21 the
     *     wrapper never sourced hermit, so `bun` was absent from the daemon's PATH and every
     *     meta-only / TS scripted-tool descriptor silently failed to enrich. The hermit
     *     `bin/bun` symlink is committed to the repo, so the walk-up resolves it regardless
     *     of how the daemon was launched — no `source bin/activate-hermit` required.
     *
     * Mirrors [LazyYamlScriptedToolRegistration.resolveEsbuildBinary]'s PATH-then-walk-up
     * shape; the difference is the walk-up target (`bin/bun`, the committed hermit symlink,
     * vs esbuild's SDK `node_modules/.bin/esbuild`).
     *
     * **Bun is the only JS runtime Trailblaze uses.** `setup.sh` installs bun
     * on every Runway CI agent, the SDK's own `bun install` populates the
     * `node_modules/` the shim resolves against, and the
     * `extract-tool-defs.mjs` shim itself was verified byte-identical between
     * bun and Node so there's no behavior incentive to keep a Node fallback.
     * A binary-installed Trailblaze user who authors TypeScript tools only
     * needs the bun runtime they already have for `bun install` — never a
     * separate Node install.
     *
     * `bun.exe` is tried alongside `bun` for Windows-checkout walk-ups.
     *
     * Returns null when bun is neither on PATH nor resolvable via the repo `bin/`
     * walk-up. Downstream callers degrade gracefully — the analyzer reports "no tools
     * extracted" and meta-only descriptors surface a "no bun" diagnostic instead of a
     * silent failure.
     */
    fun resolveBunBinary(): File? =
      resolveBunBinary(
        pathEnv = System.getenv("PATH"),
        startDir = File(System.getProperty("user.dir") ?: ".").absoluteFile,
      )

    /**
     * Composable form of the no-arg [resolveBunBinary]: PATH first (the bun-only contract),
     * then the repo `bin/bun` walk-up from [startDir]. Pulled out (and `internal`) so a unit
     * test can pin the actual production composition — a bun on PATH short-circuits the
     * walk-up; an absent PATH bun falls through to it (the JDK-21 fresh-daemon case) — without
     * mutating process env or depending on the host's CWD. The no-arg form just feeds this the
     * live `PATH` + CWD.
     */
    internal fun resolveBunBinary(pathEnv: String?, startDir: File): File? =
      resolveBunBinary(pathEnv) ?: resolveBunViaWalkup(startDir)

    /**
     * Overload for tests — accepts an explicit `PATH` string instead of
     * reading from the JVM env. Production callers use the no-arg form;
     * direct callers in this module mock PATH to pin the "bun-only" contract
     * (any other runtime on PATH must NOT be picked up) without mutating
     * process env. This half is PATH-only by design — the [resolveBunViaWalkup]
     * fallback is composed in by the no-arg [resolveBunBinary].
     */
    internal fun resolveBunBinary(pathEnv: String?): File? {
      val path = pathEnv ?: return null
      val dirs = path.split(File.pathSeparator).filter { it.isNotBlank() }
      for (name in listOf("bun", "bun.exe")) {
        for (dir in dirs) {
          val candidate = File(dir, name)
          if (candidate.exists() && candidate.canExecute()) return candidate
        }
      }
      return null
    }

    /**
     * Walk-up half of [resolveBunBinary]: walk from [startDir] upward looking for the
     * repo's committed hermit `bin/bun` (or `bin/bun.exe`) symlink. The hermit symlink
     * `bin/bun -> .bun-<version>.pkg` is checked into the repo (see root CLAUDE.md
     * "Toolchain"), so a daemon launched from anywhere under the repo tree resolves it
     * even when the spawning shell never ran `source bin/activate-hermit` — closing the
     * fresh-daemon gap where a JDK-21 host skipped hermit activation and shipped a daemon
     * with no `bun` on PATH, leaving TS scripted-tool descriptors unregistered.
     *
     * **Repo-gated.** The walk-up only accepts a `bin/bun` that sits next to the repo's
     * committed Hermit activation script (`bin/activate-hermit`). Without that gate the
     * walk-up would hand back the first executable named `bin/bun` in *any* ancestor of CWD
     * — so an installed CLI or an untrusted workspace that happens to carry a `bin/bun`
     * helper could get it executed as the analyzer runtime instead of cleanly degrading to
     * the previous missing-bun path. `bin/activate-hermit` is committed alongside the pinned
     * `bin/bun -> .bun-<version>.pkg` symlink (root CLAUDE.md "Toolchain"), so its presence in
     * the same `bin/` is a reliable marker that this is *this repo's* Hermit toolchain and not
     * a coincidental `bin/bun`.
     *
     * Pulled out (and `internal`) so a unit test can pin the walk-up against an injected
     * starting directory without depending on the host's actual CWD or repo layout. Requires
     * the symlink target be executable, matching the PATH half's `canExecute()` guard.
     */
    internal fun resolveBunViaWalkup(startDir: File): File? {
      var current: File? = startDir
      while (current != null) {
        val binDir = File(current, "bin")
        // Only trust this `bin/` if it's the repo's Hermit toolchain dir (proven by the
        // committed activation script), so we never execute an arbitrary ancestor's `bin/bun`.
        if (File(binDir, "activate-hermit").isFile) {
          for (name in listOf("bun", "bun.exe")) {
            val candidate = File(binDir, name)
            if (candidate.exists() && candidate.canExecute()) return candidate
          }
        }
        current = current.parentFile
      }
      return null
    }

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
        else -> extractBundledShim(bytes, cacheRoot).also {
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

    /**
     * Write [shimBytes] to `<cacheRoot>/tools/extract-tool-defs.mjs` (the layout
     * [resolveExtractorShim] expects) and return [cacheRoot]. Skip-write-if-content-matches
     * keeps the cached shim's mtime stable across runs of the same framework build. Split
     * out so it's unit-testable without a JAR on the classpath.
     */
    internal fun extractBundledShim(shimBytes: ByteArray, cacheRoot: File): File {
      val shimFile = File(cacheRoot, "tools/extract-tool-defs.mjs")
      val stale = !shimFile.isFile ||
        shimFile.length() != shimBytes.size.toLong() ||
        !shimFile.readBytes().contentEquals(shimBytes)
      if (stale) {
        shimFile.parentFile.mkdirs()
        shimFile.writeBytes(shimBytes)
      }
      // Marker so [analyzerToolingAvailable] recognizes this dir as the self-contained
      // bundle — it has no `node_modules/` (the deps are inlined into the shim), which the
      // source-tree preflight would otherwise reject.
      val marker = File(cacheRoot, BUNDLED_ANALYZER_MARKER_FILENAME)
      if (!marker.isFile) {
        marker.parentFile.mkdirs()
        marker.writeText("Trailblaze self-contained scripted-tool analyzer shim bundle.\n")
      }
      return cacheRoot
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
   * `TrailblazeTypedToolSpec` field names (`supportedPlatforms`, `requiresContext`,
   * `requiresHost`, `supportedDrivers`) and values are the JSON-compatible literals
   * the author wrote at the call site (string arrays, booleans).
   *
   * `null` when the author used the bare-handler overload OR when the spec's fields
   * were all unresolvable expressions (object spread, identifier reference, function
   * call). Callers should default each missing field to the framework default (false
   * for booleans, empty for the platform/driver gates which the runtime treats as
   * "unrestricted").
   *
   * Downstream consumers translate this map into the namespaced `_meta` JSON
   * (`trailblaze/supportedPlatforms`, etc.) the runtime reads — see
   * [xyz.block.trailblaze.scripting.AnalyzerScriptedToolEnrichment.mergeMeta] for the
   * canonical projection.
   */
  val spec: JsonObject? = null,
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
