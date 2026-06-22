package xyz.block.trailblaze.scripting

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Rule-branch tests for [ScriptedToolImportAnalyzer]. Each case exercises one of the
 * detection rules currently implemented:
 *
 *  - pure-ES import → NOT flipped
 *  - `node:*` builtin import → flipped, chain breadcrumb populated
 *  - `node:process` carve-out → NOT flipped (the real bundler externalises it too)
 *  - known Node-only npm package import (axios) → flipped, chain breadcrumb populated
 *
 * Dynamic `require()` with non-statically-analysable arguments was called out by issue
 * #3190 as a third rule, but the analyzer doesn't implement it in this PR — see the
 * `Deferred — dynamic require()` note in the kdoc on [ScriptedToolImportAnalyzer] for
 * the rationale (esbuild's `--platform=neutral` doesn't emit a clean warning, and authors
 * still get a clear `Dynamic require of "X" is not supported` runtime error via the
 * polyfill esbuild inlines).
 *
 * The "explicit `requiresHost: true` is respected" branch is covered at a different
 * layer — that short-circuit happens in [xyz.block.trailblaze.host.TrailblazeHostYamlRunner]
 * BEFORE the analyzer is called; the analyzer itself has no opinion on the author flag.
 *
 * Every test calls [assumeEsbuildPresent] first — without a real esbuild on disk the
 * analyzer can't produce a verdict (and returns `requiresHost = false` so the real bundle
 * pass can surface the missing binary). On a fresh checkout that ran `bun install` once,
 * every test runs; on hosts without esbuild they assume-skip cleanly. Mirrors the same
 * skip pattern [DaemonScriptedToolBundlerTest] uses.
 */
class ScriptedToolImportAnalyzerTest {

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
  fun `pure JS tool with no imports does not flip`() = runBlocking {
    assumeEsbuildPresent()
    val src = writeTs(
      name = "noImports.ts",
      body = """
        |export function run(): string {
        |  return "no imports";
        |}
      """.trimMargin(),
    )
    val verdict = analyzer.analyze(src)
    assertFalse(verdict.requiresHost, "expected NOT host-only; got reason=${verdict.reason}")
    assertNull(verdict.reason)
  }

  @Test
  fun `direct node colon builtin import flips and produces chain breadcrumb`() = runBlocking {
    assumeEsbuildPresent()
    val src = writeTs(
      name = "usesNodeFs.ts",
      // `import "node:fs"` for the side-effect — esbuild records the import in the metafile
      // even with no named binding, and the external marker is what the analyzer keys on.
      body = """
        |import "node:fs";
        |export function run(): string {
        |  return "uses node:fs";
        |}
      """.trimMargin(),
    )
    val verdict = analyzer.analyze(src)
    assertTrue(verdict.requiresHost, "expected host-only; reason=${verdict.reason}")
    val reason = assertNotNull(verdict.reason)
    assertTrue(
      reason.startsWith("usesNodeFs.ts"),
      "expected chain to start with the user's script filename; got: $reason",
    )
    assertTrue(
      reason.endsWith("node:fs"),
      "expected chain to end with the offending external; got: $reason",
    )
  }

  @Test
  fun `node colon process is NOT flipped because the real bundler externalises it too`() = runBlocking {
    assumeEsbuildPresent()
    // The real bundler ([DaemonScriptedToolBundler.runEsbuild]) emits `--external:node:process`
    // unconditionally — that path-through is what lets a scripted tool read `process.env`
    // when running under the host's QuickJS via the host binding. Flagging this import would
    // misclassify every author who touches env vars; pin the carve-out here so a future
    // tightening of the analyzer doesn't silently drop the exemption.
    val src = writeTs(
      name = "usesNodeProcess.ts",
      body = """
        |import "node:process";
        |export function run(): string {
        |  return "process";
        |}
      """.trimMargin(),
    )
    val verdict = analyzer.analyze(src)
    assertFalse(
      verdict.requiresHost,
      "expected NOT host-only — node:process is the deliberately externalised carve-out; " +
        "reason=${verdict.reason}",
    )
  }

  @Test
  fun `known Node-only npm import flips with package name in chain`() = runBlocking {
    assumeEsbuildPresent()
    // Importing `axios` works for the analyzer even when `node_modules/axios` is absent on
    // disk because the analyzer marks the package external up front (see
    // [KnownNodeOnlyPackages]). The metafile records it as an unresolved external, which
    // is exactly what the chain classifier matches against. This keeps the rule branch
    // testable without forcing a `bun install axios` precondition.
    val src = writeTs(
      name = "usesAxios.ts",
      body = """
        |import axios from "axios";
        |export async function run(): Promise<string> {
        |  return axios.toString();
        |}
      """.trimMargin(),
    )
    val verdict = analyzer.analyze(src)
    assertTrue(verdict.requiresHost, "expected host-only; reason=${verdict.reason}")
    val reason = assertNotNull(verdict.reason)
    assertTrue(reason.contains("axios"), "expected chain to name axios; got: $reason")
    assertEquals(
      "usesAxios.ts → axios",
      reason,
      "expected the canonical short chain for a direct host-only import",
    )
  }

  @Test
  fun `subpath import of a known Node-only package flips and reports the package root in the chain`() = runBlocking {
    assumeEsbuildPresent()
    // Pins the fix for the Codex P1 finding on PR #3199: esbuild's `--external:axios` is a
    // package-prefix match, so an `import "axios/lib/foo"` shows up in the metafile as
    // `external: true` with `path: "axios/lib/foo"`. The previous exact-set-membership
    // check missed these subpath imports, letting them fall through to the real bundle
    // pass and re-introducing the sibling-abort behaviour this change exists to prevent.
    // The chain string must still report the package root (`axios`), not the deep subpath
    // — authors care about what they reached for, not how esbuild resolved it.
    val src = writeTs(
      name = "usesAxiosSubpath.ts",
      body = """
        |import "axios/lib/utils";
        |export function run(): string {
        |  return "subpath";
        |}
      """.trimMargin(),
    )
    val verdict = analyzer.analyze(src)
    assertTrue(verdict.requiresHost, "expected host-only; reason=${verdict.reason}")
    val reason = assertNotNull(verdict.reason)
    assertEquals(
      "usesAxiosSubpath.ts → axios",
      reason,
      "expected chain to collapse the subpath import back to the package root",
    )
  }

  @Test
  fun `analyze returns false when esbuild binary is absent`() = runBlocking {
    // Deliberate missing-binary case — the runner falls back to a degraded session-start
    // path that surfaces the missing binary; the analyzer should not throw, so the runner
    // can still proceed with its own error message.
    val missingBinaryAnalyzer = ScriptedToolImportAnalyzer(
      esbuildBinary = File(tempFolder.root, "definitely-missing-esbuild"),
    )
    val src = writeTs(
      name = "anyTool.ts",
      body = "export function run(): void {}",
    )
    val verdict = missingBinaryAnalyzer.analyze(src)
    assertFalse(verdict.requiresHost)
    assertNull(verdict.reason)
  }

  @Test
  fun `analyze returns false when script file does not exist`() = runBlocking {
    val missing = File(tempFolder.root, "no-such-file.ts")
    val verdict = analyzer.analyze(missing)
    assertFalse(verdict.requiresHost)
    assertNull(verdict.reason)
  }

  // --- helpers ---

  private fun writeTs(name: String, body: String): File {
    val file = tempFolder.newFile(name)
    file.writeText(body)
    return file
  }

  /**
   * Same walk-up resolution as [DaemonScriptedToolBundlerTest.resolveEsbuildBinary]; kept
   * local rather than promoted to a shared test util so the two suites stay self-contained
   * (each suite's setup explains what it skips and why).
   */
  private fun resolveEsbuildBinary(): File? {
    System.getenv("TRAILBLAZE_TEST_ESBUILD_BINARY")?.takeIf { it.isNotBlank() }?.let { path ->
      val explicit = File(path)
      if (explicit.isFile) return explicit
    }
    val marker = "sdks/typescript/package.json"
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
