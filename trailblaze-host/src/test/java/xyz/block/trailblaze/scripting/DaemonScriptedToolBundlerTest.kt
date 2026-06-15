package xyz.block.trailblaze.scripting

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.config.project.TrailmapTargetConfig
import xyz.block.trailblaze.config.project.TrailblazeTrailmapManifest
import java.io.File
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [DaemonScriptedToolBundler]. The tests split into two flavors:
 *
 *  - **esbuild-independent** (containment guards, wrapper synthesis, tool-name validation,
 *    the stubbed empty-output path): run unconditionally on every host. These never invoke
 *    the real `esbuild` binary — either they throw at validation/containment time before
 *    `runEsbuild` is reached, or they construct their own stub binary inline.
 *  - **esbuild-driven** (full-bundle production, cache-hit, descriptor-relative resolution,
 *    duplicate-name detection, stale-wrapper sweep): require a real `esbuild` on disk and
 *    call [assumeEsbuildPresent] up-front to skip cleanly when the binary is absent.
 *
 * The binary is located via [resolveEsbuildBinary], which mirrors the Gradle plugin's
 * `defaultEsbuildBinary` walk-up to the framework root and looks for
 * `sdks/typescript/node_modules/.bin/esbuild`. When the binary is absent (e.g. on a
 * fresh checkout where `bun install` hasn't run), only the esbuild-driven tests
 * assume-skip — esbuild-independent coverage still runs.
 */
class DaemonScriptedToolBundlerTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private lateinit var esbuild: File
  private lateinit var cacheDir: File
  private lateinit var bundler: DaemonScriptedToolBundler

  @Before
  fun setup() {
    cacheDir = tempFolder.newFolder("scripted-bundles-cache")
    // Tests that don't actually invoke esbuild (containment guards, wrapper synthesis,
    // tool-name validation) run unconditionally; tests that DO drive a bundle through
    // esbuild call [assumeEsbuildPresent] up-front to skip cleanly when the binary is
    // missing. Without this split, every test in the class skipped on hosts lacking
    // `bun install`, masking genuinely useful coverage that has no esbuild dependency.
    esbuild = resolveEsbuildBinary() ?: File(tempFolder.root, "missing-esbuild")
    bundler = DaemonScriptedToolBundler(esbuildBinary = esbuild, cacheDir = cacheDir)
  }

  private fun assumeEsbuildPresent() {
    assumeTrue(
      "esbuild binary not found under sdks/typescript/node_modules/.bin/esbuild — " +
        "run `bun install` in sdks/typescript to enable this test.",
      esbuild.isFile,
    )
  }

  @Test
  fun `bundleOne produces a non-empty bundle for a tiny inline ts file`() = runBlocking {
    assumeEsbuildPresent()
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
    assumeEsbuildPresent()
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
  fun `bundleAll resolves script path relative to the descriptor YAML file`() = runBlocking {
    // Pins the descriptor-relative resolution added alongside the trailmap self-containment
    // refactor. Before this change, `script: ./tool.ts` resolved from the JVM CWD (repo
    // root), forcing authors to write long paths like `./trails/config/trailmaps/mypacks/tool.ts`.
    // After: a bare `./tool.ts` in a descriptor at `trailmapDir/tools/tool.yaml` resolves to
    // `trailmapDir/tools/tool.ts` — the implementation lives next to the descriptor in the trailmap.
    assumeEsbuildPresent()
    val trailmapDir = tempFolder.newFolder("relativePathTrailmap")
    val toolScript = File(trailmapDir, "tools/myPack_relativePathTool.ts").apply {
      parentFile.mkdirs()
      writeText(
        """
        |const value: number = 1;
        |export function myPack_relativePathTool(): void { console.log(value); }
        |""".trimMargin(),
      )
    }
    // Descriptor uses a descriptor-relative path — just the filename with no dir prefix.
    File(trailmapDir, "tools/myPack_relativePathTool.yaml").writeText(
      toolDescriptorYaml(name = "myPack_relativePathTool", scriptPath = "./${toolScript.name}"),
    )
    val trailmap = TrailblazeTrailmapManifest(
      id = "relativePathTrailmap",
      target = trailmapTarget(displayName = "Relative Path Trailmap", toolNames = listOf("myPack_relativePathTool")),
    )
    val result = bundler.bundleAll(
      trailmaps = listOf(trailmap),
      trailmapBaseDirs = mapOf(trailmap to trailmapDir),
    )
    assertTrue(result.containsKey("myPack_relativePathTool"), "expected tool to be bundled; got keys: ${result.keys}")
    assertTrue(result["myPack_relativePathTool"]!!.isFile, "expected bundle file to exist")
    assertTrue(result["myPack_relativePathTool"]!!.length() > 0L, "expected non-empty bundle")
  }

  @Test
  fun `bundleAll across two synthetic trailmaps returns map keyed by tool name`() = runBlocking {
    assumeEsbuildPresent()
    val trailmapADir = tempFolder.newFolder("trailmapA")
    val trailmapBDir = tempFolder.newFolder("trailmapB")

    // Each script must export a function whose name matches the descriptor's `name:` —
    // the bundler's synthesized wrapper imports the named export for registration.
    val toolAScript = writeTinyTs("toolA-source.ts", exportName = "trailmapA_demo_toolA", body = "console.log('A')")
    val toolBScript = writeTinyTs("toolB-source.ts", exportName = "trailmapB_demo_toolB", body = "console.log('B')")

    File(trailmapADir, "tools/toolA.yaml").apply {
      parentFile.mkdirs()
      writeText(toolDescriptorYaml(name = "trailmapA_demo_toolA", scriptPath = toolAScript.absolutePath))
    }
    File(trailmapBDir, "tools/toolB.yaml").apply {
      parentFile.mkdirs()
      writeText(toolDescriptorYaml(name = "trailmapB_demo_toolB", scriptPath = toolBScript.absolutePath))
    }

    val trailmapA = TrailblazeTrailmapManifest(
      id = "trailmapA",
      target = trailmapTarget(displayName = "Trailmap A", toolNames = listOf("trailmapA_demo_toolA")),
    )
    val trailmapB = TrailblazeTrailmapManifest(
      id = "trailmapB",
      target = trailmapTarget(displayName = "Trailmap B", toolNames = listOf("trailmapB_demo_toolB")),
    )

    val result = bundler.bundleAll(
      trailmaps = listOf(trailmapA, trailmapB),
      trailmapBaseDirs = mapOf(trailmapA to trailmapADir, trailmapB to trailmapBDir),
    )

    assertEquals(
      setOf("trailmapA_demo_toolA", "trailmapB_demo_toolB"),
      result.keys,
      "expected the result map to contain both tools keyed by their declared names",
    )
    result.values.forEach { bundle ->
      assertTrue(bundle.isFile, "expected ${bundle.absolutePath} to exist")
      assertTrue(bundle.length() > 0L, "expected ${bundle.absolutePath} to be non-empty")
    }
  }

  @Test
  fun `bundleAll fails when target tools references an unknown name`() = runBlocking {
    // With the name-based resolution model, `target.tools:` lists tool names rather than
    // paths. A name that doesn't match any descriptor under `<trailmap>/tools/` fails with the
    // discovered-name list embedded for triage. (Replaces the legacy descriptor-path
    // containment test — there are no paths in `target.tools:` to escape from anymore.)
    val trailmapDir = tempFolder.newFolder("unknownNameTrailmap")
    File(trailmapDir, "tools/known.yaml").apply {
      parentFile.mkdirs()
      writeText(toolDescriptorYaml(name = "knownTool", scriptPath = "./known.ts"))
    }
    val trailmap = TrailblazeTrailmapManifest(
      id = "unknownNameTrailmap",
      target = trailmapTarget(displayName = "Unknown Name Trailmap", toolNames = listOf("missingTool")),
    )
    val thrown = assertFailsWith<IOException> {
      bundler.bundleAll(trailmaps = listOf(trailmap), trailmapBaseDirs = mapOf(trailmap to trailmapDir))
    }
    val message = thrown.message.orEmpty()
    assertTrue(
      message.contains("'missingTool'"),
      "expected message to name the unknown tool; got: $message",
    )
    assertTrue(
      message.contains("knownTool"),
      "expected message to surface available names; got: $message",
    )
  }

  @Test
  fun `bundleAll rejects relative script that escapes the trailmap dir`() = runBlocking {
    // Pins canonical-path containment for the session-time resolver, matching the loader's
    // mcp_servers containment guarantee. Without this check, a descriptor declaring
    // `script: ../sibling.ts` would resolve outside the trailmap at session start with no
    // guard — defense-in-depth across all the resolvers.
    val trailmapDir = tempFolder.newFolder("containmentTrailmap")
    // Sibling file OUTSIDE the trailmap — exists so the test pins containment, not file-not-found.
    val outsideScript = File(trailmapDir.parentFile, "sibling-outside.ts").apply {
      writeText("export function escapeTool(): void {}")
    }
    assertTrue(outsideScript.isFile, "outside script must exist for containment to be the real failure")
    // Descriptor lives under `<trailmapDir>/tools/` (the only place discovery looks). Its
    // `script:` field reaches outside the trailmap via `../../`.
    File(trailmapDir, "tools/escapeTool.yaml").apply {
      parentFile.mkdirs()
      writeText(toolDescriptorYaml(name = "escapeTool", scriptPath = "../../${outsideScript.name}"))
    }
    val trailmap = TrailblazeTrailmapManifest(
      id = "containmentTrailmap",
      target = trailmapTarget(displayName = "Containment Trailmap", toolNames = listOf("escapeTool")),
    )
    val thrown = assertFailsWith<IOException> {
      bundler.bundleAll(trailmaps = listOf(trailmap), trailmapBaseDirs = mapOf(trailmap to trailmapDir))
    }
    val message = thrown.message.orEmpty()
    assertTrue(
      message.contains("resolves outside the trailmap directory"),
      "expected message to call out trailmap containment; got: $message",
    )
    assertTrue(
      message.contains("escapeTool"),
      "expected message to name the offending tool; got: $message",
    )
  }

  @Test
  fun `bundleAll throws when two trailmaps declare the same scripted tool name`() = runBlocking {
    // Pins the duplicate-name detection added in 520bb7ac5. Pre-fix, a LinkedHashMap.put
    // would silently overwrite the earlier entry; downstream dispatch would route to
    // whichever bundle won the race. We want a loud failure that names both trailmap ids.
    assumeEsbuildPresent()
    val trailmapADir = tempFolder.newFolder("dupTrailmapA")
    val trailmapBDir = tempFolder.newFolder("dupTrailmapB")

    val sharedToolName = "duplicate_demo_tool"
    val toolAScript = writeTinyTs("dupA-source.ts", exportName = sharedToolName, body = "console.log('A')")
    val toolBScript = writeTinyTs("dupB-source.ts", exportName = sharedToolName, body = "console.log('B')")

    File(trailmapADir, "tools/tool.yaml").apply {
      parentFile.mkdirs()
      writeText(toolDescriptorYaml(name = sharedToolName, scriptPath = toolAScript.absolutePath))
    }
    File(trailmapBDir, "tools/tool.yaml").apply {
      parentFile.mkdirs()
      writeText(toolDescriptorYaml(name = sharedToolName, scriptPath = toolBScript.absolutePath))
    }

    val trailmapA = TrailblazeTrailmapManifest(
      id = "dupTrailmapA",
      target = trailmapTarget(displayName = "Dup A", toolNames = listOf(sharedToolName)),
    )
    val trailmapB = TrailblazeTrailmapManifest(
      id = "dupTrailmapB",
      target = trailmapTarget(displayName = "Dup B", toolNames = listOf(sharedToolName)),
    )

    val thrown = assertFailsWith<IOException> {
      bundler.bundleAll(
        trailmaps = listOf(trailmapA, trailmapB),
        trailmapBaseDirs = mapOf(trailmapA to trailmapADir, trailmapB to trailmapBDir),
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
  fun `bundleAll fails when target tools lists the same name twice in one trailmap`() = runBlocking {
    // Pin the per-trailmap dedup added alongside the name-resolution flip. Without this check,
    // listing the same name twice in `target.tools:` would trip the cross-trailmap collision
    // guard later with a message that incorrectly blames a sibling trailmap. The per-trailmap check
    // fires first and produces an accurate diagnostic.
    val trailmapDir = tempFolder.newFolder("dupInTargetTrailmap")
    File(trailmapDir, "tools/foo.yaml").apply {
      parentFile.mkdirs()
      writeText(toolDescriptorYaml(name = "foo", scriptPath = "./foo.ts"))
    }
    val trailmap = TrailblazeTrailmapManifest(
      id = "dupInTargetTrailmap",
      target = trailmapTarget(displayName = "Dup In Target", toolNames = listOf("foo", "foo")),
    )

    val thrown = assertFailsWith<IOException> {
      bundler.bundleAll(trailmaps = listOf(trailmap), trailmapBaseDirs = mapOf(trailmap to trailmapDir))
    }
    val message = thrown.message.orEmpty()
    assertTrue(message.contains("'foo'"), "expected duplicate name in message; got: $message")
    assertTrue(message.contains("more than once"), "expected per-trailmap diagnostic; got: $message")
    assertTrue(
      !message.contains("Duplicate scripted-tool name 'foo' across trailmaps"),
      "must NOT misattribute to cross-trailmap collision; got: $message",
    )
  }

  @Test
  fun `bundleAll rejects symlinked descriptor that escapes the trailmap dir at discovery`() = runBlocking {
    // Pin the symlink-containment check added in the round-1 follow-up. A `<trailmap>/tools/foo.yaml`
    // that symlinks outside the trailmap must be rejected, not silently followed — matches the
    // runtime loader's `TrailmapSource.readFilesystemSibling` containment guarantee.
    //
    // Note the fixture: `target.tools:` lists `outsideTool` (the name *inside* the outside
    // descriptor) rather than `escape` (the symlink filename) so the test pins discovery-time
    // rejection unambiguously. If the canonical-path check ever silently relaxed, the
    // failure would change to UnknownScriptedToolName ('outsideTool' didn't register because
    // it was filtered out at containment time) — distinguishable from the current "resolves
    // outside" assertion.
    val trailmapDir = tempFolder.newFolder("symlinkContainmentTrailmap")
    File(trailmapDir, "tools").mkdirs()
    val outsideDir = tempFolder.newFolder("outside-tools")
    val outsideDescriptor = File(outsideDir, "outside.yaml").apply {
      writeText(toolDescriptorYaml(name = "outsideTool", scriptPath = "./outside.ts"))
    }
    val symlinkSource = File(trailmapDir, "tools/outsideTool.yaml").toPath()
    try {
      java.nio.file.Files.createSymbolicLink(symlinkSource, outsideDescriptor.toPath())
    } catch (_: UnsupportedOperationException) {
      // Filesystem doesn't support symlinks (Windows without elevated perms) — skip the test
      // rather than fail it. The bundler test for the same guard uses the same pattern.
      return@runBlocking
    }
    val trailmap = TrailblazeTrailmapManifest(
      id = "symlinkContainmentTrailmap",
      target = trailmapTarget(displayName = "Symlink Containment", toolNames = listOf("outsideTool")),
    )

    val thrown = assertFailsWith<IOException> {
      bundler.bundleAll(trailmaps = listOf(trailmap), trailmapBaseDirs = mapOf(trailmap to trailmapDir))
    }
    val message = thrown.message.orEmpty()
    assertTrue(
      message.contains("resolves outside the trailmap directory"),
      "expected containment failure; got: $message",
    )
  }

  @Test
  fun `bundleAll surfaces unknown-name with skipped-file hint when target tools references a malformed descriptor`() = runBlocking {
    // Lead-dev round 3 #I2 + #I5: this test exercises the discovery-phase skip-and-log
    // behavior + the downstream unknown-name diagnostic WITHOUT requiring esbuild — the
    // failure surfaces before any bundling happens. Pinning this independently of esbuild
    // means clean CI agents (no `bun install`) still get the regression check.
    val trailmapDir = tempFolder.newFolder("malformedReferencedTrailmap")
    File(trailmapDir, "tools").mkdirs()
    File(trailmapDir, "tools/foo.yaml").writeText(
      """
      |script: ./foo.ts
      |this is not valid yaml:::
      |  - { unclosed
      |""".trimMargin(),
    )
    val trailmap = TrailblazeTrailmapManifest(
      id = "malformedReferencedTrailmap",
      target = trailmapTarget(displayName = "Malformed Referenced", toolNames = listOf("foo")),
    )

    val thrown = assertFailsWith<IOException> {
      bundler.bundleAll(trailmaps = listOf(trailmap), trailmapBaseDirs = mapOf(trailmap to trailmapDir))
    }
    val message = thrown.message.orEmpty()
    assertTrue("got: $message") { message.contains("'foo'") }
    assertTrue(
      "expected the skipped-file hint to point at foo.yaml; got: $message",
    ) { message.contains("foo.yaml") && message.contains("skipped during discovery") }
  }

  @Test
  fun `bundleAll skips malformed descriptors and continues with sibling tools`() = runBlocking {
    // Lead-dev review #2 (round 2): a half-written `<trailmap>/tools/broken.yaml` must NOT take
    // the whole trailmap out — sibling descriptors still register and `target.tools:` references
    // resolve normally against them.
    assumeEsbuildPresent()
    val trailmapDir = tempFolder.newFolder("wipFriendlyTrailmap")
    File(trailmapDir, "tools").mkdirs()
    // Malformed YAML — half-written by the author.
    File(trailmapDir, "tools/broken.yaml").writeText(
      """
      |script: ./broken.ts
      |this is not valid yaml:::
      |  - { unclosed
      |""".trimMargin(),
    )
    // Working sibling — must still register and bundle.
    val workingScript = writeTinyTs("wip-working.ts", exportName = "workingTool")
    File(trailmapDir, "tools/working.yaml").writeText(
      toolDescriptorYaml(name = "workingTool", scriptPath = workingScript.absolutePath),
    )
    val trailmap = TrailblazeTrailmapManifest(
      id = "wipFriendlyTrailmap",
      target = trailmapTarget(displayName = "WIP Friendly", toolNames = listOf("workingTool")),
    )

    val result = bundler.bundleAll(trailmaps = listOf(trailmap), trailmapBaseDirs = mapOf(trailmap to trailmapDir))
    assertTrue(
      result.containsKey("workingTool"),
      "sibling descriptor must still register despite the malformed neighbor; got keys: ${result.keys}",
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
  fun `synthesized wrapper exposes a tools Proxy with SDK-aligned reserved props and unwrap`() {
    // The wrapper-synthesized `__client.tools` Proxy must mirror the SDK's
    // `createToolsProxy` (`sdks/typescript/src/client.ts:535`) so typed handlers see
    // the same `ctx.tools.<name>(args)` semantics whether they run on the QuickJS
    // bundle path (this wrapper) or the subprocess MCP path (the SDK's real Proxy).
    // The Proxy is emitted into every per-tool IIFE the bundler produces. Two drift
    // points this test guards against:
    //  - `TOOLS_PROXY_RESERVED_PROPS` parity (`client.ts:504-521`) — including
    //    `__proto__`, which most JS engines synthesize as a Proxy `get`-trap.
    //    Without the full reserved set, `await client.tools` (probes `.then`),
    //    `JSON.stringify(client.tools)` (probes `.toJSON` / `.toString`), and
    //    prototype walks (probe `.constructor` / `.__proto__`) would dispatch a
    //    spurious `callTool("then", ...)` to the host.
    //  - Envelope unwrap (`_unwrapToolResult` at `client.ts:615`) — return
    //    `structuredContent` if present, fall back to `textContent`. The wrapper
    //    additionally handles `message` for the on-device callback transport's
    //    `TrailblazeToolResult.Success` shape (serialized verbatim by
    //    `SessionScopedHostBinding.callFromBundle`). Without unwrap, a typed handler
    //    reading `const r = await ctx.tools.foo(args); r.field` sees the envelope,
    //    not the structured payload — silent regression for any handler composing
    //    other tools.
    //
    // The actual runtime semantics are pinned by the integration test that runs
    // the wrapper through QuickJS — see `LaunchedScriptingRuntimeTest` /
    // `QuickJsToolHostTest`. This test guards against drift in the generated
    // wrapper source so a future refactor on either side (the SDK Proxy or this
    // synthesis) catches the divergence before it ships.
    val src = writeTinyTs("proxy-parity-source.ts", exportName = "doSomething")
    val wrapper = bundler.synthesizeWrapper(src, toolName = "doSomething")

    // Reserved props: every name from TOOLS_PROXY_RESERVED_PROPS appears in the
    // single combined conditional. The Proxy returns `undefined` for these so
    // value-coercion / inspect paths don't fire a stray callTool against the daemon.
    val reservedProps = listOf(
      "then", "catch", "finally",
      "constructor", "prototype", "__proto__",
      "toString", "valueOf", "toJSON",
    )
    for (prop in reservedProps) {
      assertTrue(
        wrapper.contains("name === '$prop'"),
        "expected reserved-prop check for '$prop' in wrapper; got:\n$wrapper",
      )
    }

    // Envelope unwrap — structuredContent → textContent → message fallback chain.
    // Matches `_unwrapToolResult` at `client.ts:615` plus the on-device
    // `TrailblazeToolResult.Success { message }` shape `SessionScopedHostBinding`
    // emits. Drift on either side surfaces here.
    assertTrue(
      wrapper.contains("envelope.structuredContent !== undefined") &&
        wrapper.contains("envelope.structuredContent !== null"),
      "expected structuredContent check in wrapper; got:\n$wrapper",
    )
    assertTrue(
      wrapper.contains("envelope.textContent !== undefined") &&
        wrapper.contains("envelope.textContent !== null"),
      "expected textContent fallback in wrapper; got:\n$wrapper",
    )
    assertTrue(
      wrapper.contains("return envelope.message"),
      "expected message fallback (TrailblazeToolResult.Success on-device shape) " +
        "in wrapper; got:\n$wrapper",
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
    assumeEsbuildPresent()
    val src = writeTinyTs("sweep-source.ts", exportName = "doSomething")
    // Drop a stale wrapper alongside the user's script — what an abrupt JVM exit would
    // have left behind.
    val staleWrapper = File(src.parentFile, "${DaemonScriptedToolBundler.WRAPPER_FILENAME_PREFIX}stale1234.ts")
    staleWrapper.writeText("// pretend this is a leftover from a SIGKILLed daemon")
    // Age it past the sweep's min-age floor so it reads as a genuine crash leftover. The sweep
    // deliberately spares recent wrappers (a concurrent daemon's live esbuild entry point).
    staleWrapper.setLastModified(System.currentTimeMillis() - 60L * 60 * 1000)
    assertTrue(staleWrapper.isFile, "stale wrapper sentinel must exist before bundleOne")

    bundler.bundleOne(src, toolName = "doSomething")

    assertTrue(
      !staleWrapper.exists(),
      "expected bundleOneInternal to sweep stale wrapper file; still at ${staleWrapper.absolutePath}",
    )
  }

  @Test
  fun `stale-wrapper sweep runs at most once per parent directory per JVM`() = runBlocking {
    // Pins the once-per-(JVM, directory) sweep dedup added to fix a concurrent-bundle race:
    // pre-fix, every bundleOne call swept ALL `.trailblaze-wrapper-*.ts` files in the parent
    // dir, including ones a concurrent bundler had just written and was still feeding to
    // esbuild — surfaced as `[ERROR] Could not resolve ".../.trailblaze-wrapper-…ts"`.
    // After the fix, the sweep runs only on the first call per (JVM, directory); subsequent
    // calls leave sibling wrappers alone (real ones from a previous crash get cleaned up by
    // the first call; live ones from concurrent calls are left intact).
    assumeEsbuildPresent()
    val src1 = writeTinyTs("sweep-once-1.ts", exportName = "first")
    val firstStale = File(src1.parentFile, "${DaemonScriptedToolBundler.WRAPPER_FILENAME_PREFIX}stale-before-first.ts")
    firstStale.writeText("// stale from a previous JVM crash")
    // Age it past the sweep's min-age floor — a real crash leftover is old.
    firstStale.setLastModified(System.currentTimeMillis() - 60L * 60 * 1000)
    bundler.bundleOne(src1, toolName = "first")
    assertTrue(
      !firstStale.exists(),
      "first call must sweep pre-existing stale wrappers; still at ${firstStale.absolutePath}",
    )

    // Drop a NEW sibling wrapper after the first call. This simulates the state a
    // concurrent bundle would leave in the directory (its in-flight wrapper). The next
    // bundleOne in the same directory must NOT delete it.
    val src2 = writeTinyTs("sweep-once-2.ts", exportName = "second")
    val concurrentlyActiveWrapper = File(
      src2.parentFile,
      "${DaemonScriptedToolBundler.WRAPPER_FILENAME_PREFIX}stale-after-first.ts",
    )
    concurrentlyActiveWrapper.writeText("// would belong to a concurrent bundler — must survive")
    bundler.bundleOne(src2, toolName = "second")
    assertTrue(
      concurrentlyActiveWrapper.exists(),
      "second call in same dir must NOT re-sweep; concurrent-wrapper stand-in was deleted at " +
        concurrentlyActiveWrapper.absolutePath,
    )
  }

  @Test
  fun `stale-wrapper sweep spares recent wrappers from concurrent daemons`() = runBlocking {
    // Multi-daemon safety: a second daemon (separate JVM) may have a live wrapper in the shared
    // source dir when THIS JVM runs its first sweep. The per-(JVM, dir) dedup can't see across
    // processes, so the sweep must spare wrappers younger than the esbuild timeout (mtime-based,
    // process-independent). Pins the fix for the cross-JVM "Could not resolve
    // .trailblaze-wrapper-*" race observed when one daemon-per-copy bundles in the same dir.
    assumeEsbuildPresent()
    val src = writeTinyTs("sweep-recent-peer.ts", exportName = "doSomething")
    val recentPeerWrapper = File(
      src.parentFile,
      "${DaemonScriptedToolBundler.WRAPPER_FILENAME_PREFIX}peer-live.ts",
    )
    // Freshly written (current mtime) = younger than the sweep's min-age floor.
    recentPeerWrapper.writeText("// a concurrent daemon's in-flight wrapper — must survive the sweep")

    bundler.bundleOne(src, toolName = "doSomething")

    assertTrue(
      recentPeerWrapper.exists(),
      "sweep must spare a recent (live concurrent-daemon) wrapper; deleted at ${recentPeerWrapper.absolutePath}",
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

  private fun trailmapTarget(displayName: String, toolNames: List<String>): TrailmapTargetConfig =
    TrailmapTargetConfig(displayName = displayName, tools = toolNames)

  /**
   * Minimal descriptor YAML — just the fields the bundler reads (`name`, `script`).
   * Real trailmap tool descriptors carry more (description, inputSchema, _meta), but the
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
