package xyz.block.trailblaze.scripting

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.config.InlineScriptToolConfig
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the analyzer splice that [xyz.block.trailblaze.host.TrailblazeHostYamlRunner]
 * delegates to — the load-bearing behavior advertised by PR #3199:
 *
 *  1. A `.ts` tool whose closure imports a `node:*` builtin is dropped from the bundling
 *     loop; its on-device-viable sibling still ends up registered.
 *  2. A tool whose author explicitly set `requiresHost: true` short-circuits the analyzer
 *     and stays in the bundling loop — the analyzer must never second-guess the explicit
 *     declaration.
 *  3. When every tool's closure is clean, the partition is a no-op.
 *
 * The previous behavior (single-step bundling loop with no analyzer) would have failed
 * session start as soon as the first Node-touching tool hit esbuild's "Could not resolve"
 * error path, silently taking out every sibling. A regression that reorders the
 * `requiresHost == true` short-circuit, removes the per-tool isolation, or accidentally
 * re-introduces the failure-tanks-the-loop pattern is what this test exists to catch.
 */
class ScriptedToolHostOnlyPartitionerTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private lateinit var esbuild: File
  private lateinit var analyzer: ScriptedToolImportAnalyzer

  @Before
  fun setup() {
    esbuild = resolveEsbuildBinary() ?: File(tempFolder.root, "missing-esbuild")
    analyzer = ScriptedToolImportAnalyzer(esbuildBinary = esbuild)
  }

  private fun assumeEsbuildPresent() {
    assumeTrue(
      "esbuild binary not found under sdks/typescript/node_modules/.bin/esbuild — " +
        "run `bun install` in sdks/typescript to enable this test.",
      esbuild.isFile,
    )
  }

  @Test
  fun `host-only sibling is dropped, on-device-viable sibling stays`() = runBlocking {
    assumeEsbuildPresent()
    // The pre-#3190 behavior was: bundler.bundleOne("usesNodeFs.ts") fails on
    // "Could not resolve node:fs" → session-start aborts → `cleanSibling` is also lost.
    // The partition is what restores per-tool isolation.
    val cleanSibling = writeTool(
      toolName = "cleanSibling",
      scriptName = "cleanSibling.ts",
      scriptBody = "export function cleanSibling(): string { return 'clean'; }",
    )
    val nodeFsTool = writeTool(
      toolName = "usesNodeFs",
      scriptName = "usesNodeFs.ts",
      scriptBody = """
        |import "node:fs";
        |export function usesNodeFs(): string { return 'fs'; }
      """.trimMargin(),
    )

    val captured = mutableListOf<String>()
    val partition = partitionByImportClosure(
      tools = listOf(cleanSibling, nodeFsTool),
      analyzer = analyzer,
      log = captured::add,
    )

    assertEquals(listOf("cleanSibling"), partition.toBundle.map { it.name })
    assertEquals(listOf("usesNodeFs"), partition.skippedNames)
    // Breadcrumb per skip + rollup at the end. Pin both — a future refactor that drops
    // the directed message would degrade author UX silently.
    assertTrue(captured.any { it.contains("Tool 'usesNodeFs'") && it.contains("node:fs") })
    assertTrue(captured.any { it.contains("auto-flipped to host-only") && it.contains("usesNodeFs") })
  }

  @Test
  fun `explicit requiresHost true short-circuits the analyzer and stays in the bundling loop`() = runBlocking {
    assumeEsbuildPresent()
    // A tool that imports axios — which would trip the analyzer — but whose author already
    // declared `requiresHost: true`. The author flag is the explicit signal that the tool
    // is host-only by design (per the kdoc on `partitionByImportClosure`), so the analyzer
    // must NOT be consulted; the tool stays in `toBundle` and goes through the bundler
    // unchanged. Existing `requiresHost`-as-LLM-visibility semantics continue to apply at
    // on-device registration time, where the launcher filters the tool out.
    val authorFlaggedTool = writeTool(
      toolName = "authorFlagged",
      scriptName = "authorFlagged.ts",
      scriptBody = """
        |import axios from "axios";
        |export function authorFlagged(): string { return axios.toString(); }
      """.trimMargin(),
      requiresHost = true,
    )

    val captured = mutableListOf<String>()
    val partition = partitionByImportClosure(
      tools = listOf(authorFlaggedTool),
      analyzer = analyzer,
      log = captured::add,
    )

    assertEquals(listOf("authorFlagged"), partition.toBundle.map { it.name })
    assertEquals(emptyList(), partition.skippedNames)
    // No breadcrumb because the analyzer never ran — the short-circuit fires before the
    // verdict-logging branch.
    assertTrue(
      captured.none { it.contains("Tool 'authorFlagged'") },
      "expected no analyzer-emitted log lines for author-flagged tools; got: $captured",
    )
  }

  @Test
  fun `all-clean closure leaves every tool in the bundling loop`() = runBlocking {
    assumeEsbuildPresent()
    val toolA = writeTool(
      toolName = "toolA",
      scriptName = "toolA.ts",
      scriptBody = "export function toolA(): string { return 'a'; }",
    )
    val toolB = writeTool(
      toolName = "toolB",
      scriptName = "toolB.ts",
      scriptBody = "export function toolB(): string { return 'b'; }",
    )

    val captured = mutableListOf<String>()
    val partition = partitionByImportClosure(
      tools = listOf(toolA, toolB),
      analyzer = analyzer,
      log = captured::add,
    )

    assertEquals(listOf("toolA", "toolB"), partition.toBundle.map { it.name })
    assertEquals(emptyList(), partition.skippedNames)
    assertTrue(
      captured.none { it.contains("auto-flipped to host-only") },
      "expected no rollup log line when nothing was flipped; got: $captured",
    )
  }

  // --- helpers ---

  private fun writeTool(
    toolName: String,
    scriptName: String,
    scriptBody: String,
    requiresHost: Boolean = false,
  ): InlineScriptToolConfig {
    val script = tempFolder.newFile(scriptName)
    script.writeText(scriptBody)
    return InlineScriptToolConfig(
      script = script.absolutePath,
      name = toolName,
      description = "test tool",
      requiresHost = requiresHost,
      runtime = null,
      meta = if (requiresHost) {
        buildJsonObject {
          put("trailblaze/requiresHost", kotlinx.serialization.json.JsonPrimitive(true))
        }
      } else {
        null
      },
      inputSchema = buildJsonObject { put("type", kotlinx.serialization.json.JsonPrimitive("object")) },
    )
  }

  // Same walk-up resolution as the analyzer/bundler tests — kept local so each test class
  // stays self-contained about what it skips and why.
  private fun resolveEsbuildBinary(): File? {
    System.getenv("TRAILBLAZE_TEST_ESBUILD_BINARY")?.takeIf { it.isNotBlank() }?.let { path ->
      val explicit = File(path)
      if (explicit.isFile) return explicit
    }
    val marker = "sdks/typescript-tools/package.json"
    val esbuildRel = "sdks/typescript/node_modules/.bin/esbuild"
    var current: File? = File(System.getProperty("user.dir"))
    while (current != null) {
      if (File(current, marker).isFile) {
        val esb = File(current, esbuildRel)
        return esb.takeIf { it.isFile }
      }
      val children = try {
        current.listFiles()
      } catch (_: SecurityException) {
        null
      }
      if (children != null) {
        for (child in children) {
          if (child.isDirectory && File(child, marker).isFile) {
            val esb = File(child, esbuildRel)
            return esb.takeIf { it.isFile }
          }
        }
      }
      current = current.parentFile
    }
    return null
  }
}
