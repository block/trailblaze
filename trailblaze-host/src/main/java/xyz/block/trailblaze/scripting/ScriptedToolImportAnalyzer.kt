package xyz.block.trailblaze.scripting

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The verdict for a single scripted tool returned by [ScriptedToolImportAnalyzer.analyze].
 *
 *  - [requiresHost] — `true` when the import-closure analysis found a Node-only API
 *    (a `node:*` builtin or a known Node-only npm package). Dynamic `require(...)` detection
 *    is deferred — see the class-level kdoc on [ScriptedToolImportAnalyzer] for rationale.
 *  - [reason] — when [requiresHost] is true, a single-line breadcrumb in the shape
 *    `script.ts → axios → node:http` for logging. Null otherwise.
 */
data class RequiresHostAnalysis(
  val requiresHost: Boolean,
  val reason: String? = null,
)

/**
 * Static-analysis pre-pass that decides whether a scripted tool needs the host (Node/Bun)
 * runtime — i.e., would fail to load in the on-device QuickJS bundle — by introspecting
 * the import graph esbuild produces.
 *
 * The on-device QuickJS bundler ([DaemonScriptedToolBundler]) shells out to esbuild with
 * `--platform=neutral`. Pure-ES npm dependencies (lodash, zod, date-fns) bundle cleanly
 * into the on-device IIFE; `node:*` builtins do not. Today, the second case surfaces as a
 * cryptic "Could not resolve" esbuild error that aborts session start for every scripted
 * tool in the trailmap — even sibling tools that would have bundled fine on their own. This
 * analyzer lets the host runner short-circuit the bundle attempt for individual host-only
 * tools, log a clear breadcrumb, and continue with the on-device-viable siblings.
 *
 * **How it works.** Runs a dry-run esbuild pass with `--metafile` and every known host-only
 * import marked external (so the dry-run succeeds even when the user's `node_modules` is
 * absent or incomplete). The metafile is then walked depth-first from the user's `.ts`
 * entry; if any reachable input imports a `node:*` builtin or a known Node-only npm package,
 * the analyzer returns `requiresHost = true` with the offending dep-chain spelled out for
 * logging.
 *
 * **What gets flagged**
 *
 *  1. Direct or transitive import of a `node:*` builtin
 *     (`node:fs`, `node:http`, `node:child_process`, ...).
 *  2. Direct or transitive import of a known Node-runtime-only npm package — maintained in
 *     [KnownNodeOnlyPackages].
 *  3. (Deferred.) Non-statically-analyzable `require(...)` was called out by the issue as
 *     a third rule, but esbuild with `--platform=neutral` doesn't emit a clean warning for
 *     it — instead the bundler wraps the call in a runtime polyfill that throws
 *     `'Dynamic require of "X" is not supported'` when evaluated under QuickJS. The user
 *     therefore still gets a clear runtime error, just one step later than the breadcrumb
 *     channel. A future pass that does source-level static analysis (e.g. parsing the `.ts`
 *     with `@babel/parser` or esbuild's own analyzer) can recover the build-time signal.
 *
 * **What does NOT get flagged**
 *
 *  - A `.ts` tool that imports only `@trailblaze/scripting` (or no runtime deps at all) —
 *    today's invisible-TS default path. lodash, zod, date-fns, ramda, etc. also fall in
 *    this bucket because they're pure ES.
 *  - The author's explicit `requiresHost: true` flag — this analyzer is not called when
 *    that flag is already set (the caller short-circuits at the partition step).
 *
 * **Failure handling.** Unexpected esbuild failures (syntax error in the user's `.ts`,
 * unresolvable pure-ES dep when `node_modules` isn't populated, esbuild binary missing, the
 * subprocess hanging past the timeout) all collapse to `requiresHost = false` so the real
 * bundle pass can surface the genuine error with its existing error path. The analyzer's
 * job is to make on-device-viability decisions, not to second-guess legit bundling failures.
 */
class ScriptedToolImportAnalyzer(
  private val esbuildBinary: File,
  private val knownNodeOnlyPackages: Set<String> = KnownNodeOnlyPackages.DEFAULT,
) {
  /**
   * Analyse [scriptPath]'s import closure and return a [RequiresHostAnalysis] verdict.
   *
   * @param scriptPath The `.ts` (or `.js`) entry the user authored under `<trailmap>/tools/`.
   *   Must already exist on disk; a missing file collapses to `requiresHost = false` and
   *   defers to the real bundler's error path.
   */
  suspend fun analyze(scriptPath: File): RequiresHostAnalysis = withContext(Dispatchers.IO) {
    if (!esbuildBinary.isFile) return@withContext RequiresHostAnalysis(false)
    if (!scriptPath.isFile) return@withContext RequiresHostAnalysis(false)

    val metafile = File.createTempFile("trailblaze-analyzer-meta-", ".json")
    val outFile = File.createTempFile("trailblaze-analyzer-out-", ".js")
    try {
      // `node:*` glob is supported by esbuild ≥ 0.17. Combined with the known-Node-only
      // npm list, the externals cover every import path we want the analyser to *classify*
      // rather than *resolve*: by marking them external up front, esbuild doesn't try to
      // walk into `node_modules/axios/...` (which may have its own resolution issues), and
      // we still see them in the metafile as `external: true` entries we can match against
      // [classifyHostOnlyImport].
      val externals = buildList {
        add("node:*")
        addAll(knownNodeOnlyPackages)
      }
      val argv = listOf(
        esbuildBinary.absolutePath,
        scriptPath.absolutePath,
        "--bundle",
        "--platform=neutral",
        "--format=iife",
        "--target=es2020",
        "--main-fields=module,main",
        "--metafile=${metafile.absolutePath}",
        "--outfile=${outFile.absolutePath}",
        "--log-level=warning",
      ) + externals.map { "--external:$it" }

      val proc = ProcessBuilder(argv)
        // cwd = scriptPath's parent so relative imports resolve the same way the real bundle
        // does. Mirrors [DaemonScriptedToolBundler.runEsbuild]'s `.directory(entry.parentFile)`.
        .directory(scriptPath.parentFile)
        // Discard stdout/stderr at the OS level. The previous shape was
        // `.redirectErrorStream(true)` followed by a post-`waitFor` drain — which would
        // deadlock if esbuild produced more than ~64KB of warnings: the OS pipe buffer fills,
        // esbuild blocks on write, this thread blocks in `waitFor`, and the 60s timeout
        // would force-kill a successful-but-chatty run. The analyzer doesn't consume the
        // output today (the verdict comes from the metafile JSON), so dropping the pipe
        // entirely is the cleanest fix. A future dynamic-require detection pass that wants
        // the stream should drain it on a background thread BEFORE `waitFor`, not after.
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
      val finished = proc.waitFor(60, TimeUnit.SECONDS)
      if (!finished) {
        proc.destroyForcibly()
        proc.waitFor(5, TimeUnit.SECONDS)
        // Hung esbuild → no verdict. The real bundle pass will hit the same hang and produce
        // its existing timeout error.
        return@withContext RequiresHostAnalysis(false)
      }
      if (proc.exitValue() != 0) {
        // esbuild errored for a non-classification reason (syntax error, unresolvable
        // pure-ES dep, ...). Defer to the real bundle pass — we don't want to mask a
        // genuine author error behind a misleading "marked host-only" log line.
        return@withContext RequiresHostAnalysis(false)
      }

      // Metafile read + JSON decode + dep-walk can each throw on a corrupt artifact, a
      // schema change in a future esbuild version, or an unexpected filesystem state. Honor
      // the documented contract — unexpected analyzer failures collapse to
      // `requiresHost = false` so the real bundle pass surfaces the genuine error path
      // rather than us masking it behind a misleading "marked host-only" log line.
      val chain = try {
        val meta = parseMetafile(metafile.readText())
        findHostOnlyChain(meta, scriptPath)
      } catch (_: Throwable) {
        null
      }
      if (chain != null) {
        return@withContext RequiresHostAnalysis(requiresHost = true, reason = chain)
      }

      RequiresHostAnalysis(false)
    } finally {
      metafile.delete()
      outFile.delete()
    }
  }

  /**
   * Breadth-first walk over the metafile's `inputs` graph rooted at [scriptPath]. Returns
   * the formatted chain string for the first reachable host-only import, or `null` if the
   * closure is on-device-clean. esbuild's `inputs` keys are paths relative to the cwd the
   * subprocess ran in (the script's parent dir), so the user's entry shows up under its
   * bare filename. BFS over DFS so the surfaced chain is the *shortest* path to a host-only
   * external — the author sees their direct offending import in the breadcrumb rather than
   * a deeper indirect one when both paths exist in the same closure.
   */
  private fun findHostOnlyChain(meta: EsbuildMetafile, scriptPath: File): String? {
    val rootKey = meta.inputs.keys.firstOrNull {
      it == scriptPath.name || it.endsWith("/${scriptPath.name}")
    } ?: return null

    // parent[child] = parent-input-key (so we can reconstruct the path back to root).
    val parent = mutableMapOf<String, String>()
    val visited = mutableSetOf(rootKey)
    val queue = ArrayDeque<String>()
    queue.add(rootKey)

    while (queue.isNotEmpty()) {
      val current = queue.removeFirst()
      val input = meta.inputs[current] ?: continue
      for (imp in input.imports) {
        val path = imp.path
        if (imp.external == true) {
          val classified = classifyHostOnlyImport(path)
          if (classified != null) {
            return formatChain(parent, current, classified, rootKey)
          }
          // External but not a flag — could be e.g. `node:process` (the one esbuild flag
          // the real bundler also externalizes, deliberately). Don't recurse, don't flag.
          continue
        }
        if (path in visited) continue
        visited += path
        parent[path] = current
        queue.add(path)
      }
    }
    return null
  }

  /**
   * Returns the canonical chain-display name for [path] when it identifies a host-only
   * import, or null when the path is a benign external (e.g. `node:process`, which the real
   * bundler also marks external and which would be a false positive here).
   *
   *  - `node:fs`, `node:http`, ... → the full `node:*` specifier.
   *  - Names in [knownNodeOnlyPackages] → the package name verbatim.
   *
   * **Prefix matching for npm packages.** esbuild treats `--external:axios` as a
   * package-prefix match — both bare `axios` AND any subpath import like `axios/lib/foo`
   * show up in the metafile as `external: true` with the subpath as the literal `path`.
   * If we only checked exact set membership, a `import "axios/lib/foo"` would slip through
   * the classifier, the real bundle pass would then fail on the unresolved subpath, and
   * the sibling-abort behaviour this change is meant to prevent would re-emerge. So both
   * `axios` and `axios/lib/foo` must classify as the same package. Same rule applies to
   * scoped packages (`@scope/pkg` and `@scope/pkg/foo`) — no special-casing needed since
   * the `/`-delimited prefix check works for both.
   */
  private fun classifyHostOnlyImport(path: String): String? {
    // `node:process` is deliberately externalised by the real bundler — flagging it would
    // misclassify any tool that touches `process.env`. Every other `node:*` builtin is
    // unreachable in QuickJS and warrants the host-only verdict. `node:*` subpath imports
    // (`node:fs/promises`, etc.) are caught by the same `startsWith` check.
    if (path == "node:process") return null
    if (path.startsWith("node:")) return path
    // Map a possible subpath import back to its package root so the chain string stays
    // useful (`script.ts → axios`, not `script.ts → axios/lib/utils/foo`). The author
    // cares about the package they reached for, not which file inside it esbuild resolved.
    knownNodeOnlyPackages.firstOrNull { pkg ->
      path == pkg || path.startsWith("$pkg/")
    }?.let { return it }
    return null
  }

  /**
   * Walk parent pointers from [leaf] up to [rootKey] to assemble the chain string. The leaf
   * input is the LAST resolved module that imported the offending external — we then tack
   * the external specifier on the end. Example output:
   *
   *   `script.ts → axios`           // direct import
   *   `script.ts → bcrypt → node:crypto`  // indirect (bcrypt is not in the known list,
   *                                          but its node:crypto usage is)
   */
  private fun formatChain(
    parent: Map<String, String>,
    leaf: String,
    leafImportSpecifier: String,
    rootKey: String,
  ): String {
    val pathFromLeafToRoot = mutableListOf<String>()
    var node: String? = leaf
    while (node != null) {
      pathFromLeafToRoot += friendlyName(node)
      if (node == rootKey) break
      node = parent[node]
    }
    return pathFromLeafToRoot.reversed().joinToString(" → ") + " → " + leafImportSpecifier
  }

  /**
   * Convert an esbuild input key (`node_modules/axios/lib/index.js`,
   * `node_modules/@scope/pkg/index.js`, `script.ts`) into a name suitable for a chain
   * display: bare filenames pass through, `node_modules/<pkg>/...` collapses to `<pkg>`
   * (or `@scope/pkg` for scoped packages).
   */
  private fun friendlyName(path: String): String {
    val marker = "node_modules/"
    val idx = path.lastIndexOf(marker)
    if (idx < 0) return path
    val after = path.substring(idx + marker.length)
    val firstSlash = after.indexOf('/')
    val firstSeg = if (firstSlash < 0) after else after.substring(0, firstSlash)
    if (!firstSeg.startsWith("@") || firstSlash < 0) return firstSeg
    // Scoped package: include the second segment too (`@scope/pkg`).
    val rest = after.substring(firstSlash + 1)
    val secondSlash = rest.indexOf('/')
    val secondSeg = if (secondSlash < 0) rest else rest.substring(0, secondSlash)
    return "$firstSeg/$secondSeg"
  }

  private fun parseMetafile(json: String): EsbuildMetafile =
    JSON_LENIENT.decodeFromString(EsbuildMetafile.serializer(), json)

  @Serializable
  internal data class EsbuildMetafile(val inputs: Map<String, EsbuildInput> = emptyMap())

  @Serializable
  internal data class EsbuildInput(val imports: List<EsbuildImport> = emptyList())

  @Serializable
  internal data class EsbuildImport(
    val path: String,
    val kind: String? = null,
    val external: Boolean? = null,
  )

  companion object {
    private val JSON_LENIENT = Json { ignoreUnknownKeys = true }
  }
}
