package xyz.block.trailblaze.scripting.subprocess

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import xyz.block.trailblaze.config.McpServerConfig
import xyz.block.trailblaze.llm.config.ClasspathResourceDiscovery
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Result of [McpSubprocessSpawner.spawn] — the live JVM [Process], plus the resolved path and
 * argv Trailblaze built it from. Callers wire [Process.inputStream] / [Process.outputStream]
 * into the MCP stdio transport (commit: Stdio MCP client wiring) and consume
 * [Process.errorStream] into the session log directory (commit: Lifecycle).
 */
data class SpawnedProcess(
  val process: Process,
  val scriptFile: File,
  val argv: List<String>,
)

/**
 * Spawns a bun subprocess for an `mcp_servers: { script: ... }` entry and hands back a
 * [SpawnedProcess]. Handshake, tool registration, and teardown belong to later commits; this
 * object only handles the "turn an [McpServerConfig] plus [McpSpawnContext] into a running
 * subprocess" step.
 *
 * ### Path resolution
 *
 * Target YAMLs live on the classpath, not the filesystem, so "relative to the YAML's
 * directory" has no filesystem meaning. Two roots are tried, in order:
 *
 * - **The filesystem.** Absolute paths in `script:` pass through unchanged; relative paths
 *   resolve against the JVM's current working directory (`System.getProperty("user.dir")`).
 *   Matches the expectation of authors who run `./trailblaze` from their project root with a
 *   `script: ./tools/myapp/login.ts` entry. Tests override the anchor via
 *   [resolveScriptPath]'s `anchor` parameter.
 * - **The classpath.** A trailmap resolved FROM the classpath ships its `.ts` tool sources
 *   inside the JAR, so in any workspace that isn't the framework checkout the `script:` path
 *   names a file that exists only as a resource. [resolveFromClasspath] extracts it there.
 *
 * That second root is why this object also owns a bit of TypeScript module resolution: an
 * extracted tool still opens with `import { trailblaze } from "@trailblaze/scripting"`, and a
 * bare specifier has nothing to resolve against in a temp directory. So the extraction writes
 * the JAR's own SDK next to the tools and a `tsconfig.json` that aliases the specifier onto it.
 * Without that half the script is found and the subprocess dies at import instead.
 */
object McpSubprocessSpawner {

  /**
   * Resolves the `script:` path against the working-directory anchor and validates that the
   * file exists. Exposed for testing; callers normally get an already-resolved file from
   * [configure].
   *
   * Resolution order:
   *  1. If the path (absolute or resolved against [anchor]) names an existing file, return it.
   *  2. **Classpath fallback** — if the path contains the `trails/config/trailmaps/<id>/tools/`
   *     anchor segment, attempt to extract the script (its sibling `.ts` files, the SDK, and an
   *     SDK-aliasing `tsconfig.json`) from the classpath into a temp directory and return the
   *     extracted path. This covers classpath-resolved trailmaps whose `.ts` sources ship inside
   *     the uber JAR but whose `script:` paths resolve against a workspace CWD that doesn't
   *     contain them. See [resolveFromClasspath].
   *  3. Fail with a descriptive error.
   */
  fun resolveScriptPath(
    script: String,
    anchor: File = File(System.getProperty("user.dir")),
  ): File {
    require(script.isNotBlank()) { "mcp_servers `script:` must be non-blank" }
    val raw = File(script)
    val resolved = if (raw.isAbsolute) raw else File(anchor, script)
    val absolute = resolved.absoluteFile
    if (absolute.isFile) return absolute

    // Classpath fallback: the script may be bundled in the JAR for a classpath-resolved
    // trailmap. Extract it (and its sibling tools) to a temp directory.
    resolveFromClasspath(script)?.let { return it }

    // Neither filesystem nor classpath resolution succeeded.
    throw IllegalArgumentException(
      "mcp_servers script not found at $absolute (resolved from '$script' against $anchor)"
    )
  }

  /**
   * Classpath-resource fallback for [resolveScriptPath]. When the daemon runs from an installed
   * uber JAR in a workspace that is NOT the framework source checkout, a classpath-resolved
   * trailmap's `script:` field names a path like
   * `trails/config/trailmaps/mylib/tools/mylib_dismissIfPresent.ts` that doesn't exist on the
   * workspace filesystem — but IS bundled in the JAR under that same classpath path.
   *
   * This method detects the `<trailmapId>/tools` anchor in the script path, extracts the
   * entire `tools/` tree for that trailmap (preserving subdirectory structure so relative
   * imports resolve under bun/esbuild), and returns the extracted script file. Extraction is
   * keyed by trailmap id so repeated calls for tools in the same trailmap reuse the directory.
   *
   * ### Why this also extracts the SDK
   *
   * Every bundled tool opens with `import { trailblaze } from "@trailblaze/scripting"`. That is a
   * bare specifier, and bun resolves those by walking up from the IMPORTING file looking for
   * `node_modules` or a `tsconfig.json` `paths` mapping — so from a temp directory it resolves to
   * nothing and the subprocess dies with `Cannot find module '@trailblaze/scripting'` during the
   * MCP handshake. Extracting the `.ts` alone therefore only moves the failure later.
   *
   * So the same JAR's SDK (shipped at [SDK_CLASSPATH_PREFIX] — the same resource tree the host
   * module extracts into `<workspace>/.trailblaze/sdk/` for a workspace's own trailmaps) lands
   * under `<extractRoot>/sdk/`, and a `tsconfig.json` aliasing the specifier onto it lands at
   * the tools root. Bun finds that config by the same walk-up, which is why it works even though
   * the spawned process's working directory is the wrapper's session directory rather than this
   * one, and why a tool nested in `tools/<subdir>/` is covered too.
   *
   * Returns null when the path has no recognizable anchor or the resource isn't on the
   * classpath — the caller then falls through to its existing error.
   *
   * @throws IllegalStateException if the tools extract but this JAR ships no SDK at
   *   [SDK_CLASSPATH_PREFIX]. That is a mis-built JAR, and every tool it carries is unloadable;
   *   failing here names the cause instead of leaving a module-not-found in a subprocess log.
   */
  internal fun resolveFromClasspath(
    script: String,
    loadClasspathResource: (String) -> String? = { ClasspathResourceDiscovery.loadResource(it) },
    listClasspathToolScripts: (String) -> Set<String> = { toolsRoot ->
      ClasspathResourceDiscovery.discoverFilenamesRecursive(toolsRoot, ".ts")
    },
    loadSdkResources: () -> Map<String, String> = {
      ClasspathResourceDiscovery.discoverAndLoadRecursive(SDK_CLASSPATH_PREFIX, "")
    },
    extractRoot: File = File(
      System.getProperty("java.io.tmpdir"),
      CLASSPATH_EXTRACT_SUBDIR,
    ),
  ): File? {
    val normalized = script.replace(File.separatorChar, '/')
    val anchorIdx = normalized.indexOf("$TRAILMAPS_CLASSPATH_ANCHOR/")
    if (anchorIdx < 0) return null
    // classpathPath = trails/config/trailmaps/<id>/tools/<subdirs…>/<name>.ts
    val classpathPath = normalized.substring(anchorIdx)
    // Segments after the anchor: <id>/tools/<subdirs…>/<name>.ts. Require <id>/tools/<file> at
    // minimum; the script may sit any number of subdirectories deep under `tools`.
    val segments = classpathPath.removePrefix("$TRAILMAPS_CLASSPATH_ANCHOR/").split('/')
    if (segments.size < 3 || segments[1] != SCRIPTED_TOOLS_DIR) return null
    val trailmapId = segments[0]
    // Guard against path traversal: reject segments containing ".." or absolute-path components.
    if (segments.any { it == ".." || it.startsWith("/") }) return null
    val toolsRoot = "$TRAILMAPS_CLASSPATH_ANCHOR/$trailmapId/$SCRIPTED_TOOLS_DIR"
    // The requested script's path relative to the tools root — preserves any subdirectories.
    val requestedRelPath = classpathPath.removePrefix("$toolsRoot/")
    // The requested script must itself be present on the classpath — otherwise this isn't a
    // classpath-bundled trailmap tool, so return null and let the caller's not-found error fire.
    if (loadClasspathResource(classpathPath) == null) return null

    // Trailmap id keys the extraction dir so tools from different trailmaps don't collide, and
    // repeated calls within one trailmap reuse it.
    val extractDir = File(extractRoot, "$trailmapId/$SCRIPTED_TOOLS_DIR")

    // Extract every `.ts` under the tools root, PRESERVING subdirectory structure, so nested
    // scripts (tools/<subdir>/x.ts) resolve AND their relative imports (siblings, ./subdir/…, ../)
    // find their targets on disk for bun. See [writeAtomically] for the write's guarantees.
    val extractedScript = try {
      for (relPath in listClasspathToolScripts(toolsRoot) + requestedRelPath) {
        // Guard individual relPaths against traversal too.
        if (relPath.contains("..") || relPath.startsWith("/")) continue
        val content = loadClasspathResource("$toolsRoot/$relPath") ?: continue
        writeAtomically(File(extractDir, relPath), content)
      }
      File(extractDir, requestedRelPath).takeIf { it.isFile }
    } catch (_: IOException) {
      // Extraction failed (disk full, permissions, etc.) — fall through to the caller's
      // descriptive not-found error rather than surfacing an opaque IOException.
      null
    } ?: return null

    // The tools are on disk but unloadable until `@trailblaze/scripting` resolves — see the
    // KDoc above. Extract this JAR's SDK and alias the specifier onto it.
    val sdkResources = loadSdkResources()
    check(sdkResources.isNotEmpty()) {
      "Extracted '$requestedRelPath' for trailmap '$trailmapId' from the classpath, but this " +
        "build ships no scripted-tool SDK at classpath resource '$SDK_CLASSPATH_PREFIX', so the " +
        "tool's `import { trailblaze } from \"@trailblaze/scripting\"` cannot resolve and the " +
        "MCP subprocess would fail at import. Rebuild so the SDK resources are packaged."
    }
    val sdkDir = File(extractRoot, SDK_EXTRACT_SUBDIR)
    return try {
      for ((relPath, content) in sdkResources) {
        if (relPath.contains("..") || relPath.startsWith("/")) continue
        writeAtomically(File(sdkDir, relPath), content)
      }
      // Written last so nothing extracted above can shadow it, and at the tools root so bun's
      // walk-up finds it from a tool at any depth.
      writeAtomically(File(extractDir, "tsconfig.json"), renderSdkAliasTsconfig(sdkDir))
      extractedScript
    } catch (_: IOException) {
      null
    }
  }

  /**
   * Writes [content] to [out] via a sibling temp file and a rename, so a concurrent reader never
   * observes a truncated file. Overwrites unconditionally: content is immutable for a given JAR,
   * so a re-extract is idempotent, and overwriting is what lets a daemon restart on a newer JAR
   * pick up updated sources rather than serving a stale extraction.
   */
  private fun writeAtomically(out: File, content: String) {
    out.parentFile?.mkdirs()
    // `File.createTempFile` requires a 3+ character prefix, so pad for short basenames like "x.ts".
    val prefix = out.nameWithoutExtension.padEnd(3, '_') + "."
    val tmp = File.createTempFile(prefix, ".tmp", out.parentFile)
    try {
      tmp.writeText(content)
      try {
        Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      } catch (_: AtomicMoveNotSupportedException) {
        // Layered/network filesystems may not support ATOMIC_MOVE. Fall back to a plain
        // REPLACE_EXISTING move — still safe because content is idempotent (same bytes from
        // the same JAR), so a concurrent reader that catches a partial write just retries.
        Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING)
      }
    } finally {
      // A throw above (or a move that left the source behind) must not strand a `.tmp` beside
      // the extracted tools, where the caller's `catch (IOException)` would hide it forever.
      tmp.delete()
    }
  }

  /**
   * The `tsconfig.json` written at an extraction's tools root, mapping `@trailblaze/scripting`
   * onto the SDK extracted into [sdkDir].
   *
   * Deliberately carries `paths` and nothing else: its only consumer is bun's module resolver at
   * spawn time (no `tsc` ever runs against an extract directory), and every option omitted is one
   * that cannot conflict with how the runtime loads the tool. Paths are absolute, so the file does
   * not care where it sits relative to the SDK. Serialized through kotlinx rather than string
   * concatenation so a path needing JSON escaping cannot produce an unparseable config.
   */
  private fun renderSdkAliasTsconfig(sdkDir: File): String {
    val distDir = File(sdkDir, SDK_DIST_DIR_NAME).absoluteFile.invariantSeparatorsPath
    return TSCONFIG_JSON.encodeToString(
      JsonObject.serializer(),
      buildJsonObject {
        putJsonObject("compilerOptions") {
          putJsonObject("paths") {
            putJsonArray(SDK_PACKAGE_NAME) { add("$distDir/index") }
            putJsonArray("$SDK_PACKAGE_NAME/*") { add("$distDir/*") }
          }
        }
      },
    )
  }

  /** Classpath directory prefix under which trailmaps (and their `tools/<name>.ts` sources) are
   * bundled as resources. */
  private const val TRAILMAPS_CLASSPATH_ANCHOR = "trails/config/trailmaps"

  /** Trailmap-relative subdirectory that owns scripted-tool `.ts` sources. */
  private const val SCRIPTED_TOOLS_DIR = "tools"

  /** Temp-dir subdirectory under `java.io.tmpdir` for classpath-extracted `.ts` sources. */
  private const val CLASSPATH_EXTRACT_SUBDIR = "trailblaze-mcp-classpath-scripts"

  /**
   * Classpath prefix of the TypeScript SDK this JAR ships (`dist/index.js` and friends), packaged
   * by `:trailblaze-models`. Keep in step with that module's staging task and with the host
   * module's workspace extraction, which read the same tree.
   */
  private const val SDK_CLASSPATH_PREFIX = "trails/config/sdk/typescript"

  /** Sibling of the per-trailmap tool dirs under the extract root that receives the SDK. */
  private const val SDK_EXTRACT_SUBDIR = "sdk"

  /** Directory inside [SDK_CLASSPATH_PREFIX] (and the extract) holding the SDK's entry points. */
  private const val SDK_DIST_DIR_NAME = "dist"

  /** The bare specifier every scripted tool imports the authoring surface from. */
  private const val SDK_PACKAGE_NAME = "@trailblaze/scripting"

  /** Pretty-printed so a developer inspecting an extract directory can read the mapping. */
  private val TSCONFIG_JSON = Json { prettyPrint = true }

  /**
   * Output of [configure] — the configured [ProcessBuilder] plus the resolved script file the
   * builder was primed with. Lets [spawn] (and tests) avoid re-resolving the path.
   */
  data class Configured(val builder: ProcessBuilder, val scriptFile: File)

  /**
   * Builds a [ProcessBuilder] primed with argv, working directory, and [envVars] for the
   * given config + context. Does **not** call [ProcessBuilder.start] — that's [spawn]'s job.
   *
   * Separated so tests can assert on env inheritance, argv, and cwd without needing bun
   * available in the test environment.
   */
  fun configure(
    config: McpServerConfig,
    context: McpSpawnContext,
    runtime: BunRuntime,
    anchor: File = File(System.getProperty("user.dir")),
  ): Configured {
    val script = requireNotNull(config.script) {
      "configure() called with a non-script McpServerConfig (command: entries not yet supported)"
    }
    val scriptFile = resolveScriptPath(script, anchor)
    val argv = runtime.argv(scriptFile)
    val builder = ProcessBuilder(argv)
      .directory(scriptFile.parentFile)
      .redirectErrorStream(false)
    val env = builder.environment() // parent env already populated
    envVars(context, scriptFile).forEach { (k, v) -> env[k] = v }
    return Configured(builder, scriptFile)
  }

  /**
   * Resolves, configures, and starts the subprocess. Returns the live handle wrapping the JVM
   * [Process] plus the inputs used so diagnostics can record "we invoked bun run /abs/x.ts."
   */
  fun spawn(
    config: McpServerConfig,
    context: McpSpawnContext,
    runtime: BunRuntime = BunRuntimeDetector.cached,
    anchor: File = File(System.getProperty("user.dir")),
  ): SpawnedProcess {
    val (builder, scriptFile) = configure(config, context, runtime, anchor)
    return SpawnedProcess(
      process = builder.start(),
      scriptFile = scriptFile,
      argv = builder.command().toList(),
    )
  }

  /**
   * The `TRAILBLAZE_*` env vars at subprocess spawn. **Public API** — per the scope devlog
   * (§ Environment contract), breaking changes require a bump. Authors read these inside
   * their own MCP server to drive dynamic `tools/list` filtering, logging, etc.
   */
  internal fun envVars(context: McpSpawnContext, scriptFile: File): Map<String, String> = buildMap {
    put("TRAILBLAZE_DEVICE_PLATFORM", context.platform.name)
    put("TRAILBLAZE_DEVICE_DRIVER", context.driver.yamlKey)
    put("TRAILBLAZE_DEVICE_WIDTH_PX", context.widthPixels.toString())
    put("TRAILBLAZE_DEVICE_HEIGHT_PX", context.heightPixels.toString())
    put("TRAILBLAZE_SESSION_ID", context.sessionId.value)
    put("TRAILBLAZE_TOOLSET_FILE", scriptFile.absolutePath)
    // baseUrl surfaces both as an env var (readable at server-startup time) and in
    // `_meta.trailblaze.baseUrl` on each `tools/call` (authoritative per-call value). The env
    // var is a convenience for tools that want to configure state once at `initialize`. Omit
    // when unset so tests without a live HTTP server don't advertise a bogus URL.
    context.baseUrl?.let { put("TRAILBLAZE_BASE_URL", it) }
    // Keep the TS SDK's client-side fetch timeout in lockstep with the daemon's
    // `-Dtrailblaze.callback.timeoutMs`. Without this the client's default 32s abort would
    // fire before a daemon configured for a longer callback dispatch could return, defeating
    // the override. Buffer is small (2s) so the daemon is normally the source of a structured
    // timeout error rather than a client-side abort. Default-duplication with
    // `ScriptingCallbackEndpoint.DEFAULT_CALLBACK_TIMEOUT_MS` is intentional — a cross-module
    // constant accessor would create a dependency from `:trailblaze-scripting-subprocess` to
    // `:trailblaze-server` (wrong direction); the comment flags the two as in-lockstep.
    put("TRAILBLAZE_CLIENT_FETCH_TIMEOUT_MS", resolveClientFetchTimeoutMs().toString())
  }

  /** Keep in sync with `ScriptingCallbackEndpoint.DEFAULT_CALLBACK_TIMEOUT_MS`. */
  private const val DEFAULT_CALLBACK_TIMEOUT_MS: Long = 120_000L
  private const val CLIENT_FETCH_BUFFER_MS: Long = 2_000L

  internal fun resolveClientFetchTimeoutMs(): Long {
    val override = System.getProperty("trailblaze.callback.timeoutMs")?.toLongOrNull()?.takeIf { it > 0 }
    return (override ?: DEFAULT_CALLBACK_TIMEOUT_MS) + CLIENT_FETCH_BUFFER_MS
  }
}
