package xyz.block.trailblaze.scripting

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.config.project.PackTargetConfig
import xyz.block.trailblaze.config.project.TrailblazePackManifest
import java.io.File
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [DaemonScriptedToolBundler]. The tests require a real `esbuild` binary
 * because we exercise the actual bundling pipeline end-to-end (the cache hit path
 * has no useful semantics if it never sees a successful initial bundle to cache).
 *
 * The binary is located via [resolveEsbuildBinary], which mirrors the Gradle plugin's
 * `defaultEsbuildBinary` walk-up to the framework root and looks for
 * `sdks/typescript/node_modules/.bin/esbuild`. When the binary is absent (e.g. on a
 * fresh checkout where `bun install` hasn't run), every test in this class is
 * skipped with a JUnit Assume — keeping CI green without making the suite flaky.
 */
class DaemonScriptedToolBundlerTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private lateinit var esbuild: File
  private lateinit var cacheDir: File
  private lateinit var bundler: DaemonScriptedToolBundler

  @Before
  fun setup() {
    val resolved = resolveEsbuildBinary()
    assumeTrue(
      "esbuild binary not found under sdks/typescript/node_modules/.bin/esbuild — " +
        "run `bun install` in sdks/typescript to enable this test.",
      resolved != null && resolved.isFile,
    )
    esbuild = resolved!!
    cacheDir = tempFolder.newFolder("scripted-bundles-cache")
    bundler = DaemonScriptedToolBundler(esbuildBinary = esbuild, cacheDir = cacheDir)
  }

  @Test
  fun `bundleOne produces a non-empty bundle for a tiny inline ts file`() = runBlocking {
    val src = writeTinyTs("hello.ts")
    val out = bundler.bundleOne(src, toolName = "run")
    assertTrue(out.isFile, "expected bundle file to exist at ${out.absolutePath}")
    assertTrue(out.length() > 0L, "expected non-empty bundle, got ${out.length()} bytes")
    assertTrue(out.name.endsWith(".bundle.js"), "expected .bundle.js suffix, got ${out.name}")
    // Cache lives under our injected cacheDir, not the user's home.
    assertEquals(cacheDir.canonicalFile, out.parentFile.canonicalFile)
    // Bundle must self-register on the global registry shape QuickJsToolHost.callTool reads.
    val bundleSource = out.readText()
    assertTrue(
      bundleSource.contains("__trailblazeTools[\"run\"]") ||
        bundleSource.contains("__trailblazeTools.run") ||
        bundleSource.contains("\"run\"]"),
      "expected bundle to register tool 'run' on globalThis.__trailblazeTools; got:\n$bundleSource",
    )
    assertTrue(
      bundleSource.contains("__trailblazeCall"),
      "expected bundle to wire client.callTool to host's __trailblazeCall binding; got:\n$bundleSource",
    )
  }

  @Test
  fun `bundleOne hits cache on second call with unchanged source`() = runBlocking {
    val src = writeTinyTs("cache-me.ts")
    val first = bundler.bundleOne(src, toolName = "run")
    val firstMtime = first.lastModified()
    // Force a sleep is unnecessary — we'll fail the assertion if mtime changes,
    // not if it stays the same. Identity of returned path is the primary signal.
    val second = bundler.bundleOne(src, toolName = "run")
    assertEquals(
      first.canonicalPath,
      second.canonicalPath,
      "expected cache hit to return same bundle path",
    )
    assertEquals(
      firstMtime,
      second.lastModified(),
      "expected cache hit to leave bundle file untouched (mtime should not move)",
    )
  }

  @Test
  fun `bundleAll across two synthetic packs returns map keyed by tool name`() = runBlocking {
    val packADir = tempFolder.newFolder("packA")
    val packBDir = tempFolder.newFolder("packB")

    // Each script must export a function whose name matches the descriptor's `name:` —
    // the bundler's synthesized wrapper imports the named export for registration.
    val toolAScript = writeTinyTs("toolA-source.ts", exportName = "packA_demo_toolA", body = "console.log('A')")
    val toolBScript = writeTinyTs("toolB-source.ts", exportName = "packB_demo_toolB", body = "console.log('B')")

    val toolAYaml = File(packADir, "tools/toolA.yaml").apply {
      parentFile.mkdirs()
      writeText(toolDescriptorYaml(name = "packA_demo_toolA", scriptPath = toolAScript.absolutePath))
    }
    val toolBYaml = File(packBDir, "tools/toolB.yaml").apply {
      parentFile.mkdirs()
      writeText(toolDescriptorYaml(name = "packB_demo_toolB", scriptPath = toolBScript.absolutePath))
    }

    val packA = TrailblazePackManifest(
      id = "packA",
      target = packTarget(displayName = "Pack A", toolPaths = listOf(toolAYaml.relativeTo(packADir).path)),
    )
    val packB = TrailblazePackManifest(
      id = "packB",
      target = packTarget(displayName = "Pack B", toolPaths = listOf(toolBYaml.relativeTo(packBDir).path)),
    )

    val result = bundler.bundleAll(
      packs = listOf(packA, packB),
      packBaseDirs = mapOf(packA to packADir, packB to packBDir),
    )

    assertEquals(
      setOf("packA_demo_toolA", "packB_demo_toolB"),
      result.keys,
      "expected the result map to contain both tools keyed by their declared names",
    )
    result.values.forEach { bundle ->
      assertTrue(bundle.isFile, "expected ${bundle.absolutePath} to exist")
      assertTrue(bundle.length() > 0L, "expected ${bundle.absolutePath} to be non-empty")
    }
  }

  @Test
  fun `bundleAll throws when two packs declare the same scripted tool name`() = runBlocking {
    // Pins the duplicate-name detection added in 520bb7ac5. Pre-fix, a LinkedHashMap.put
    // would silently overwrite the earlier entry; downstream dispatch would route to
    // whichever bundle won the race. We want a loud failure that names both pack ids.
    val packADir = tempFolder.newFolder("dupPackA")
    val packBDir = tempFolder.newFolder("dupPackB")

    val sharedToolName = "duplicate_demo_tool"
    val toolAScript = writeTinyTs("dupA-source.ts", exportName = sharedToolName, body = "console.log('A')")
    val toolBScript = writeTinyTs("dupB-source.ts", exportName = sharedToolName, body = "console.log('B')")

    val toolAYaml = File(packADir, "tools/tool.yaml").apply {
      parentFile.mkdirs()
      writeText(toolDescriptorYaml(name = sharedToolName, scriptPath = toolAScript.absolutePath))
    }
    val toolBYaml = File(packBDir, "tools/tool.yaml").apply {
      parentFile.mkdirs()
      writeText(toolDescriptorYaml(name = sharedToolName, scriptPath = toolBScript.absolutePath))
    }

    val packA = TrailblazePackManifest(
      id = "dupPackA",
      target = packTarget(displayName = "Dup A", toolPaths = listOf(toolAYaml.relativeTo(packADir).path)),
    )
    val packB = TrailblazePackManifest(
      id = "dupPackB",
      target = packTarget(displayName = "Dup B", toolPaths = listOf(toolBYaml.relativeTo(packBDir).path)),
    )

    val thrown = assertFailsWith<IOException> {
      bundler.bundleAll(
        packs = listOf(packA, packB),
        packBaseDirs = mapOf(packA to packADir, packB to packBDir),
      )
    }
    val message = thrown.message.orEmpty()
    assertTrue(
      message.contains("Duplicate scripted-tool name '$sharedToolName'"),
      "expected message to call out the duplicate name; got: $message",
    )
    assertTrue(
      message.contains(toolBScript.absolutePath),
      "expected message to name the conflicting source path; got: $message",
    )
  }

  @Test
  fun `synthesized wrapper uses bracket access so hyphenated tool names produce valid TS`() {
    // Tool names are allowed to contain hyphens (`WorkspaceClientDtsGeneratorTest` has tests
    // for this) and a direct `import { foo-bar as __h } from ...` is invalid TS (hyphens
    // aren't legal identifiers). The wrapper must use namespace import + bracket-access
    // by string key so any string survives — including hyphens, dots, and digit-leading.
    // Validate the wrapper text directly since we can't easily author a `.ts` whose
    // function declaration uses a hyphenated identifier without leaning on esbuild's
    // string-literal-export support.
    val src = writeTinyTs("hyphen-source.ts", exportName = "doSomething")
    val wrapper = bundler.synthesizeWrapper(src, toolName = "clock-android-launchApp")
    assertTrue(
      wrapper.contains("import * as __userModule"),
      "expected namespace import; got:\n$wrapper",
    )
    assertTrue(
      wrapper.contains("__userModule[\"clock-android-launchApp\"]"),
      "expected bracket-access by string key for hyphenated name; got:\n$wrapper",
    )
    assertTrue(
      wrapper.contains("globalThis.__trailblazeTools[\"clock-android-launchApp\"]"),
      "expected registry assignment via bracket access; got:\n$wrapper",
    )
    // Sanity: the wrapper must not contain a direct identifier import like
    // `import { clock-android-launchApp as ... }` — that's the bug the bracket-access
    // refactor protects against.
    assertTrue(
      !wrapper.contains("clock-android-launchApp as"),
      "wrapper must not import the hyphenated name as an identifier; got:\n$wrapper",
    )
  }

  @Test
  fun `synthesized wrapper escapes embedded quotes and backslashes safely`() {
    // Defensive: tool names declared via YAML are restricted in practice, but the wrapper
    // synthesis must produce a parseable JS string regardless of what slips in. This
    // pins the escape behavior so a future widening of tool-name validation doesn't
    // silently produce broken bundles.
    val src = writeTinyTs("escape-source.ts", exportName = "doSomething")
    val wrapper = bundler.synthesizeWrapper(src, toolName = "weird\"name\\with-edge")
    // The string should appear properly escaped (\" and \\) so JS can parse it.
    assertTrue(
      wrapper.contains("\"weird\\\"name\\\\with-edge\""),
      "expected JS-string-literal escaping of quotes and backslashes; got:\n$wrapper",
    )
  }

  @Test
  fun `synthesized wrapper throws when an inner client_callTool returns isError envelope`() {
    // Verifies the shim's error-propagation contract: a `{isError:true, error:...}`
    // envelope from the host binding (e.g. `SessionScopedHostBinding.errorEnvelope`) MUST
    // throw inside the user's `await client.callTool(...)` so authors can catch it via
    // try/catch and so an outer scripted tool can't silently return Success while masking
    // an inner failure (the bug Codex P1 caught on PR #2774).
    val src = writeTinyTs("error-prop-source.ts", exportName = "doSomething")
    val wrapper = bundler.synthesizeWrapper(src, toolName = "doSomething")
    assertTrue(
      wrapper.contains("result.isError === true"),
      "expected isError-envelope detection in shim; got:\n$wrapper",
    )
    assertTrue(
      wrapper.contains("type.indexOf(\"Error\")"),
      "expected TrailblazeToolResult.Error discriminator detection in shim; got:\n$wrapper",
    )
    assertTrue(
      wrapper.contains("throw new Error("),
      "expected the shim to throw on inner failures; got:\n$wrapper",
    )
  }

  @Test
  fun `bundleOne rejects tool names with invalid characters`() = runBlocking {
    val src = writeTinyTs("validation-source.ts", exportName = "doSomething")
    // Spaces, quotes, control chars all produce syntactically broken wrappers or are
    // load-bearing in the registry assignment. Reject at the boundary with an actionable
    // message pointing at the YAML descriptor.
    val invalidNames = listOf("name with spaces", "name\"with\"quotes", "name\nwith\nnewlines", "1starts-with-digit")
    for (bad in invalidNames) {
      val ex = assertFailsWith<IllegalArgumentException>("expected $bad to be rejected") {
        bundler.bundleOne(src, toolName = bad)
      }
      assertTrue(
        ex.message?.contains("Invalid scripted-tool name") == true,
        "expected error message to call out invalid name; got: ${ex.message}",
      )
    }
  }

  @Test
  fun `bundleOneInternal sweeps stale wrapper files left by previous abrupt exits`() = runBlocking {
    val src = writeTinyTs("sweep-source.ts", exportName = "doSomething")
    // Drop a stale wrapper alongside the user's script — what an abrupt JVM exit would
    // have left behind.
    val staleWrapper = File(src.parentFile, "${DaemonScriptedToolBundler.WRAPPER_FILENAME_PREFIX}stale1234.ts")
    staleWrapper.writeText("// pretend this is a leftover from a SIGKILLed daemon")
    assertTrue(staleWrapper.isFile, "stale wrapper sentinel must exist before bundleOne")

    bundler.bundleOne(src, toolName = "doSomething")

    assertTrue(
      !staleWrapper.exists(),
      "expected bundleOneInternal to sweep stale wrapper file; still at ${staleWrapper.absolutePath}",
    )
  }

  @Test
  fun `bundleOne does not leave a partial bundle when esbuild produces empty output`() = runBlocking {
    // Pins the atomic-write tmp-file pattern added in 520bb7ac5. Pre-fix, an esbuild
    // invocation that produced 0 bytes (or got killed mid-write) could leave a file at
    // <sha>.bundle.js that the next session's cache hit would happily serve. The fix
    // writes to a tmp file first and only renames on non-empty success; a 0-byte output
    // throws and the tmp is deleted.
    //
    // We simulate "esbuild succeeds-then-empty" with a stub binary that writes nothing.
    val stubEsbuild = tempFolder.newFile("stub-esbuild.sh").apply {
      writeText(
        """
        |#!/usr/bin/env bash
        |# Parse the --outfile=... argument and touch it (size 0). Mirrors the empty-output
        |# failure mode without needing to crash a real esbuild.
        |for arg in "${'$'}@"; do
        |  case "${'$'}arg" in
        |    --outfile=*) out="${'$'}{arg#--outfile=}"; : > "${'$'}out" ;;
        |  esac
        |done
        |exit 0
        |""".trimMargin(),
      )
      setExecutable(true)
    }
    val stubCacheDir = tempFolder.newFolder("stub-cache")
    val stubBundler = DaemonScriptedToolBundler(esbuildBinary = stubEsbuild, cacheDir = stubCacheDir)
    val src = writeTinyTs("empty-output-source.ts")

    assertFailsWith<IOException> { stubBundler.bundleOne(src, toolName = "run") }

    // The advertised <sha>.bundle.js must NOT exist (rename only happens on non-empty).
    val finalBundles = stubCacheDir.listFiles { f -> f.name.endsWith(".bundle.js") }.orEmpty()
    assertTrue(
      finalBundles.isEmpty(),
      "expected no <sha>.bundle.js after empty-output failure; got: ${finalBundles.map { it.name }}",
    )
    // No leftover *.tmp files either — bundleOneInternal's catch deletes them.
    val leftoverTmps = stubCacheDir.listFiles { f -> f.name.contains(".tmp") }.orEmpty()
    assertTrue(
      leftoverTmps.isEmpty(),
      "expected no leftover .tmp files in cache dir; got: ${leftoverTmps.map { it.name }}",
    )
    // A subsequent retry against the same source must NOT see a stale cache hit — the
    // failure path must leave the cache in a state where the next call re-runs esbuild.
    assertFalse(
      stubCacheDir.listFiles { f -> f.name.endsWith(".bundle.js") }.orEmpty().isNotEmpty(),
      "no cache file should remain — a follow-up bundleOne call should re-run esbuild, not hit a stale cache",
    )
  }

  // --- helpers ---

  private fun writeTinyTs(
    name: String,
    exportName: String = "run",
    body: String = "console.log('hi')",
  ): File {
    val file = tempFolder.newFile(name)
    file.writeText(
      """
      |const value: number = 42;
      |export function $exportName(): void {
      |  $body;
      |}
      |""".trimMargin(),
    )
    return file
  }

  private fun packTarget(displayName: String, toolPaths: List<String>): PackTargetConfig =
    PackTargetConfig(displayName = displayName, tools = toolPaths)

  /**
   * Minimal descriptor YAML — just the fields the bundler reads (`name`, `script`).
   * Real pack tool descriptors carry more (description, inputSchema, _meta), but the
   * bundler doesn't need them; kaml's `strictMode = false` ignores absent extras.
   */
  private fun toolDescriptorYaml(name: String, scriptPath: String): String =
    """
    |name: $name
    |script: $scriptPath
    |""".trimMargin()

  /**
   * Locates an `esbuild` binary for the test:
   * 1. `TRAILBLAZE_TEST_ESBUILD_BINARY` env var (absolute path) — escape hatch for
   *    contributors who already have esbuild installed somewhere outside the repo.
   * 2. Walks parents from the JVM CWD looking for the framework root marker
   *    (`sdks/typescript-tools/package.json`), then returns
   *    `<root>/sdks/typescript/node_modules/.bin/esbuild` if it exists. Mirrors
   *    `defaultEsbuildBinary()` in the build-logic plugin.
   *
   * Returns `null` when neither yields an existing binary — every test in this
   * class is `assumeTrue`-skipped in that case.
   */
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
