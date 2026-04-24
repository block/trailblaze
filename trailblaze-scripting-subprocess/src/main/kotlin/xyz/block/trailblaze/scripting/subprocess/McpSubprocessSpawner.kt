package xyz.block.trailblaze.scripting.subprocess

import xyz.block.trailblaze.config.McpServerConfig
import java.io.File

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
 * Spawns a bun/tsx subprocess for an `mcp_servers: { script: ... }` entry and hands back a
 * [SpawnedProcess]. Handshake, tool registration, and teardown belong to later commits; this
 * object only handles the "turn an [McpServerConfig] plus [McpSpawnContext] into a running
 * subprocess" step.
 *
 * ### Path resolution (open design question settled here)
 *
 * Target YAMLs live on the classpath, not the filesystem, so "relative to the YAML's
 * directory" has no filesystem meaning. MVP convention:
 *
 * - **Absolute paths** in `script:` pass through unchanged.
 * - **Relative paths** resolve against the JVM's current working directory
 *   (`System.getProperty("user.dir")`). Matches the expectation of authors who run
 *   `./trailblaze` from their project root with a `script: ./tools/myapp/login.ts` entry.
 *
 * Tests can override the anchor via [resolveScriptPath]'s `anchor` parameter. A future
 * `script_root:` field on `AppTargetYamlConfig` can override this per target if a concrete
 * need surfaces; deliberately not MVP.
 */
object McpSubprocessSpawner {

  /**
   * Resolves the `script:` path against the working-directory anchor and validates that the
   * file exists. Exposed for testing; callers normally get an already-resolved file from
   * [configure].
   */
  fun resolveScriptPath(
    script: String,
    anchor: File = File(System.getProperty("user.dir")),
  ): File {
    require(script.isNotBlank()) { "mcp_servers `script:` must be non-blank" }
    val raw = File(script)
    val resolved = if (raw.isAbsolute) raw else File(anchor, script)
    val absolute = resolved.absoluteFile
    require(absolute.isFile) {
      "mcp_servers script not found at $absolute (resolved from '$script' against $anchor)"
    }
    return absolute
  }

  /**
   * Output of [configure] — the configured [ProcessBuilder] plus the resolved script file the
   * builder was primed with. Lets [spawn] (and tests) avoid re-resolving the path.
   */
  data class Configured(val builder: ProcessBuilder, val scriptFile: File)

  /**
   * Builds a [ProcessBuilder] primed with argv, working directory, and [envVars] for the
   * given config + context. Does **not** call [ProcessBuilder.start] — that's [spawn]'s job.
   *
   * Separated so tests can assert on env inheritance, argv, and cwd without needing bun/tsx
   * available in the test environment.
   */
  fun configure(
    config: McpServerConfig,
    context: McpSpawnContext,
    runtime: NodeRuntime,
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
    runtime: NodeRuntime = NodeRuntimeDetector.cached,
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
  private const val DEFAULT_CALLBACK_TIMEOUT_MS: Long = 30_000L
  private const val CLIENT_FETCH_BUFFER_MS: Long = 2_000L

  internal fun resolveClientFetchTimeoutMs(): Long {
    val override = System.getProperty("trailblaze.callback.timeoutMs")?.toLongOrNull()?.takeIf { it > 0 }
    return (override ?: DEFAULT_CALLBACK_TIMEOUT_MS) + CLIENT_FETCH_BUFFER_MS
  }
}
