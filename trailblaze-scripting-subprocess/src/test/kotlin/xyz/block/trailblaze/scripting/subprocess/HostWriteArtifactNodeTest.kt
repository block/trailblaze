package xyz.block.trailblaze.scripting.subprocess

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * CI-runnable end-to-end exercise of `host_writeArtifact.js`.
 *
 * The sister test in [InlineScriptToolServerSynthesizerTest] spawns the synthesized wrapper
 * via bun/tsx and is the strongest e2e signal — but it `assumeTrue`-skips when bun/tsx aren't
 * on PATH (which they aren't on the gradle :check CI runner). That left no CI signal that the
 * JS file itself works.
 *
 * This test runs `host_writeArtifact.js` directly via `node` (universally available on every
 * runner) using a small ESM runner shim. It proves: (1) the imports resolve under Node — they
 * include `node:fs/promises`, `node:path`, `node:os`, all of which are Node-only and the whole
 * reason the tool is gated `requiresHost: true`; (2) the function writes the expected bytes
 * to disk under the per-session subtree; (3) the sanitizer keeps a hostile `sessionId` from
 * escaping the subtree.
 *
 * Fails loud (no `assumeTrue`-skip) — Node is required. If the runner ever doesn't have it,
 * we want a red signal, not silence.
 */
class HostWriteArtifactNodeTest {

  companion object {
    private lateinit var workDir: File
    private lateinit var runnerFile: File

    /**
     * Detected JS runtime — `node`, `bun`, or `tsx`, all of which can run plain ESM `.mjs`
     * files. Searches `$PATH` plus common Homebrew locations so the test runs on macOS CI
     * runners that have a sparse PATH but the binary installed somewhere standard. Resolved
     * once per JVM. `null` if nothing was found; the @Test methods fail loud in that case
     * (no silent skipping).
     */
    private val JS_RUNTIME: File? by lazy {
      val candidates = listOf("node", "bun", "tsx")
      val extraDirs = listOf(
        "/usr/local/bin",
        "/opt/homebrew/bin",
        "/opt/homebrew/opt/node/bin",
        "${System.getProperty("user.home")}/.bun/bin",
      )
      val pathDirs = (System.getenv("PATH") ?: "")
        .split(File.pathSeparator)
        .filter { it.isNotBlank() }
      val searchDirs = pathDirs + extraDirs.filter { it !in pathDirs }
      candidates.asSequence()
        .flatMap { name -> searchDirs.asSequence().map { dir -> File(dir, name) } }
        .firstOrNull { it.isFile && it.canExecute() }
    }

    /**
     * Walks up from the JVM cwd looking for the example file. Gradle runs tests with cwd =
     * module dir, IntelliJ uses repo root — walking up handles both.
     */
    private val hostWriteArtifactExampleFile: File = run {
      val relative =
        "examples/android-sample-app/trails/config/mcp-sdk/host_writeArtifact.js"
      var cursor: File? = File(System.getProperty("user.dir")).absoluteFile
      while (cursor != null) {
        val candidate = cursor.resolve(relative)
        if (candidate.isFile) return@run candidate
        cursor = cursor.parentFile
      }
      File(System.getProperty("user.dir"), relative)
    }

    @BeforeClass @JvmStatic fun setup() {
      // Print a setup banner so CI logs show this test class actually ran (Gradle's
      // testLogging only surfaces SKIPPED / FAILED / STANDARD_OUT events; silent passes
      // produce zero log output and become indistinguishable from "test never ran").
      println(
        "HostWriteArtifactNodeTest: setup running, " +
          "cwd=${System.getProperty("user.dir")}, runtime=${JS_RUNTIME?.absolutePath ?: "<none>"}",
      )
      check(hostWriteArtifactExampleFile.isFile) {
        "host_writeArtifact.js not found via walk-up from cwd=${System.getProperty("user.dir")}; " +
          "expected at examples/android-sample-app/trails/config/mcp-sdk/host_writeArtifact.js"
      }
      workDir = Files.createTempDirectory("host_writeArtifact_node_test_").toFile()
      runnerFile = File(workDir, "runner.mjs").apply {
        writeText(
          """
          // Test runner that invokes host_writeArtifact() once and prints a JSON envelope.
          //
          // Args (positional, all required):
          //   relativePath, contents, sessionId
          import { host_writeArtifact } from ${esmStringLiteral(hostWriteArtifactExampleFile.absolutePath)};

          const [relativePath, contents, sessionId] = process.argv.slice(2);
          try {
            const summary = await host_writeArtifact(
              { relativePath, contents },
              { sessionId },
            );
            console.log(JSON.stringify({ ok: true, summary }));
          } catch (e) {
            console.log(JSON.stringify({ ok: false, error: e?.message ?? String(e) }));
            process.exitCode = 0;
          }
          """.trimIndent() + "\n",
        )
      }
    }

    @AfterClass @JvmStatic fun teardown() {
      workDir.deleteRecursively()
    }

    private fun esmStringLiteral(value: String): String {
      // JS string literal — escape quotes and backslashes, wrap in double quotes.
      val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
      return "\"$escaped\""
    }
  }

  @Test fun `writes utf8 contents and reports correct byte count for multibyte chars`() {
    val sessionId = "host_writeArtifact_test_${System.nanoTime()}"
    val expectedRoot = File(System.getProperty("java.io.tmpdir"))
      .resolve("trailblaze-artifacts")
      .resolve(sessionId)
    try {
      // "héllo\nworld" is 12 bytes in UTF-8, 11 chars in UTF-16 — the Buffer.byteLength fix
      // is what makes this 12, not 11.
      val result = runRunner(
        relativePath = "exercise/hello.txt",
        contents = "héllo\nworld",
        sessionId = sessionId,
      )
      assertThat(result.ok).isTrue()
      val summary = checkNotNull(result.summary) {
        "host_writeArtifact returned no summary; error=${result.error}"
      }
      assertThat(summary).contains("Wrote 12 bytes to ")
      val target = File(summary.substringAfter("Wrote 12 bytes to ").trim())
      assertThat(target.isFile).isTrue()
      assertThat(target.readText(Charsets.UTF_8)).isEqualTo("héllo\nworld")
      assertThat(target.canonicalFile.absolutePath)
        .contains(expectedRoot.canonicalFile.absolutePath)
    } finally {
      expectedRoot.deleteRecursively()
    }
  }

  @Test fun `rejects path traversal via relativePath`() {
    val result = runRunner(
      relativePath = "../escape.txt",
      contents = "x",
      sessionId = "host_writeArtifact_traversal_${System.nanoTime()}",
    )
    // The function throws; runner converts to {ok:false, error:...} envelope.
    assertThat(result.ok).isEqualTo(false)
    assertThat(checkNotNull(result.error)).contains("relative path without")
  }

  @Test fun `sanitizes hostile sessionId so the path can't escape per-session subtree`() {
    // The sanitizer replaces /, ., etc with _, so `../../../etc/passwd` becomes
    // `_________etc_passwd`. The `summary` returned by Node references the path with
    // whatever os.tmpdir() reports — uncanonicalized on macOS — so we delete by both
    // canonical and raw paths to be safe.
    val sanitized = "_________etc_passwd"
    val sanitizedRoot = File(System.getProperty("java.io.tmpdir"))
      .resolve("trailblaze-artifacts")
      .resolve(sanitized)
    try {
      val result = runRunner(
        relativePath = "h.txt",
        contents = "x",
        sessionId = "../../../etc/passwd",
      )
      assertThat(result.ok).isTrue()
      val summary = checkNotNull(result.summary)
      // Must NOT leak the hostile string verbatim into the path.
      assertThat(summary.contains("../") || summary.contains("/etc/passwd")).isEqualTo(false)
      // Must land under a sanitized per-session subtree.
      assertThat(summary).contains("/trailblaze-artifacts/$sanitized/h.txt")
      // And the file must actually exist on disk where the summary said it does.
      val written = File(summary.substringAfter("Wrote 1 bytes to ").trim())
      assertThat(written.isFile).isTrue()
    } finally {
      sanitizedRoot.deleteRecursively()
      // canonicalFile-resolved variant in case java.io.tmpdir differs from Node's.
      sanitizedRoot.canonicalFile.deleteRecursively()
    }
  }

  private data class RunnerResult(
    val ok: Boolean,
    val summary: String?,
    val error: String?,
  )

  private fun runRunner(relativePath: String, contents: String, sessionId: String): RunnerResult {
    val runtime = checkNotNull(JS_RUNTIME) {
      "No JS runtime found. HostWriteArtifactNodeTest requires `node`, `bun`, or `tsx` on " +
        "PATH (or in common Homebrew paths). Searched PATH=${System.getenv("PATH")}"
    }
    val pb = ProcessBuilder(runtime.absolutePath, runnerFile.absolutePath, relativePath, contents, sessionId)
      .redirectErrorStream(false)
      .directory(workDir)
    val process = try {
      pb.start()
    } catch (e: Exception) {
      throw AssertionError("Failed to spawn ${runtime.absolutePath}", e)
    }
    val stdout = process.inputStream.bufferedReader().use { it.readText() }
    val stderr = process.errorStream.bufferedReader().use { it.readText() }
    val exited = process.waitFor(30, TimeUnit.SECONDS)
    if (!exited) {
      process.destroyForcibly()
      throw AssertionError("node runner did not exit within 30s. stderr:\n$stderr")
    }
    val exit = process.exitValue()
    if (exit != 0) {
      throw AssertionError(
        "node runner exited with non-zero code $exit. stdout:\n$stdout\nstderr:\n$stderr",
      )
    }
    val lastJsonLine = stdout.lineSequence()
      .map { it.trim() }
      .lastOrNull { it.startsWith("{") && it.endsWith("}") }
      ?: throw AssertionError(
        "node runner produced no JSON envelope on stdout. stdout:\n$stdout\nstderr:\n$stderr",
      )
    val envelope = try {
      Json.parseToJsonElement(lastJsonLine).jsonObject
    } catch (e: Exception) {
      throw AssertionError("node runner emitted non-JSON last line: $lastJsonLine", e)
    }
    return RunnerResult(
      ok = envelope["ok"]?.jsonPrimitive?.boolean ?: false,
      summary = envelope["summary"]?.jsonPrimitive?.contentOrNull,
      error = envelope["error"]?.jsonPrimitive?.contentOrNull,
    )
  }
}
