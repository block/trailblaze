package xyz.block.trailblaze.bundle

import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * Tests for [TrailblazePackBundler] — the core walker used by the
 * `trailblaze.bundle` plugin to emit per-pack TypeScript bindings
 * (`tools.d.ts`) augmenting `@trailblaze/scripting`'s `TrailblazeToolMap` interface.
 *
 * Covers:
 *  - Schema vocabulary translation: string / number / boolean / array / object / enum / null.
 *  - Required-vs-optional handling (`required: false` → `?:`, default required: true).
 *  - Special TS-identifier characters in tool names get quoted (`"foo-bar": ...`).
 *  - Empty schema → `Record<string, never>` to model "tool takes no args."
 *  - Multi-pack collection produces a single sorted-by-name augmentation block.
 *  - Duplicate tool names across packs fail loudly (declaration-merging would silently
 *    pick one shape otherwise).
 *  - Missing `inputSchema` is OK — emits a parameterless entry.
 *  - JSDoc `* /` sequences inside descriptions are escaped so they don't close the comment.
 *
 * Each test runs against a temp-dir fixture; no Gradle test fixture is needed.
 */
class TrailblazePackBundlerTest {

  private val tempDirs = mutableListOf<File>()

  @AfterTest
  fun cleanupTempDirs() {
    tempDirs.forEach { it.deleteRecursively() }
    tempDirs.clear()
  }

  @Test
  fun `single pack with one scripted tool emits a typed entry`() {
    val packsDir = newTempDir()
    writePack(
      packsDir, packId = "alpha",
      packYaml = """
        id: alpha
        target:
          display_name: Alpha
          tools:
            - tools/alpha_doThing.yaml
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "alpha", toolFile = "tools/alpha_doThing.yaml",
      toolYaml = """
        script: ./tools/alpha_doThing.js
        name: alpha_doThing
        description: Does a thing.
        inputSchema:
          message:
            type: string
            description: What to say.
          retries:
            type: integer
            description: How many times.
            required: false
      """.trimIndent(),
    )

    runGenerator(packsDir)
    val rendered = dtsFile(packsDir, "alpha").readText()

    assertTrue("rendered: $rendered") { rendered.contains("declare module \"@trailblaze/scripting\"") }
    assertTrue("rendered: $rendered") { rendered.contains("interface TrailblazeToolMap") }
    assertTrue("rendered: $rendered") { rendered.contains("alpha_doThing: {") }
    assertTrue("rendered: $rendered") { rendered.contains("message: string;") }
    // `required: false` translates to optional `retries?: number;`.
    assertTrue("rendered: $rendered") { rendered.contains("retries?: number;") }
    assertTrue("rendered: $rendered") { rendered.contains("/** Does a thing. */") || rendered.contains("Does a thing.") }
    assertTrue("rendered: $rendered") { rendered.endsWith("export {};\n") }
  }

  @Test
  fun `enum field becomes a string-literal union`() {
    val packsDir = newTempDir()
    writePack(
      packsDir, packId = "p",
      packYaml = """
        id: p
        target:
          display_name: P
          tools:
            - tools/withEnum.yaml
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "p", toolFile = "tools/withEnum.yaml",
      toolYaml = """
        script: ./tools/withEnum.js
        name: withEnum
        inputSchema:
          mode:
            type: string
            enum: [fast, slow]
      """.trimIndent(),
    )

    runGenerator(packsDir)
    val rendered = dtsFile(packsDir, "p").readText()

    assertTrue("rendered: $rendered") { rendered.contains("mode: \"fast\" | \"slow\";") }
  }

  @Test
  fun `array and boolean and object types translate to TS equivalents`() {
    val packsDir = newTempDir()
    writePack(
      packsDir, packId = "p",
      packYaml = """
        id: p
        target:
          display_name: P
          tools:
            - tools/widget.yaml
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "p", toolFile = "tools/widget.yaml",
      toolYaml = """
        script: ./tools/widget.js
        name: widget
        inputSchema:
          tags:
            type: array
          enabled:
            type: boolean
          extras:
            type: object
          weird:
            type: someUnknownType
      """.trimIndent(),
    )

    runGenerator(packsDir)
    val rendered = dtsFile(packsDir, "p").readText()

    assertTrue("rendered: $rendered") { rendered.contains("tags: unknown[];") }
    assertTrue("rendered: $rendered") { rendered.contains("enabled: boolean;") }
    assertTrue("rendered: $rendered") { rendered.contains("extras: Record<string, unknown>;") }
    // Unknown schema type falls through to `unknown` rather than failing the build.
    assertTrue("rendered: $rendered") { rendered.contains("weird: unknown;") }
  }

  @Test
  fun `empty inputSchema renders Record string never`() {
    val packsDir = newTempDir()
    writePack(
      packsDir, packId = "p",
      packYaml = """
        id: p
        target:
          display_name: P
          tools:
            - tools/noargs.yaml
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "p", toolFile = "tools/noargs.yaml",
      toolYaml = """
        script: ./tools/noargs.js
        name: noargs
        description: Takes no args.
      """.trimIndent(),
    )

    runGenerator(packsDir)
    val rendered = dtsFile(packsDir, "p").readText()

    assertTrue("rendered: $rendered") { rendered.contains("noargs: Record<string, never>;") }
  }

  @Test
  fun `same tool name in two different packs is allowed (per-pack scoping)`() {
    // Per-pack output: each pack writes its own .d.ts, so two packs declaring the same
    // tool name no longer collide at the bundler level — they live in separate
    // augmentation files. Cross-pack collisions (when both .d.ts files end up in the
    // same tsconfig include scope) are TypeScript's job to surface.
    val packsDir = newTempDir()
    writePack(
      packsDir, packId = "alpha",
      packYaml = """
        id: alpha
        target:
          display_name: Alpha
          tools:
            - tools/shared.yaml
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "alpha", toolFile = "tools/shared.yaml",
      toolYaml = """
        script: ./tools/shared.js
        name: sharedTool
        inputSchema:
          x: { type: string }
      """.trimIndent(),
    )
    writePack(
      packsDir, packId = "beta",
      packYaml = """
        id: beta
        target:
          display_name: Beta
          tools:
            - tools/shared.yaml
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "beta", toolFile = "tools/shared.yaml",
      toolYaml = """
        script: ./tools/shared.js
        name: sharedTool
        inputSchema:
          y: { type: number }
      """.trimIndent(),
    )

    runGenerator(packsDir)

    val alphaRendered = dtsFile(packsDir, "alpha").readText()
    val betaRendered = dtsFile(packsDir, "beta").readText()
    assertTrue("alpha: $alphaRendered") { alphaRendered.contains("sharedTool: {") && alphaRendered.contains("x: string;") }
    assertTrue("beta: $betaRendered") { betaRendered.contains("sharedTool: {") && betaRendered.contains("y: number;") }
  }

  @Test
  fun `same tool name twice inside one pack fails with a clear message`() {
    // Per-pack dedup still applies — a typo in the same pack that registers two YAML
    // descriptors with the same `name:` would silently produce a broken `.d.ts` without
    // this guard.
    val packsDir = newTempDir()
    writePack(
      packsDir, packId = "p",
      packYaml = """
        id: p
        target:
          display_name: P
          tools:
            - tools/a.yaml
            - tools/b.yaml
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "p", toolFile = "tools/a.yaml",
      toolYaml = """
        script: ./tools/a.js
        name: dupTool
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "p", toolFile = "tools/b.yaml",
      toolYaml = """
        script: ./tools/b.js
        name: dupTool
      """.trimIndent(),
    )

    val ex = assertFailsWith<TrailblazePackBundleException> { runGenerator(packsDir) }
    assertTrue("message: ${ex.message}") { ex.message?.contains("Duplicate scripted tool name 'dupTool'") == true }
  }

  @Test
  fun `multi-pack run produces one dts per pack`() {
    // With per-pack output, two packs each get their own bindings file containing only
    // their own tools. The within-pack tool ordering is still sorted by name (only
    // matters when a pack declares multiple tools).
    val packsDir = newTempDir()
    writePack(
      packsDir, packId = "z",
      packYaml = """
        id: z
        target:
          display_name: Z
          tools:
            - tools/zebra.yaml
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "z", toolFile = "tools/zebra.yaml",
      toolYaml = """
        script: ./tools/zebra.js
        name: zebraTool
      """.trimIndent(),
    )
    writePack(
      packsDir, packId = "a",
      packYaml = """
        id: a
        target:
          display_name: A
          tools:
            - tools/apple.yaml
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "a", toolFile = "tools/apple.yaml",
      toolYaml = """
        script: ./tools/apple.js
        name: appleTool
      """.trimIndent(),
    )

    runGenerator(packsDir)

    val zRendered = dtsFile(packsDir, "z").readText()
    val aRendered = dtsFile(packsDir, "a").readText()
    assertTrue("z pack should contain zebraTool, not appleTool: $zRendered") {
      zRendered.contains("zebraTool:") && !zRendered.contains("appleTool:")
    }
    assertTrue("a pack should contain appleTool, not zebraTool: $aRendered") {
      aRendered.contains("appleTool:") && !aRendered.contains("zebraTool:")
    }
  }

  @Test
  fun `target pack inherits scripted tools from a library pack via dependencies`() {
    // Phase 2: A target pack with `dependencies: [<library-pack-with-tools>]` should have
    // a `.d.ts` containing BOTH its own tools AND the library pack's tools, with consistent
    // (sorted-by-name) ordering. Mirrors the runtime-side per-target tool aggregation.
    val packsDir = newTempDir()
    writePack(
      packsDir, packId = "lib",
      packYaml = """
        id: lib
        target:
          display_name: Lib
          tools:
            - tools/libTool.yaml
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "lib", toolFile = "tools/libTool.yaml",
      toolYaml = """
        script: ./tools/libTool.js
        name: libTool
        description: Provided by the library pack.
        inputSchema:
          payload: { type: string }
      """.trimIndent(),
    )
    writePack(
      packsDir, packId = "app",
      packYaml = """
        id: app
        dependencies:
          - lib
        target:
          display_name: App
          tools:
            - tools/appTool.yaml
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "app", toolFile = "tools/appTool.yaml",
      toolYaml = """
        script: ./tools/appTool.js
        name: appTool
      """.trimIndent(),
    )

    runGenerator(packsDir)

    val appRendered = dtsFile(packsDir, "app").readText()
    assertTrue("app rendered should contain own tool appTool: $appRendered") {
      appRendered.contains("appTool:")
    }
    assertTrue("app rendered should contain inherited tool libTool: $appRendered") {
      appRendered.contains("libTool: {")
    }
    // The sourcePath comment on the inherited entry points at lib's tool YAML, naming the
    // pack of origin. Authors see at-a-glance where an unexpected entry came from.
    // (On macOS, temp dirs are symlinks under /private/var/..., so relative paths can
    // include ../ prefixes; assert just on the trailing pack/file path that's always
    // present in the rendered sourcePath comment.)
    assertTrue("app rendered should attribute libTool back to lib's tool YAML: $appRendered") {
      appRendered.contains("lib/tools/libTool.yaml")
    }
    // Sort order: entries are alphabetical by tool name. appTool < libTool.
    val appIndex = appRendered.indexOf("appTool:")
    val libIndex = appRendered.indexOf("libTool:")
    assertTrue("expected appTool before libTool (sorted by name): app=$appIndex lib=$libIndex") {
      appIndex in 0 until libIndex
    }

    // The library pack's own `.d.ts` should still cover only its own tools — there's no
    // implicit self-inclusion through dependencies, and `lib` doesn't depend on `app`.
    val libRendered = dtsFile(packsDir, "lib").readText()
    assertTrue("lib rendered should contain libTool: $libRendered") {
      libRendered.contains("libTool:")
    }
    assertTrue("lib rendered should NOT contain appTool (no reverse inheritance): $libRendered") {
      !libRendered.contains("appTool:")
    }
  }

  @Test
  fun `transitive dependency chain A to B to C aggregates all three packs into A's bindings`() {
    // Pack A depends on B; B depends on C. A's `.d.ts` should contain its own + B's + C's
    // scripted tools. Cycle-free three-level chain validates the transitive DFS.
    val packsDir = newTempDir()
    writePack(
      packsDir, packId = "c",
      packYaml = """
        id: c
        target:
          display_name: C
          tools:
            - tools/cTool.yaml
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "c", toolFile = "tools/cTool.yaml",
      toolYaml = """
        script: ./tools/cTool.js
        name: cTool
      """.trimIndent(),
    )
    writePack(
      packsDir, packId = "b",
      packYaml = """
        id: b
        dependencies: [c]
        target:
          display_name: B
          tools:
            - tools/bTool.yaml
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "b", toolFile = "tools/bTool.yaml",
      toolYaml = """
        script: ./tools/bTool.js
        name: bTool
      """.trimIndent(),
    )
    writePack(
      packsDir, packId = "a",
      packYaml = """
        id: a
        dependencies: [b]
        target:
          display_name: A
          tools:
            - tools/aTool.yaml
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "a", toolFile = "tools/aTool.yaml",
      toolYaml = """
        script: ./tools/aTool.js
        name: aTool
      """.trimIndent(),
    )

    runGenerator(packsDir)

    val aRendered = dtsFile(packsDir, "a").readText()
    assertTrue("a should contain its own aTool: $aRendered") { aRendered.contains("aTool:") }
    assertTrue("a should inherit bTool through 1-deep dep: $aRendered") { aRendered.contains("bTool:") }
    assertTrue("a should inherit cTool through 2-deep transitive dep: $aRendered") { aRendered.contains("cTool:") }
    // B inherits only C; A is not in B's closure.
    val bRendered = dtsFile(packsDir, "b").readText()
    assertTrue("b should NOT contain aTool: $bRendered") { !bRendered.contains("aTool:") }
    assertTrue("b should contain bTool: $bRendered") { bRendered.contains("bTool:") }
    assertTrue("b should inherit cTool: $bRendered") { bRendered.contains("cTool:") }
    // C has no deps.
    val cRendered = dtsFile(packsDir, "c").readText()
    assertTrue("c should contain only cTool: $cRendered") {
      cRendered.contains("cTool:") && !cRendered.contains("aTool:") && !cRendered.contains("bTool:")
    }
  }

  @Test
  fun `diamond dependency does not double-include the shared transitive pack's tools`() {
    // Root depends on A and B; both A and B depend on D. D's tools must appear ONCE in
    // root's .d.ts — a naive DFS without memoization would double-emit and produce a
    // TypeScript declaration-merging error on the same interface key.
    val packsDir = newTempDir()
    writePack(
      packsDir, packId = "d",
      packYaml = """
        id: d
        target:
          display_name: D
          tools:
            - tools/dTool.yaml
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "d", toolFile = "tools/dTool.yaml",
      toolYaml = """
        script: ./tools/dTool.js
        name: dTool
      """.trimIndent(),
    )
    writePack(packsDir, packId = "leftA", packYaml = "id: leftA\ndependencies: [d]\n")
    writePack(packsDir, packId = "leftB", packYaml = "id: leftB\ndependencies: [d]\n")
    writePack(
      packsDir, packId = "root",
      packYaml = """
        id: root
        dependencies: [leftA, leftB]
        target:
          display_name: Root
          tools:
            - tools/rootTool.yaml
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "root", toolFile = "tools/rootTool.yaml",
      toolYaml = """
        script: ./tools/rootTool.js
        name: rootTool
      """.trimIndent(),
    )

    runGenerator(packsDir)

    val rootRendered = dtsFile(packsDir, "root").readText()
    // dTool must appear once; lastIndexOf == indexOf means single occurrence.
    val firstDTool = rootRendered.indexOf("dTool:")
    val lastDTool = rootRendered.lastIndexOf("dTool:")
    assertTrue("dTool should appear exactly once in root bindings: first=$firstDTool last=$lastDTool") {
      firstDTool >= 0 && firstDTool == lastDTool
    }
    assertTrue("rootTool present: $rootRendered") { rootRendered.contains("rootTool:") }
  }

  @Test
  fun `missing dependency is silently skipped at build time`() {
    // At build time the bundler can't see classpath-shipped packs (e.g. the framework
    // `trailblaze` stdlib pack). Listing a missing dep is benign — the runtime loader's
    // strict missing-dep check fires at daemon start, not here.
    val packsDir = newTempDir()
    writePack(
      packsDir, packId = "p",
      packYaml = """
        id: p
        dependencies: [trailblaze]
        target:
          display_name: P
          tools:
            - tools/pTool.yaml
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "p", toolFile = "tools/pTool.yaml",
      toolYaml = """
        script: ./tools/pTool.js
        name: pTool
      """.trimIndent(),
    )

    runGenerator(packsDir)
    val rendered = dtsFile(packsDir, "p").readText()
    assertTrue("p rendered should contain its own pTool: $rendered") { rendered.contains("pTool:") }
  }

  @Test
  fun `cyclic dependencies fail loudly`() {
    val packsDir = newTempDir()
    writePack(
      packsDir, packId = "a",
      packYaml = """
        id: a
        dependencies: [b]
        target:
          display_name: A
          tools:
            - tools/aTool.yaml
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "a", toolFile = "tools/aTool.yaml",
      toolYaml = """
        script: ./tools/aTool.js
        name: aTool
      """.trimIndent(),
    )
    writePack(packsDir, packId = "b", packYaml = "id: b\ndependencies: [a]\n")

    val ex = assertFailsWith<TrailblazePackBundleException.CyclicDependencies> {
      runGenerator(packsDir)
    }
    assertTrue("message: ${ex.message}") { ex.message?.contains("Cycle detected") == true }
    assertTrue("message: ${ex.message}") { ex.message?.contains("'a'") == true }
  }

  @Test
  fun `self-referential dependency is detected as a cycle`() {
    val packsDir = newTempDir()
    writePack(
      packsDir, packId = "p",
      packYaml = """
        id: p
        dependencies: [p]
        target:
          display_name: P
          tools:
            - tools/pTool.yaml
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "p", toolFile = "tools/pTool.yaml",
      toolYaml = """
        script: ./tools/pTool.js
        name: pTool
      """.trimIndent(),
    )

    val ex = assertFailsWith<TrailblazePackBundleException.CyclicDependencies> {
      runGenerator(packsDir)
    }
    assertTrue("message: ${ex.message}") { ex.message?.contains("'p'") == true }
  }

  @Test
  fun `same tool name in pack and dependency fails with a clear cross-pack message`() {
    val packsDir = newTempDir()
    writePack(
      packsDir, packId = "lib",
      packYaml = """
        id: lib
        target:
          display_name: Lib
          tools:
            - tools/clash.yaml
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "lib", toolFile = "tools/clash.yaml",
      toolYaml = """
        script: ./tools/clash.js
        name: clashingName
      """.trimIndent(),
    )
    writePack(
      packsDir, packId = "consumer",
      packYaml = """
        id: consumer
        dependencies: [lib]
        target:
          display_name: Consumer
          tools:
            - tools/myclash.yaml
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "consumer", toolFile = "tools/myclash.yaml",
      toolYaml = """
        script: ./tools/myclash.js
        name: clashingName
      """.trimIndent(),
    )

    val ex = assertFailsWith<TrailblazePackBundleException.DuplicateToolName> {
      runGenerator(packsDir)
    }
    assertTrue("message: ${ex.message}") {
      ex.message?.contains("clashingName") == true &&
        ex.message?.contains("'lib'") == true &&
        ex.message?.contains("'consumer'") == true
    }
  }

  @Test
  fun `two workspace packs declaring the same id fail loudly`() {
    // Pack ids are the dep-graph routing key — sibling collisions are non-recoverable.
    val packsDir = newTempDir()
    File(packsDir, "first").mkdirs()
    File(packsDir, "first/pack.yaml").writeText("id: duplicated\n")
    File(packsDir, "second").mkdirs()
    File(packsDir, "second/pack.yaml").writeText("id: duplicated\n")

    val ex = assertFailsWith<TrailblazePackBundleException.DuplicatePackId> {
      runGenerator(packsDir)
    }
    assertTrue("message: ${ex.message}") { ex.message?.contains("'duplicated'") == true }
  }

  @Test
  fun `tool name with hyphen is emitted as a quoted property`() {
    val packsDir = newTempDir()
    writePack(
      packsDir, packId = "p",
      packYaml = """
        id: p
        target:
          display_name: P
          tools:
            - tools/hyphen.yaml
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "p", toolFile = "tools/hyphen.yaml",
      toolYaml = """
        script: ./tools/hyphen.js
        name: weird-tool-name
      """.trimIndent(),
    )

    runGenerator(packsDir)
    val rendered = dtsFile(packsDir, "p").readText()

    assertTrue("rendered: $rendered") { rendered.contains("\"weird-tool-name\":") }
  }

  @Test
  fun `library pack with no scripted tools writes no dts file`() {
    // Library pack (no `target:`) — bundler should silently skip emission rather than
    // writing a placeholder `.d.ts`. A no-tools target pack behaves the same way.
    val packsDir = newTempDir()
    writePack(
      packsDir, packId = "lib",
      packYaml = """
        id: lib
        defaults:
          android:
            tool_sets: [observation]
      """.trimIndent(),
    )

    runGenerator(packsDir)

    assertTrue("expected no dts written for library pack") { !dtsFile(packsDir, "lib").exists() }
  }

  @Test
  fun `parent-segment tool refs are rejected`() {
    val packsDir = newTempDir()
    writePack(
      packsDir, packId = "p",
      packYaml = """
        id: p
        target:
          display_name: P
          tools:
            - ../escape.yaml
      """.trimIndent(),
    )

    val ex = assertFailsWith<TrailblazePackBundleException> { runGenerator(packsDir) }
    assertTrue("message: ${ex.message}") { ex.message?.contains("'..' segments") == true }
  }

  @Test
  fun `absolute tool refs are rejected`() {
    val packsDir = newTempDir()
    writePack(
      packsDir, packId = "p",
      packYaml = """
        id: p
        target:
          display_name: P
          tools:
            - /etc/passwd
      """.trimIndent(),
    )

    val ex = assertFailsWith<TrailblazePackBundleException> { runGenerator(packsDir) }
    assertTrue("message: ${ex.message}") { ex.message?.contains("must not start with '/'") == true }
  }

  @Test
  fun `URL-encoded tool refs are rejected`() {
    val packsDir = newTempDir()
    writePack(
      packsDir, packId = "p",
      packYaml = """
        id: p
        target:
          display_name: P
          tools:
            - "tools/%2e%2e/escape.yaml"
      """.trimIndent(),
    )

    val ex = assertFailsWith<TrailblazePackBundleException> { runGenerator(packsDir) }
    assertTrue("message: ${ex.message}") { ex.message?.contains("URL encoding") == true }
  }

  @Test
  fun `symlink resolving outside the pack directory is rejected`() {
    // Textual rules (`..`, absolute, URL-encoded, etc.) are tested above. This case is the
    // canonical-path containment branch: the tool ref is a perfectly innocent string that
    // happens to be a SYMLINK pointing OUTSIDE the pack directory. The textual guard can't
    // see this — only the NIO `Path.startsWith` containment check rejects it.
    //
    // Skipped on filesystems that don't support symlink creation (Windows without
    // elevated perms) — `Assume.assumeTrue` reports SKIPPED rather than falsely PASSED.
    assumeTrue(
      "filesystem does not support symlink creation — skipping",
      supportsSymlinks(),
    )
    val packsDir = newTempDir()
val outsideTarget = File(newTempDir(), "outsideTool.yaml").apply {
      writeText(
        """
        script: ./tools/outside.js
        name: outsideTool
        """.trimIndent(),
      )
    }
    writePack(
      packsDir, packId = "p",
      packYaml = """
        id: p
        target:
          display_name: P
          tools:
            - tools/escape.yaml
      """.trimIndent(),
    )
    val packDir = File(packsDir, "p")
    File(packDir, "tools").mkdirs()
    Files.createSymbolicLink(
      File(packDir, "tools/escape.yaml").toPath(),
      outsideTarget.toPath(),
    )

    val ex = assertFailsWith<TrailblazePackBundleException> { runGenerator(packsDir) }
    assertTrue("message: ${ex.message}") {
      ex.message?.contains("resolves outside the pack directory") == true
    }
  }

  @Test
  fun `malformed inputSchema property value fails loudly`() {
    val packsDir = newTempDir()
    writePack(
      packsDir, packId = "p",
      packYaml = """
        id: p
        target:
          display_name: P
          tools:
            - tools/bad.yaml
      """.trimIndent(),
    )
    // YAML decodes `mode: string` as a plain string scalar at the property's value position —
    // author probably meant `mode: { type: string }` but forgot the wrapper. Runtime
    // serialization throws on this shape; the generator now matches.
    writeTool(
      packsDir, packId = "p", toolFile = "tools/bad.yaml",
      toolYaml = """
        script: ./tools/bad.js
        name: badTool
        inputSchema:
          mode: string
      """.trimIndent(),
    )

    val ex = assertFailsWith<TrailblazePackBundleException> { runGenerator(packsDir) }
    // kaml rejects the property-value type mismatch when decoding into BundlerScriptedToolProperty;
    // the bundler wraps that as "is not a valid YAML object the bundler can parse: ...".
    assertTrue("message: ${ex.message}") {
      ex.message?.contains("is not a valid YAML") == true && ex.message?.contains("bad.yaml") == true
    }
  }

  @Test
  fun `dangling scripted tool path fails with a clear message`() {
    val packsDir = newTempDir()
    writePack(
      packsDir, packId = "p",
      packYaml = """
        id: p
        target:
          display_name: P
          tools:
            - tools/missing.yaml
      """.trimIndent(),
    )

    val ex = assertFailsWith<TrailblazePackBundleException> { runGenerator(packsDir) }
    assertTrue("message: ${ex.message}") { ex.message?.contains("missing.yaml") == true }
  }

  @Test
  fun `target-tools as a non-list value fails loudly`() {
    val packsDir = newTempDir()
// Author wrote `tools: 42` instead of a list — runtime would reject; generator must too.
    writePack(
      packsDir, packId = "p",
      packYaml = """
        id: p
        target:
          display_name: P
          tools: 42
      """.trimIndent(),
    )
    val ex = assertFailsWith<TrailblazePackBundleException> { runGenerator(packsDir) }
    // kaml rejects scalar `42` when decoding into List<String>; bundler wraps the error.
    assertTrue("message: ${ex.message}") { ex.message?.contains("is not a valid YAML") == true }
  }

  @Test
  fun `target-tools containing a non-string scalar fails when the resolved path does not exist`() {
    val packsDir = newTempDir()
    writePack(
      packsDir, packId = "p",
      packYaml = """
        id: p
        target:
          display_name: P
          tools:
            - tools/valid.yaml
            - 42
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "p", toolFile = "tools/valid.yaml",
      toolYaml = """
        script: ./tools/valid.js
        name: validTool
      """.trimIndent(),
    )
    // kaml coerces the integer `42` to the string `"42"` when decoding into List<String>
    // — we don't get to reject it at the schema level. The downstream guard fires instead:
    // the bundler tries to resolve `42` as a tool ref, finds no file at that path, and
    // throws "does not exist." Different surface than under SnakeYAML (which threw at
    // schema-time), same fail-loud outcome.
    val ex = assertFailsWith<TrailblazePackBundleException> { runGenerator(packsDir) }
    assertTrue("message: ${ex.message}") { ex.message?.contains("does not exist") == true }
  }

  @Test
  fun `target as a non-map value fails loudly`() {
    val packsDir = newTempDir()
    writePack(
      packsDir, packId = "p",
      packYaml = """
        id: p
        target: 42
      """.trimIndent(),
    )
    val ex = assertFailsWith<TrailblazePackBundleException> { runGenerator(packsDir) }
    // kaml rejects scalar 42 when decoding into BundlerTarget object shape.
    assertTrue("message: ${ex.message}") { ex.message?.contains("is not a valid YAML") == true }
  }

  @Test
  fun `inputSchema as a non-map value fails loudly`() {
    val packsDir = newTempDir()
    writePack(
      packsDir, packId = "p",
      packYaml = """
        id: p
        target:
          display_name: P
          tools:
            - tools/bad.yaml
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "p", toolFile = "tools/bad.yaml",
      toolYaml = """
        script: ./tools/bad.js
        name: badInputSchemaTool
        inputSchema: "string"
      """.trimIndent(),
    )
    val ex = assertFailsWith<TrailblazePackBundleException> { runGenerator(packsDir) }
    // kaml rejects the scalar string when decoding inputSchema as Map<String, ...>.
    assertTrue("message: ${ex.message}") { ex.message?.contains("is not a valid YAML") == true }
  }

  @Test
  fun `tool YAML missing the name field fails loudly`() {
    val packsDir = newTempDir()
    writePack(
      packsDir, packId = "p",
      packYaml = """
        id: p
        target:
          display_name: P
          tools:
            - tools/nameless.yaml
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "p", toolFile = "tools/nameless.yaml",
      toolYaml = """
        script: ./tools/nameless.js
        description: Forgot to declare a name.
      """.trimIndent(),
    )
    // `name:` is now nullable on `BundlerToolFile` because the multi-tool shape (`tools:`)
    // also satisfies the descriptor — kaml no longer rejects a missing top-level `name:` at
    // decode time. The validation moves into `collectScriptedToolEntries`, which throws
    // `BlankToolName` when neither `name:` nor `tools:` is present. The new error message
    // names both shapes so authors know how to fix it.
    val ex = assertFailsWith<TrailblazePackBundleException.BlankToolName> {
      runGenerator(packsDir)
    }
    assertTrue("message: ${ex.message}") {
      ex.message?.contains("must declare either a top-level `name:`") == true &&
        ex.message?.contains("`tools:`") == true
    }
  }

  @Test
  fun `tool YAML with blank name field fails loudly`() {
    val packsDir = newTempDir()
    writePack(
      packsDir, packId = "p",
      packYaml = """
        id: p
        target:
          display_name: P
          tools:
            - tools/blank.yaml
      """.trimIndent(),
    )
    // Empty `name: ""` decodes successfully via kaml (the field is non-null but the
    // empty string is a valid String). Without explicit blank-string validation the
    // bundler would emit `"": Record<string, never>;` in tools.d.ts — valid TS,
    // semantically broken. This test guards the explicit BlankToolName check.
    writeTool(
      packsDir, packId = "p", toolFile = "tools/blank.yaml",
      toolYaml = """
        script: ./tools/blank.js
        name: ""
      """.trimIndent(),
    )
    val ex = assertFailsWith<TrailblazePackBundleException.BlankToolName> {
      runGenerator(packsDir)
    }
    assertTrue("message: ${ex.message}") {
      ex.message?.contains("blank 'name' field") == true
    }
  }

  @Test
  fun `tool YAML with whitespace-only name field fails loudly`() {
    val packsDir = newTempDir()
    writePack(
      packsDir, packId = "p",
      packYaml = """
        id: p
        target:
          display_name: P
          tools:
            - tools/spaces.yaml
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "p", toolFile = "tools/spaces.yaml",
      toolYaml = """
        script: ./tools/spaces.js
        name: "   "
      """.trimIndent(),
    )
    val ex = assertFailsWith<TrailblazePackBundleException.BlankToolName> {
      runGenerator(packsDir)
    }
    assertTrue("message: ${ex.message}") {
      ex.message?.contains("blank 'name' field") == true
    }
  }

  @Test
  fun `numeric inputSchema property key is coerced to string and rendered as quoted property`() {
    val packsDir = newTempDir()
    writePack(
      packsDir, packId = "p",
      packYaml = """
        id: p
        target:
          display_name: P
          tools:
            - tools/numkey.yaml
      """.trimIndent(),
    )
    // YAML allows numeric keys; kaml coerces them to string when decoding into
    // Map<String, …>. The bundler then emits them as quoted properties (since `1` isn't
    // a valid TS bare-identifier). Lenient behavior — author error is preserved through
    // to the generated bindings rather than rejected at schema-time. Acceptable trade
    // because the runtime YAML loader behaves the same way.
    writeTool(
      packsDir, packId = "p", toolFile = "tools/numkey.yaml",
      toolYaml = """
        script: ./tools/numkey.js
        name: numKeyTool
        inputSchema:
          1: { type: string }
      """.trimIndent(),
    )
    runGenerator(packsDir)
    val rendered = dtsFile(packsDir, "p").readText()
    assertTrue("rendered: $rendered") { rendered.contains("\"1\": string;") }
  }

  @Test
  fun `empty enum list fails loudly`() {
    val packsDir = newTempDir()
    writePack(
      packsDir, packId = "p",
      packYaml = """
        id: p
        target:
          display_name: P
          tools:
            - tools/emptyenum.yaml
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "p", toolFile = "tools/emptyenum.yaml",
      toolYaml = """
        script: ./tools/emptyenum.js
        name: emptyEnumTool
        inputSchema:
          mode: { type: string, enum: [] }
      """.trimIndent(),
    )
    val ex = assertFailsWith<TrailblazePackBundleException> { runGenerator(packsDir) }
    assertTrue("message: ${ex.message}") {
      ex.message?.contains("'enum' must contain at least one value") == true
    }
  }

  @Test
  fun `mixed-type enum is coerced to string-literal union`() {
    val packsDir = newTempDir()
    writePack(
      packsDir, packId = "p",
      packYaml = """
        id: p
        target:
          display_name: P
          tools:
            - tools/mixed.yaml
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "p", toolFile = "tools/mixed.yaml",
      toolYaml = """
        script: ./tools/mixed.js
        name: mixedEnumTool
        inputSchema:
          mode: { type: string, enum: [fast, 42] }
      """.trimIndent(),
    )
    // kaml coerces the integer `42` to `"42"` when decoding enum as List<String>. The
    // previous SnakeYAML-based bundler caught this with an explicit "must all be strings"
    // error; under kaml the lenient coercion produces a `"fast" | "42"` union. Acceptable —
    // the runtime YAML loader behaves the same way, and author intent (a string-y enum) is
    // preserved.
    runGenerator(packsDir)
    val rendered = dtsFile(packsDir, "p").readText()
    assertTrue("rendered: $rendered") { rendered.contains("mode: \"fast\" | \"42\";") }
  }

  @Test
  fun `non-list enum value fails loudly`() {
    val packsDir = newTempDir()
    writePack(
      packsDir, packId = "p",
      packYaml = """
        id: p
        target:
          display_name: P
          tools:
            - tools/scalarenum.yaml
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "p", toolFile = "tools/scalarenum.yaml",
      toolYaml = """
        script: ./tools/scalarenum.js
        name: scalarEnumTool
        inputSchema:
          mode: { type: string, enum: fast }
      """.trimIndent(),
    )
    val ex = assertFailsWith<TrailblazePackBundleException> { runGenerator(packsDir) }
    // kaml rejects the scalar when decoding enum as List<String>?.
    assertTrue("message: ${ex.message}") { ex.message?.contains("is not a valid YAML") == true }
  }

  @Test
  fun `orphan cleanup leaves dts files at non-standard layouts alone`() {
    // The orphan-cleanup filter requires the file shape `<packDir>/tools/.trailblaze/tools.d.ts`
    // — verifying parent==`.trailblaze` AND grandparent==`tools`. A file at any other depth
    // that happens to be named `tools.d.ts` inside a `.trailblaze/` directory (e.g. an
    // author-created `unrelated/.trailblaze/tools.d.ts`) must NOT be deleted by cleanup.
    // Without the grandparent check, a future refactor that drops it would silently start
    // sweeping files outside the bundler's own output shape.
    val packsDir = newTempDir()
    // A pack is present so `generate()` runs the cleanup pass.
    writePack(
      packsDir, packId = "p",
      packYaml = """
        id: p
        target:
          display_name: P
          tools: []
      """.trimIndent(),
    )
    // Plant a file matching the bundler's filename + `.trailblaze` parent, but with a
    // grandparent that is NOT `tools/`. Cleanup must leave it alone.
    val outOfShape = File(packsDir, "unrelated/.trailblaze/tools.d.ts")
    outOfShape.parentFile.mkdirs()
    outOfShape.writeText("// author-created, not bundler-managed\nexport {};\n")

    runGenerator(packsDir)

    assertTrue("non-bundler `.trailblaze/tools.d.ts` must not be swept: $outOfShape") {
      outOfShape.exists()
    }
  }

  // ---- Fixtures ----

  private fun newTempDir(): File = createTempDirectory("trailblaze-bindings-test").toFile().also(tempDirs::add)

  private fun     writePack(packsDir: File, packId: String, packYaml: String) {
    val dir = File(packsDir, packId).apply { mkdirs() }
    File(dir, "pack.yaml").writeText(packYaml)
  }

  private fun writeTool(packsDir: File, packId: String, toolFile: String, toolYaml: String) {
    val out = File(File(packsDir, packId), toolFile)
    out.parentFile.mkdirs()
    out.writeText(toolYaml)
  }

  private fun runGenerator(packsDir: File) {
    TrailblazePackBundler(packsDir = packsDir).generate()
  }

  /** Resolves the per-pack `tools.d.ts` location the bundler now writes to. */
  private fun dtsFile(packsDir: File, packId: String): File =
    File(packsDir, "$packId/tools/.trailblaze/tools.d.ts")

  /**
   * Probe for symlink-creation support — `Files.createSymbolicLink` requires elevated
   * permissions on Windows. Used by the canonical-containment test to skip cleanly on
   * environments that can't exercise the symlink path.
   */
  private fun supportsSymlinks(): Boolean = try {
    val probeDir = createTempDirectory("symlink-probe").toFile().also(tempDirs::add)
    val target = File(probeDir, "target").apply { writeText("x") }
    val link = File(probeDir, "link").toPath()
    Files.createSymbolicLink(link, target.toPath())
    true
  } catch (_: UnsupportedOperationException) {
    false
  } catch (_: java.io.IOException) {
    false
  }
}
