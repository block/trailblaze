package xyz.block.trailblaze.scripting.subprocess

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.messageContains
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.config.McpServerConfig
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.model.SessionId
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test

class McpSubprocessSpawnerTest {

  private val tmpDir = Files.createTempDirectory("mcp-spawner-test").toFile()
  private val scriptFile = File(tmpDir, "fixture.ts").apply { writeText("// no-op fixture\n") }
  private val bunRuntime = BunRuntime(File("/opt/homebrew/bin/bun"))
  private val context = McpSpawnContext(
    platform = TrailblazeDevicePlatform.ANDROID,
    driver = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
    widthPixels = 1080,
    heightPixels = 2400,
    sessionId = SessionId("session_test"),
  )

  @AfterTest fun cleanup() {
    tmpDir.deleteRecursively()
  }

  @Test fun `resolveScriptPath accepts absolute path`() {
    val resolved = McpSubprocessSpawner.resolveScriptPath(scriptFile.absolutePath)
    assertThat(resolved).isEqualTo(scriptFile.absoluteFile)
  }

  @Test fun `resolveScriptPath resolves relative against anchor`() {
    val resolved = McpSubprocessSpawner.resolveScriptPath("fixture.ts", anchor = tmpDir)
    assertThat(resolved).isEqualTo(scriptFile.absoluteFile)
  }

  @Test fun `resolveScriptPath errors on missing file with helpful message`() {
    assertFailure {
      McpSubprocessSpawner.resolveScriptPath("does-not-exist.ts", anchor = tmpDir)
    }.messageContains("mcp_servers script not found")
  }

  @Test fun `resolveScriptPath rejects blank path`() {
    assertFailure {
      McpSubprocessSpawner.resolveScriptPath("")
    }.messageContains("must be non-blank")
  }

  @Test fun `configure sets argv cwd and TRAILBLAZE env vars`() {
    val config = McpServerConfig(script = scriptFile.absolutePath)
    val configured = McpSubprocessSpawner.configure(config, context, bunRuntime)

    assertThat(configured.scriptFile).isEqualTo(scriptFile.absoluteFile)
    assertThat(configured.builder.command().toList()).isEqualTo(
      listOf("/opt/homebrew/bin/bun", "run", scriptFile.absolutePath),
    )
    assertThat(configured.builder.directory()).isEqualTo(scriptFile.absoluteFile.parentFile)

    val env = configured.builder.environment()
    assertThat(env["TRAILBLAZE_DEVICE_PLATFORM"]).isEqualTo("ANDROID")
    assertThat(env["TRAILBLAZE_DEVICE_DRIVER"]).isEqualTo("android-ondevice-accessibility")
    assertThat(env["TRAILBLAZE_DEVICE_WIDTH_PX"]).isEqualTo("1080")
    assertThat(env["TRAILBLAZE_DEVICE_HEIGHT_PX"]).isEqualTo("2400")
    assertThat(env["TRAILBLAZE_SESSION_ID"]).isEqualTo("session_test")
    assertThat(env["TRAILBLAZE_TOOLSET_FILE"]).isEqualTo(scriptFile.absolutePath)
    // Default path: daemon uses 120s dispatch timeout, subprocess fetch timeout is 122s
    // (120 + 2s buffer) so the daemon is normally the one that returns a structured error
    // rather than the client aborting first.
    assertThat(env["TRAILBLAZE_CLIENT_FETCH_TIMEOUT_MS"]).isEqualTo("122000")
  }

  @Test fun `resolveClientFetchTimeoutMs defaults to 122s and honors daemon override plus buffer`() {
    // The whole point of threading this through is to keep the client's abort above the
    // daemon's callback timeout. A regression that dropped the buffer or ignored the system
    // property would let the client abort first and swallow the daemon's structured error.
    System.clearProperty("trailblaze.callback.timeoutMs")
    assertThat(McpSubprocessSpawner.resolveClientFetchTimeoutMs()).isEqualTo(122_000L)

    try {
      System.setProperty("trailblaze.callback.timeoutMs", "60000")
      assertThat(McpSubprocessSpawner.resolveClientFetchTimeoutMs()).isEqualTo(62_000L)

      // Non-positive / non-numeric overrides silently fall back to the default — same
      // "silently-default on typo" tradeoff as the daemon-side resolveTimeoutMs.
      System.setProperty("trailblaze.callback.timeoutMs", "-1")
      assertThat(McpSubprocessSpawner.resolveClientFetchTimeoutMs()).isEqualTo(122_000L)

      System.setProperty("trailblaze.callback.timeoutMs", "abc")
      assertThat(McpSubprocessSpawner.resolveClientFetchTimeoutMs()).isEqualTo(122_000L)
    } finally {
      System.clearProperty("trailblaze.callback.timeoutMs")
    }
  }

  @Test fun `configure propagates daemon callback timeout override to TRAILBLAZE_CLIENT_FETCH_TIMEOUT_MS`() {
    // End-to-end through configure() — proves the resolver's value actually lands in the
    // spawned process's environment, not just the resolver's return value in isolation.
    System.setProperty("trailblaze.callback.timeoutMs", "45000")
    try {
      val config = McpServerConfig(script = scriptFile.absolutePath)
      val configured = McpSubprocessSpawner.configure(config, context, bunRuntime)
      assertThat(configured.builder.environment()["TRAILBLAZE_CLIENT_FETCH_TIMEOUT_MS"])
        .isEqualTo("47000")
    } finally {
      System.clearProperty("trailblaze.callback.timeoutMs")
    }
  }

  @Test fun `configure inherits parent environment`() {
    val config = McpServerConfig(script = scriptFile.absolutePath)
    val configured = McpSubprocessSpawner.configure(config, context, bunRuntime)
    val env = configured.builder.environment()

    // PATH is practically universal in inherited env; sanity-checks the inheritance path.
    assertThat(env.keys).contains("PATH")
  }

  @Test fun `configure rejects command-only entries`() {
    val config = McpServerConfig(command = listOf("python"))
    assertFailure {
      McpSubprocessSpawner.configure(config, context, bunRuntime)
    }.messageContains("command: entries not yet supported")
  }

  // --- Classpath fallback tests ---

  /**
   * Stand-in for the SDK resource tree the real JAR ships at `trails/config/sdk/typescript`.
   * Only the paths matter to these tests — the aliasing tsconfig names `dist/index`, and the
   * bun-backed test below is the one that proves the real SDK actually loads.
   */
  private val fakeSdkResources = mapOf(
    "dist/index.js" to "export const trailblaze = {};\n",
    "dist/index.d.ts" to "export declare const trailblaze: unknown;\n",
  )

  @Test fun `resolveFromClasspath extracts script from classpath when present`() {
    val extractRoot = File(tmpDir, "extract")
    val scriptContent = "// classpath tool\n"
    val resolved = McpSubprocessSpawner.resolveFromClasspath(
      script = "trails/config/trailmaps/mylib/tools/mylib_dismissIfPresent.ts",
      loadClasspathResource = { path ->
        if (path == "trails/config/trailmaps/mylib/tools/mylib_dismissIfPresent.ts") scriptContent
        else null
      },
      listClasspathToolScripts = { _ -> setOf("mylib_dismissIfPresent.ts") },
      loadSdkResources = { fakeSdkResources },
      extractRoot = extractRoot,
    )
    assertThat(resolved).isNotNull()
    assertThat(resolved!!.readText()).isEqualTo(scriptContent)
    assertThat(resolved.name).isEqualTo("mylib_dismissIfPresent.ts")
  }

  /**
   * The other half of the fix. An extracted tool still imports `@trailblaze/scripting`, so the
   * extraction has to leave the SDK and a `tsconfig.json` aliasing that specifier onto it, or the
   * spawned subprocess dies at import with the script resolved but unloadable.
   *
   * Asserts the mapping is absolute and points at the extracted SDK — a relative mapping would
   * be read against whatever directory the tool happens to sit in.
   */
  @Test fun `resolveFromClasspath writes an SDK-aliasing tsconfig beside the extracted tools`() {
    val extractRoot = File(tmpDir, "extract")
    val resolved = McpSubprocessSpawner.resolveFromClasspath(
      script = "trails/config/trailmaps/mylib/tools/mylib_tap.ts",
      loadClasspathResource = { path ->
        if (path == "trails/config/trailmaps/mylib/tools/mylib_tap.ts") "// tool\n" else null
      },
      listClasspathToolScripts = { _ -> setOf("mylib_tap.ts") },
      loadSdkResources = { fakeSdkResources },
      extractRoot = extractRoot,
    )
    assertThat(resolved).isNotNull()

    val sdkDist = File(extractRoot, "sdk/dist")
    assertThat(File(sdkDist, "index.js").readText()).isEqualTo("export const trailblaze = {};\n")

    // Bun walks up from the imported tool file, so the config belongs at the tools root.
    val tsconfig = File(resolved!!.parentFile, "tsconfig.json")
    assertThat(tsconfig.isFile).isEqualTo(true)
    val body = tsconfig.readText()
    assertThat(body).contains("\"@trailblaze/scripting\"")
    assertThat(body).contains(File(sdkDist, "index").absolutePath)
    assertThat(body).contains(File(sdkDist, "*").absolutePath)
  }

  /**
   * A tool nested under `tools/<subdir>/` must be covered by the same single config, because bun
   * resolves the specifier by walking up from the importing file rather than from the tools root.
   */
  @Test fun `a nested extracted tool is covered by the tools-root tsconfig`() {
    val extractRoot = File(tmpDir, "extract")
    val resolved = McpSubprocessSpawner.resolveFromClasspath(
      script = "trails/config/trailmaps/mylib/tools/host/mylib_deep.ts",
      loadClasspathResource = { path ->
        if (path == "trails/config/trailmaps/mylib/tools/host/mylib_deep.ts") "// deep\n" else null
      },
      listClasspathToolScripts = { _ -> setOf("host/mylib_deep.ts") },
      loadSdkResources = { fakeSdkResources },
      extractRoot = extractRoot,
    )
    assertThat(resolved).isNotNull()
    // One directory above the nested tool is the tools root, which is where the config lands.
    assertThat(File(resolved!!.parentFile.parentFile, "tsconfig.json").isFile).isEqualTo(true)
  }

  /**
   * A build that packages the tool sources but not the SDK cannot produce a loadable tool. Naming
   * that here beats letting the subprocess fail later with a module-not-found whose cause is a
   * packaging bug two modules away.
   */
  @Test fun `resolveFromClasspath fails loudly when the build ships no SDK`() {
    assertFailure {
      McpSubprocessSpawner.resolveFromClasspath(
        script = "trails/config/trailmaps/mylib/tools/mylib_tap.ts",
        loadClasspathResource = { path ->
          if (path == "trails/config/trailmaps/mylib/tools/mylib_tap.ts") "// tool\n" else null
        },
        listClasspathToolScripts = { _ -> setOf("mylib_tap.ts") },
        loadSdkResources = { emptyMap() },
        extractRoot = File(tmpDir, "extract"),
      )
    }.messageContains("ships no scripted-tool SDK")
  }

  /**
   * The SDK extraction must not leave `.tmp` staging files behind: the caller swallows
   * [java.io.IOException], so anything stranded here accumulates unnoticed next to the tools.
   */
  @Test fun `extraction leaves no staging files behind`() {
    val extractRoot = File(tmpDir, "extract")
    McpSubprocessSpawner.resolveFromClasspath(
      script = "trails/config/trailmaps/mylib/tools/mylib_tap.ts",
      loadClasspathResource = { path ->
        if (path == "trails/config/trailmaps/mylib/tools/mylib_tap.ts") "// tool\n" else null
      },
      listClasspathToolScripts = { _ -> setOf("mylib_tap.ts") },
      loadSdkResources = { fakeSdkResources },
      extractRoot = extractRoot,
    )
    val strays = extractRoot.walkTopDown().filter { it.name.endsWith(".tmp") }.toList()
    assertThat(strays).isEqualTo(emptyList())
  }

  @Test fun `resolveFromClasspath returns null when path has no trailmaps anchor`() {
    val extractRoot = File(tmpDir, "extract")
    val resolved = McpSubprocessSpawner.resolveFromClasspath(
      script = "some/other/path/tool.ts",
      loadClasspathResource = { null },
      listClasspathToolScripts = { emptySet() },
      loadSdkResources = { fakeSdkResources },
      extractRoot = extractRoot,
    )
    assertThat(resolved).isNull()
  }

  @Test fun `resolveFromClasspath returns null when resource not on classpath`() {
    val extractRoot = File(tmpDir, "extract")
    val resolved = McpSubprocessSpawner.resolveFromClasspath(
      script = "trails/config/trailmaps/mylib/tools/missing.ts",
      loadClasspathResource = { null },
      listClasspathToolScripts = { emptySet() },
      loadSdkResources = { fakeSdkResources },
      extractRoot = extractRoot,
    )
    assertThat(resolved).isNull()
  }

  @Test fun `resolveFromClasspath handles nested subdirectory scripts`() {
    val extractRoot = File(tmpDir, "extract")
    val scriptContent = "// nested tool\n"
    val resolved = McpSubprocessSpawner.resolveFromClasspath(
      script = "trails/config/trailmaps/mylib/tools/host/mylib_helper.ts",
      loadClasspathResource = { path ->
        when (path) {
          "trails/config/trailmaps/mylib/tools/host/mylib_helper.ts" -> scriptContent
          "trails/config/trailmaps/mylib/tools/shared.ts" -> "// shared\n"
          else -> null
        }
      },
      listClasspathToolScripts = { _ -> setOf("host/mylib_helper.ts", "shared.ts") },
      loadSdkResources = { fakeSdkResources },
      extractRoot = extractRoot,
    )
    assertThat(resolved).isNotNull()
    assertThat(resolved!!.readText()).isEqualTo(scriptContent)
    // Sibling was also extracted so relative imports resolve
    assertThat(File(resolved.parentFile.parentFile, "shared.ts").isFile).isEqualTo(true)
  }

  @Test fun `resolveScriptPath errors when neither filesystem nor classpath has the file`() {
    // Verifies the error path: a trailmap-shaped path that doesn't exist on disk AND isn't
    // on the classpath still produces the descriptive "mcp_servers script not found" error.
    // The positive classpath-fallback path is covered by the resolveFromClasspath tests above
    // (resolveScriptPath delegates to it internally).
    assertFailure {
      McpSubprocessSpawner.resolveScriptPath(
        "trails/config/trailmaps/nonexistent/tools/missing.ts",
        anchor = tmpDir,
      )
    }.messageContains("mcp_servers script not found")
  }

  /**
   * Two things had to be true for this to test the `..` guard rather than something incidental.
   *
   * The path shape has to REACH the guard: `trailmaps/../../../etc/passwd/tools/…` exits earlier
   * at the `segments[1] != "tools"` shape check, so it stays green with the guard deleted. Here
   * the segments are `["..", "tools", "evil.ts"]`, which clears the shape check.
   *
   * And the escape has to be able to SUCCEED without the guard. With the extract root absent,
   * `mkdirs` on a path containing `..` fails (the OS can't resolve `..` through a missing
   * directory), the caller's `IOException` catch returns null, and the assertion below passes
   * with the guard gone — green for a reason that has nothing to do with traversal. Creating the
   * root first removes that accident.
   *
   * Asserting on the escaped file, not just the return value, pins the property that actually
   * matters. Verified by deleting the guard and watching both assertions fail.
   */
  @Test fun `resolveFromClasspath rejects path traversal attempts`() {
    val extractRoot = File(tmpDir, "extract").apply { mkdirs() }
    val escapeTarget = File(tmpDir, "tools/evil.ts")

    val resolved = McpSubprocessSpawner.resolveFromClasspath(
      script = "trails/config/trailmaps/../tools/evil.ts",
      loadClasspathResource = { "// evil" },
      listClasspathToolScripts = { emptySet() },
      loadSdkResources = { fakeSdkResources },
      extractRoot = extractRoot,
    )

    assertThat(resolved).isNull()
    assertThat(escapeTarget.exists()).isEqualTo(false)
  }

  @Test fun `resolveFromClasspath overwrites on re-extraction for JAR upgrade safety`() {
    val extractRoot = File(tmpDir, "extract")
    // First call: extracts v1 content
    McpSubprocessSpawner.resolveFromClasspath(
      script = "trails/config/trailmaps/mylib/tools/mylib_tap.ts",
      loadClasspathResource = { path ->
        if (path == "trails/config/trailmaps/mylib/tools/mylib_tap.ts") "// v1\n"
        else null
      },
      listClasspathToolScripts = { _ -> setOf("mylib_tap.ts") },
      loadSdkResources = { fakeSdkResources },
      extractRoot = extractRoot,
    )
    // Second call with updated content (simulates a newer JAR): overwrites
    val second = McpSubprocessSpawner.resolveFromClasspath(
      script = "trails/config/trailmaps/mylib/tools/mylib_tap.ts",
      loadClasspathResource = { path ->
        if (path == "trails/config/trailmaps/mylib/tools/mylib_tap.ts") "// v2\n"
        else null
      },
      listClasspathToolScripts = { _ -> setOf("mylib_tap.ts") },
      loadSdkResources = { fakeSdkResources },
      extractRoot = extractRoot,
    )
    assertThat(second).isNotNull()
    assertThat(second!!.readText()).isEqualTo("// v2\n")
  }

  /**
   * The acceptance test for #6391, and the one that would have caught the first attempt at it.
   *
   * Extracts a tool that imports `@trailblaze/scripting` — the shape every bundled trailmap tool
   * has — against the REAL SDK this build ships, then spawns it under bun and completes an MCP
   * handshake. The handshake only succeeds if the subprocess imported the tool, so a green run
   * means the specifier resolved from the extract directory. Extracting the `.ts` alone leaves
   * bun with a bare specifier and nothing to resolve it against: delete the tsconfig write in
   * `resolveFromClasspath` and this fails with `Cannot find module '@trailblaze/scripting'`,
   * which is exactly the state a file-only fallback ships.
   *
   * Deliberately hands `synthesize` the already-extracted absolute path, so the test owns its
   * extract directory instead of writing into the shared temp root the production default uses.
   */
  @Test fun `an extracted classpath tool resolves its SDK import under bun`() {
    runBlocking {
      assumeTrue("bun must be on PATH to spawn a scripted tool", runtimeAvailable())

      val toolSource =
        """
        import { trailblaze } from "@trailblaze/scripting";

        interface HelloInput { who: string }

        /** Greets someone, proving a classpath-extracted tool can load its SDK import. */
        export const mylib_hello = trailblaze.tool<HelloInput>(
          { supportedPlatforms: ["android", "ios"] },
          async (input) => `hello ${'$'}{input.who}`,
        );
        """.trimIndent() + "\n"
      val classpathPath = "trails/config/trailmaps/mylib/tools/mylib_hello.ts"

      // Real SDK resources (default `loadSdkResources`), test-owned extract root.
      val extracted = McpSubprocessSpawner.resolveFromClasspath(
        script = classpathPath,
        loadClasspathResource = { path -> if (path == classpathPath) toolSource else null },
        listClasspathToolScripts = { _ -> setOf("mylib_hello.ts") },
        extractRoot = File(tmpDir, "extract"),
      )
      assertThat(extracted).isNotNull()

      val generated = InlineScriptToolServerSynthesizer.synthesize(
        tools = listOf(
          InlineScriptToolConfig(
            script = extracted!!.absolutePath,
            name = "mylib_hello",
            description = "Greets a user from a classpath-extracted tool.",
          ),
        ),
        outputDir = File(tmpDir, "generated").apply { mkdirs() },
      )

      val spawned = McpSubprocessSpawner.spawn(config = generated.single(), context = context)
      val stderrLog = File(tmpDir, "classpath-tool.stderr.log")
      val session = try {
        McpSubprocessSession.connect(
          spawnedProcess = spawned,
          stderrCapture = StderrCapture(stderrLog),
        )
      } catch (t: Throwable) {
        // Surface the subprocess's own diagnostic — a module-resolution failure is reported
        // there, and without it this reads as an opaque handshake timeout.
        throw AssertionError(
          "Extracted classpath tool failed to load under bun. stderr:\n${stderrLog.takeIf { it.isFile }?.readText()}",
          t,
        )
      }
      try {
        val advertised = session.client.listTools(ListToolsRequest()).tools.map { it.name }
        assertThat(advertised).contains("mylib_hello")
      } finally {
        session.shutdown()
        if (!spawned.process.waitFor(10, TimeUnit.SECONDS)) {
          spawned.process.destroyForcibly()
          spawned.process.waitFor(5, TimeUnit.SECONDS)
        }
      }
    }
  }

  private fun runtimeAvailable(): Boolean = try {
    BunRuntimeDetector.cached
    true
  } catch (_: NoBunRuntimeException) {
    false
  }

  @Test fun `resolveFromClasspath extracts all sibling scripts for import resolution`() {
    val extractRoot = File(tmpDir, "extract")
    val resolved = McpSubprocessSpawner.resolveFromClasspath(
      script = "trails/config/trailmaps/factory/tools/factory_create.ts",
      loadClasspathResource = { path ->
        when (path) {
          "trails/config/trailmaps/factory/tools/factory_create.ts" -> "// create\n"
          "trails/config/trailmaps/factory/tools/factory_delete.ts" -> "// delete\n"
          "trails/config/trailmaps/factory/tools/shared/api.ts" -> "// api\n"
          else -> null
        }
      },
      listClasspathToolScripts = { _ ->
        setOf("factory_create.ts", "factory_delete.ts", "shared/api.ts")
      },
      loadSdkResources = { fakeSdkResources },
      extractRoot = extractRoot,
    )
    assertThat(resolved).isNotNull()
    // All siblings extracted
    val toolsDir = resolved!!.parentFile
    assertThat(File(toolsDir, "factory_delete.ts").isFile).isEqualTo(true)
    assertThat(File(toolsDir, "shared/api.ts").isFile).isEqualTo(true)
  }
}
