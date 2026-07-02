package xyz.block.trailblaze.trailrunner

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.scripting.ScriptedToolDefinitionAnalyzer
import xyz.block.trailblaze.scripting.callback.JsScriptingCallbackBaseUrl
import xyz.block.trailblaze.util.Console
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * WebSocket ↔ stdio bridge that gives the Trail Runner scripted-tool editor VS Code-grade TypeScript
 * support — autocomplete, hover, go-to-definition, and live error squiggles — by streaming the
 * Language Server Protocol between the in-browser editor and a real native `vtsls` TypeScript
 * language server.
 *
 * **Why a native language server rather than Monaco's in-browser TS worker.** Monaco's bundled
 * worker only sees the files you hand it; it can't resolve `@trailblaze/scripting` or a tool's
 * relative imports. `vtsls` runs against the actual workspace on disk, so it resolves types exactly
 * the way `tsc` / VS Code do — via the self-contained `tools/tsconfig.json` that
 * [xyz.block.trailblaze.host.PerTrailmapTsconfigEmitter] already writes next to every scripted tool
 * (it maps `@trailblaze/scripting` to the SDK declaration bundle that
 * [xyz.block.trailblaze.host.WorkspaceTypeScriptSetup] extracts into `<workspace>/.trailblaze/sdk/`).
 * The language server walks **up** from the opened `.ts` file to find that tsconfig, so resolution
 * needs no extra wiring here — the browser just has to open the file at its real on-disk `file://`
 * URI (see the frontend's `mountTypescript`).
 *
 * **Why this is safe to do simply.** Trail Runner is a single-user localhost daemon, so spawning one
 * language-server process per editor socket is cheap and carries none of the multi-tenant
 * process-fan-out / sandboxing concerns a hosted web IDE would. The process is owned by the socket:
 * it dies when the socket closes (or the server crashes), and a crash closes the socket.
 *
 * **Framing.** The browser ([monaco-languageclient] over [vscode-ws-jsonrpc]) carries one bare
 * JSON-RPC message per text frame; the server speaks `Content-Length`-delimited stdio. The
 * translation is [LspMessageFraming] — kept pure and unit-tested so the byte-level framing isn't at
 * the mercy of an integration test that needs a live process.
 *
 * **Degradation.** If `bun` or the SDK directory can't be resolved, the socket is closed cleanly with
 * a reason; the frontend falls back to the plain CodeMirror editor, so the editor never hard-breaks
 * just because the language server is unavailable.
 *
 * **Prerequisite:** the `webSocket {}` route requires the Ktor `WebSockets` plugin to be installed on
 * the host `Application` — the production daemon (`TrailblazeMcpServer`) installs it, and any test app
 * registering this route must `install(WebSockets)` too, or route registration throws.
 */
internal fun Route.lspRoutes(deps: TrailRunnerDeps) {
  // Resolve the canonical `file://` URI for a scripted tool's `.ts` source (and the workspace root),
  // so the browser editor can open the document at its REAL on-disk path. That real path is what lets
  // vtsls walk up to the per-trailmap `tools/tsconfig.json` and resolve `@trailblaze/scripting` — open
  // the file under a synthetic URI and type resolution silently breaks. `path` is the tool's
  // workspace-relative `sourcePath` (as carried in the tool catalog DTO). 404 when it can't be
  // resolved (the editor then mounts without LSP and degrades to plain syntax highlighting).
  get("$PATH_BASE/api/lsp/file-uri") {
    val path = call.request.queryParameters["path"]
    // A path-less request (the trail editor, which opens an in-memory `.trail.yaml` with no on-disk
    // source) still needs the daemon-local schema base URL so its schema fetch is proxy-safe — return
    // the base URLs with a null fileUri instead of 404. A NON-blank but unresolvable path stays a 404.
    if (path.isNullOrBlank()) {
      call.respond(
        LspFileUriResponse(
          workspaceUri = deps.trailsRootProvider().toPath().toUri().toString(),
          toolSchemaUrl = daemonLocalSchemaBaseUrl(),
        ),
      )
      return@get
    }
    val file = ToolSourceFiles.fileForResource(path)
    if (file == null) {
      call.respond(HttpStatusCode.NotFound, LspFileUriResponse())
      return@get
    }
    // nio's Path.toUri() yields the canonical `file:///abs/path` form (empty authority, leading
    // triple slash) that vscode-uri on the client and vtsls both expect — unlike File.toURI(), whose
    // single-slash `file:/abs` form would mismatch on URI comparison between didOpen and diagnostics.
    call.respond(
      LspFileUriResponse(
        fileUri = file.toPath().toUri().toString(),
        workspaceUri = deps.trailsRootProvider().toPath().toUri().toString(),
        toolSchemaUrl = daemonLocalSchemaBaseUrl(),
      ),
    )
  }

  // TypeScript language server (vtsls) — for `.ts` scripted tools.
  webSocket(LSP_TYPESCRIPT_PATH) { serveLanguageServer(VTSLS_BIN) }

  // YAML language server (yaml-language-server) — for `.tool.yaml` definitions. Schema association
  // (validation + key/enum completion) is driven by the client's `yaml.schemas` initializationOptions
  // pointing at the `/api/lsp/tool-schema.json` route below; the bridge itself is transport-only.
  webSocket(LSP_YAML_PATH) { serveLanguageServer(YAML_LS_BIN) }

  // The JSON Schema for `.tool.yaml` tool definitions, generated from the LIVE tool catalog so the
  // `tools:` composition block autocompletes/validates real framework + trailmap tool names and their
  // params (see [ToolYamlSchemaBuilder]). The browser editor passes this URL — with `?trailmap=<id>`
  // to scope tool names to that target — to yaml-language-server via `yaml.schemas`, so the server (a
  // localhost child of this daemon) fetches it here. Catalog build is best-effort: on failure we still
  // serve the static envelope schema (empty catalog) so validation degrades to structure-only.
  get("$PATH_BASE/api/lsp/tool-schema.json") {
    val trailmap = call.request.queryParameters["trailmap"]?.takeIf { it.isNotBlank() }
    // Build the catalog fresh per fetch — yaml-language-server fetches the schema once per editor open,
    // so the cost (a tool-catalog build) is paid rarely; if it ever shows up, cache per-trailmap. On a
    // build failure we serve the envelope-only schema (empty catalog) so YAML validation degrades to
    // structure-only rather than erroring — but LOG it, or a "no YAML completions" report is untraceable.
    val catalog = withContext(Dispatchers.IO) {
      runCatching { ToolCatalogBuilder.build() }
        .onFailure { Console.log("[LspRoutes] tool-schema catalog build failed; serving envelope-only schema: ${it.message}") }
        .getOrDefault(emptyList())
    }
    call.respondBytes(
      ToolYamlSchemaBuilder.build(catalog, trailmap).encodeToByteArray(),
      ContentType.Application.Json,
      HttpStatusCode.OK,
    )
  }

  // The JSON Schema for `.trail.yaml` files, generated from the LIVE catalog and scoped to the tools
  // that register for the trail's TARGET (via `buildRunToolsResponse`) — so the `recording:` → `tools:`
  // blocks autocomplete/validate exactly the tools that target can run. The browser passes this URL
  // with `?target=<id>&platform=<p>` (read from the trail's own `config:`). If the target can't be
  // resolved we fall back to the whole catalog (over-offer) rather than an empty/erroring schema. See
  // [TrailYamlSchemaBuilder]. Catalog build is best-effort (structure-only degrade on failure).
  get("$PATH_BASE/api/lsp/trail-schema.json") {
    val target = call.request.queryParameters["target"]?.trim().orEmpty()
    val platform = call.request.queryParameters["platform"]?.trim().orEmpty()
    val driver = call.request.queryParameters["driver"]?.trim().orEmpty()
    val (catalog, targetToolNames) = withContext(Dispatchers.IO) {
      val cat = runCatching { ToolCatalogBuilder.build() }
        .onFailure { Console.log("[LspRoutes] trail-schema catalog build failed; serving structure-only schema: ${it.message}") }
        .getOrDefault(emptyList())
      // Resolve the target's registered tool names; null when the target/driver can't be resolved so
      // the schema falls back to the whole catalog (completion still works, just un-scoped). Log a
      // resolution failure — without it a silently un-scoped schema makes "why aren't my tools scoped
      // to my target?" untraceable (mirrors the catalog-build logging above).
      val names = if (target.isNotEmpty()) {
        runCatching { buildRunToolsResponse(deps, target, driver, platform) }
          .onFailure { Console.log("[LspRoutes] trail-schema tool-scope resolution failed for target=$target driver=$driver platform=$platform; serving whole-catalog schema: ${it.message}") }
          .getOrNull()
          ?.takeIf { it.resolved }
          ?.toolsets?.flatMap { it.tools }?.toSet()
      } else {
        null
      }
      cat to names
    }
    call.respondBytes(
      TrailYamlSchemaBuilder.build(catalog, targetToolNames).encodeToByteArray(),
      ContentType.Application.Json,
      HttpStatusCode.OK,
    )
  }
}

/**
 * Spawn the given language-server bin via `bun x <bin> --stdio` and bridge it to this WebSocket. Shared
 * by the TypeScript and YAML routes — the only thing that differs is the bin name. Degrades gracefully
 * (closes the socket with a reason) when bun / the SDK dir / the process can't be resolved or started,
 * so the frontend falls back to the plain CodeMirror editor.
 */
private suspend fun DefaultWebSocketServerSession.serveLanguageServer(binName: String) {
  val launch = resolveBunLaunch()
  if (launch == null) {
    Console.log(
      "[LspRoutes] $binName unavailable (bun or SDK dir not resolvable) — closing LSP socket; " +
        "the editor will degrade to plain syntax highlighting.",
    )
    close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "language server unavailable"))
    return
  }
  val (bun, sdkDir) = launch
  // `bun x <bin>` resolves the version pinned in the SDK's package.json from <sdkDir>/node_modules —
  // the same directory the scripted-tool analyzer runs bun in, so the install is already present in a
  // source build. For vtsls, cwd matters only for that resolution (the TS project root comes from the
  // opened file URI); yaml-language-server takes its schema config from the client, not cwd.
  val proc = try {
    withContext(Dispatchers.IO) {
      ProcessBuilder(bun.absolutePath, "x", binName, "--stdio")
        .directory(sdkDir)
        .redirectErrorStream(false)
        .start()
    }
  } catch (e: IOException) {
    Console.log("[LspRoutes] failed to spawn $binName via ${bun.absolutePath}: ${e.message}")
    close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "language server failed to start"))
    return
  }
  Console.log("[LspRoutes] $binName started (pid=${runCatching { proc.pid() }.getOrNull()}, cwd=${sdkDir.absolutePath})")
  bridge(proc, binName)
}

/**
 * Pump bytes between this WebSocket (bare JSON-RPC, one message per text frame) and the language
 * server's stdio (`Content-Length`-framed). [logTag] prefixes the server's stderr in the daemon log.
 * Owns the process: every exit path cancels the pumps and tears the process down.
 */
private suspend fun DefaultWebSocketServerSession.bridge(proc: Process, logTag: String) {
  val stdin = proc.outputStream
  val decoder = LspMessageFraming.Decoder()

  // server stdout -> WebSocket: one outbound text frame per complete LSP message.
  val stdoutPump = launch(Dispatchers.IO) {
    val buf = ByteArray(16 * 1024)
    val stdout = proc.inputStream
    runCatching {
      while (true) {
        val n = stdout.read(buf)
        if (n < 0) break
        for (message in decoder.feed(buf.copyOf(n))) {
          send(Frame.Text(message))
        }
      }
    }.onFailure {
      // A read error or a framing violation (Decoder throws on a header with no Content-Length) ends
      // the pump WITHOUT the process having exited — so exitWatch won't fire. Close the socket here
      // too, or the browser hangs forever awaiting a reply that will never come. Closing lets the
      // client surface the failure and fall back to CodeMirror, preserving graceful degradation.
      Console.log("[LspRoutes] stdout pump failed (read or framing error): ${it.message}")
      runCatching { close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "language server stream error")) }
    }
  }
  // server stderr -> daemon log, so a language-server crash is diagnosable (grep "[$logTag]").
  val stderrPump = launch(Dispatchers.IO) {
    runCatching { proc.errorStream.bufferedReader().forEachLine { Console.log("[$logTag] $it") } }
  }
  // If the server exits on its own (crash / OOM), close the socket so the client stops waiting on a
  // dead server rather than hanging until the user navigates away.
  val exitWatch = launch(Dispatchers.IO) {
    runCatching { proc.waitFor() }
    runCatching { close(CloseReason(CloseReason.Codes.NORMAL, "language server exited")) }
  }

  try {
    // WebSocket -> server stdin: frame each inbound bare-JSON message.
    for (frame in incoming) {
      if (frame is Frame.Text) {
        val framed = LspMessageFraming.encode(frame.readText())
        withContext(Dispatchers.IO) {
          stdin.write(framed)
          stdin.flush()
        }
      }
    }
  } catch (e: Exception) {
    Console.log("[LspRoutes] inbound pump ended: ${e.message}")
  } finally {
    // One teardown breadcrumb regardless of why the socket ended (client close, init-timeout close,
    // server exit, or stream error). Paired with the spawn log and the stdout-pump-failure log, this
    // lets a "completions stopped working" report be traced: a pump-failure line means a framing/read
    // error; this line alone means a clean or client-initiated (e.g. timeout) close.
    Console.log("[LspRoutes] LSP socket closed — tearing down $logTag (pid=${runCatching { proc.pid() }.getOrNull()})")
    stdoutPump.cancel()
    stderrPump.cancel()
    exitWatch.cancel()
    runCatching { stdin.close() }
    destroyQuietly(proc)
  }
}

/**
 * Resolve the `(bun, sdkDir)` pair needed to launch a language server, or null when either is
 * unavailable (the caller then degrades the socket gracefully). Reuses the scripted-tool analyzer's
 * resolvers so the bun binary (PATH + Hermit `bin/bun` walk-up) and SDK directory (walk-up +
 * `TRAILBLAZE_SDK_DIR`) are found exactly the way the rest of the framework's bun tooling finds them.
 */
private fun resolveBunLaunch(): Pair<File, File>? {
  val bun = ScriptedToolDefinitionAnalyzer.resolveBunBinary() ?: return null
  val sdkDir = ScriptedToolDefinitionAnalyzer.resolveSdkDir() ?: return null
  return bun to sdkDir
}

/** Terminate [proc] politely, escalating to a forcible kill if it doesn't exit within a short grace window. */
private fun destroyQuietly(proc: Process) {
  runCatching {
    proc.destroy()
    if (!proc.waitFor(2, TimeUnit.SECONDS)) proc.destroyForcibly()
  }
}

/** WebSocket route path for the TypeScript language-server bridge, under the Trail Runner [PATH_BASE]. */
internal const val LSP_TYPESCRIPT_PATH = "$PATH_BASE/lsp/typescript"

/** WebSocket route path for the YAML language-server bridge, under the Trail Runner [PATH_BASE]. */
internal const val LSP_YAML_PATH = "$PATH_BASE/lsp/yaml"

/**
 * The bin name `bun x` resolves to launch the YAML language server — Red Hat's `yaml-language-server`,
 * the same engine VS Code's YAML extension uses. Provided by the `yaml-language-server` package pinned
 * in `sdks/typescript/package.json`, so `bun x` resolves a deterministic version from the
 * SDK's `node_modules/.bin/` rather than downloading on every connect. Package name == bin name here.
 */
internal const val YAML_LS_BIN = "yaml-language-server"

/**
 * The bin name `bun x` resolves to launch the TypeScript language server. `vtsls` wraps the same
 * `tsserver` VS Code uses and bundles its own TypeScript, so no separate `typescript` install on the
 * workspace is required. The bin is provided by the `@vtsls/language-server` package, which is pinned
 * in `sdks/typescript/package.json` so `bun x` resolves a deterministic version from the
 * SDK's `node_modules/.bin/` (the same `node_modules` the scripted-tool analyzer uses) rather than
 * downloading the latest on every connect. `bun x` matches on the bin name, not the package name —
 * hence `vtsls`, not `@vtsls/language-server`, here.
 */
internal const val VTSLS_BIN = "vtsls"

/**
 * Response for `GET $PATH_BASE/api/lsp/file-uri`: the canonical `file://` URI of a scripted tool's
 * source on disk and the workspace-root URI, both consumed by the browser editor's `mountTypescript`
 * to open the document at its real path and point the language server at the workspace. Both null on
 * a 404 (unresolvable path) so the client can cleanly fall back to a non-LSP editor.
 */
/**
 * Daemon-LOCAL base URL for the tool-schema endpoint (the trail editor derives the trail-schema URL
 * from it). yaml-language-server runs as a child of this daemon, not in the browser, so it must fetch
 * schemas from a URL reachable on the daemon host — NOT the browser-facing `document.baseURI`, which is
 * a proxy origin (e.g. behind a cloud-workstation preview reverse-proxy) the daemon child can't reach. Built from
 * the daemon's own server URL; null in contexts where it isn't set (e.g. tests), where the client falls
 * back to document.baseURI (correct for direct localhost access).
 */
private fun daemonLocalSchemaBaseUrl(): String? =
  JsScriptingCallbackBaseUrl.get()
    ?.trimEnd('/')
    ?.let { "$it$PATH_BASE/api/lsp/tool-schema.json" }

@Serializable
internal data class LspFileUriResponse(
  val fileUri: String? = null,
  val workspaceUri: String? = null,
  /** Daemon-local URL of the tool-schema endpoint, for yaml-language-server to fetch (proxy-safe). */
  val toolSchemaUrl: String? = null,
)
