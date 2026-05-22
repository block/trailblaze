package xyz.block.trailblaze.bundle

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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
 *  - Cross-pack-closure tool-name collisions fail loudly (declaration-merging would silently
 *    pick one shape otherwise).
 *  - Missing `inputSchema` is OK — emits a parameterless entry.
 *  - JSDoc `* /` sequences inside descriptions are escaped so they don't close the comment.
 *  - `target.tools:` is a list of **tool names** auto-discovered from each `.yaml` file
 *    under `<pack>/tools/` (not file paths) — duplicate-name detection, unknown-name
 *    resolution errors, and the operational-suffix exclude list (`.tool.yaml`, etc.) all
 *    live in the bundler's discovery walk.
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
            - alpha_doThing
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "alpha", toolFile = "tools/alpha_doThing.yaml",
      toolYaml = """
        script: ./tools/alpha_doThing.ts
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
            - withEnum
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "p", toolFile = "tools/withEnum.yaml",
      toolYaml = """
        script: ./tools/withEnum.ts
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
            - widget
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "p", toolFile = "tools/widget.yaml",
      toolYaml = """
        script: ./tools/widget.ts
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
            - noargs
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "p", toolFile = "tools/noargs.yaml",
      toolYaml = """
        script: ./tools/noargs.ts
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
            - sharedTool
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "alpha", toolFile = "tools/shared.yaml",
      toolYaml = """
        script: ./tools/shared.ts
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
            - sharedTool
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "beta", toolFile = "tools/shared.yaml",
      toolYaml = """
        script: ./tools/shared.ts
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
  fun `same tool name registered by two descriptor files in one pack fails with a clear message`() {
    // Per-pack discovery walks every `<pack>/tools/*.yaml` and indexes by `name:`. Two files
    // claiming the same name (typo, accidental copy-paste) would silently shadow at runtime
    // without this guard.
    val packsDir = newTempDir()
    writePack(
      packsDir, packId = "p",
      packYaml = """
        id: p
        target:
          display_name: P
          tools:
            - dupTool
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "p", toolFile = "tools/a.yaml",
      toolYaml = """
        script: ./tools/a.ts
        name: dupTool
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "p", toolFile = "tools/b.yaml",
      toolYaml = """
        script: ./tools/b.ts
        name: dupTool
      """.trimIndent(),
    )

    val ex = assertFailsWith<TrailblazePackBundleException.DuplicateToolName> { runGenerator(packsDir) }
    assertTrue("message: ${ex.message}") {
      ex.message?.contains("'dupTool'") == true &&
        ex.message?.contains("a.yaml") == true &&
        ex.message?.contains("b.yaml") == true
    }
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
            - zebraTool
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "z", toolFile = "tools/zebra.yaml",
      toolYaml = """
        script: ./tools/zebra.ts
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
            - appleTool
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "a", toolFile = "tools/apple.yaml",
      toolYaml = """
        script: ./tools/apple.ts
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
            - libTool
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "lib", toolFile = "tools/libTool.yaml",
      toolYaml = """
        script: ./tools/libTool.ts
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
            - appTool
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "app", toolFile = "tools/appTool.yaml",
      toolYaml = """
        script: ./tools/appTool.ts
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
            - cTool
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "c", toolFile = "tools/cTool.yaml",
      toolYaml = """
        script: ./tools/cTool.ts
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
            - bTool
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "b", toolFile = "tools/bTool.yaml",
      toolYaml = """
        script: ./tools/bTool.ts
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
            - aTool
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "a", toolFile = "tools/aTool.yaml",
      toolYaml = """
        script: ./tools/aTool.ts
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
            - dTool
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "d", toolFile = "tools/dTool.yaml",
      toolYaml = """
        script: ./tools/dTool.ts
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
            - rootTool
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "root", toolFile = "tools/rootTool.yaml",
      toolYaml = """
        script: ./tools/rootTool.ts
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
            - pTool
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "p", toolFile = "tools/pTool.yaml",
      toolYaml = """
        script: ./tools/pTool.ts
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
            - aTool
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "a", toolFile = "tools/aTool.yaml",
      toolYaml = """
        script: ./tools/aTool.ts
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
            - pTool
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "p", toolFile = "tools/pTool.yaml",
      toolYaml = """
        script: ./tools/pTool.ts
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
            - clashingName
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "lib", toolFile = "tools/clash.yaml",
      toolYaml = """
        script: ./tools/clash.ts
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
            - clashingName
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "consumer", toolFile = "tools/myclash.yaml",
      toolYaml = """
        script: ./tools/myclash.ts
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
            - weird-tool-name
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "p", toolFile = "tools/hyphen.yaml",
      toolYaml = """
        script: ./tools/hyphen.ts
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
  fun `target tools entry that doesn't match any discovered descriptor fails loudly`() {
    // `target.tools:` lists a name that no descriptor under `<pack>/tools/` declares. The
    // discovery walk surfaces the available names in the error so the author can spot
    // typos quickly.
    val packsDir = newTempDir()
    writePack(
      packsDir, packId = "p",
      packYaml = """
        id: p
        target:
          display_name: P
          tools:
            - missingName
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "p", toolFile = "tools/actuallyHere.yaml",
      toolYaml = """
        script: ./tools/actuallyHere.ts
        name: actuallyHere
      """.trimIndent(),
    )

    val ex = assertFailsWith<TrailblazePackBundleException.UnknownScriptedToolName> {
      runGenerator(packsDir)
    }
    assertTrue("message: ${ex.message}") {
      ex.message?.contains("'missingName'") == true &&
        ex.message?.contains("actuallyHere") == true
    }
  }

  @Test
  fun `target tools entry that points at an empty tools directory surfaces the empty registry`() {
    val packsDir = newTempDir()
    writePack(
      packsDir, packId = "p",
      packYaml = """
        id: p
        target:
          display_name: P
          tools:
            - nothingHere
      """.trimIndent(),
    )

    val ex = assertFailsWith<TrailblazePackBundleException.UnknownScriptedToolName> {
      runGenerator(packsDir)
    }
    assertTrue("message: ${ex.message}") {
      ex.message?.contains("'nothingHere'") == true &&
        ex.message?.contains("No scripted-tool descriptors discovered") == true
    }
  }

  @Test
  fun `target tools entry listed twice fails loudly`() {
    val packsDir = newTempDir()
    writePack(
      packsDir, packId = "p",
      packYaml = """
        id: p
        target:
          display_name: P
          tools:
            - dupName
            - dupName
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "p", toolFile = "tools/dupName.yaml",
      toolYaml = """
        script: ./tools/dupName.ts
        name: dupName
      """.trimIndent(),
    )

    val ex = assertFailsWith<TrailblazePackBundleException.DuplicateToolName> {
      runGenerator(packsDir)
    }
    assertTrue("message: ${ex.message}") {
      ex.message?.contains("'dupName'") == true &&
        ex.message?.contains("more than once") == true
    }
  }

  @Test
  fun `operational tool YAMLs in tools dir are skipped during scripted-tool discovery`() {
    // `tools/foo.tool.yaml` is a pure-YAML operational tool (auto-discovered from the same
    // directory but bound by suffix, not by name). It must not appear in the scripted-tool
    // registry — otherwise the bundler would try to decode it as a `PackScriptedToolFile`
    // and either fail or shadow a legitimate scripted-tool name.
    val packsDir = newTempDir()
    writePack(
      packsDir, packId = "p",
      packYaml = """
        id: p
        target:
          display_name: P
          tools:
            - scriptedTool
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "p", toolFile = "tools/scripted.yaml",
      toolYaml = """
        script: ./tools/scripted.ts
        name: scriptedTool
      """.trimIndent(),
    )
    // Operational tool YAML lives in the same dir but uses the reserved `.tool.yaml`
    // suffix. The scripted-tool discovery walk skips it; the operational walk picks it up
    // separately (out of scope for this bundler).
    writeTool(
      packsDir, packId = "p", toolFile = "tools/legacy.tool.yaml",
      toolYaml = """
        # A pure-YAML composed tool — unrelated to the scripted-tool descriptor shape.
        id: legacyOperationalTool
        description: Not a scripted tool.
      """.trimIndent(),
    )

    runGenerator(packsDir)
    val rendered = dtsFile(packsDir, "p").readText()
    assertTrue("rendered: $rendered") { rendered.contains("scriptedTool:") }
    assertTrue("operational tool must not surface as a scripted-tool entry: $rendered") {
      !rendered.contains("legacyOperationalTool")
    }
  }

  @Test
  fun `multi-tool descriptor registers every entry name and resolves each individually`() {
    // One `.ts` module exports two functions; the descriptor lists both under `tools:`.
    // `target.tools:` references each name; the bundler emits one entry per name.
    val packsDir = newTempDir()
    writePack(
      packsDir, packId = "m",
      packYaml = """
        id: m
        target:
          display_name: M
          tools:
            - firstMulti
            - secondMulti
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "m", toolFile = "tools/multi.yaml",
      toolYaml = """
        script: ./tools/multi.ts
        tools:
          - name: firstMulti
            description: First entry.
            inputSchema:
              a: { type: string }
          - name: secondMulti
            description: Second entry.
            inputSchema:
              b: { type: number }
      """.trimIndent(),
    )

    runGenerator(packsDir)
    val rendered = dtsFile(packsDir, "m").readText()
    assertTrue("firstMulti present: $rendered") { rendered.contains("firstMulti: {") }
    assertTrue("secondMulti present: $rendered") { rendered.contains("secondMulti: {") }
    assertTrue("a: string;: $rendered") { rendered.contains("a: string;") }
    assertTrue("b: number;: $rendered") { rendered.contains("b: number;") }
  }

  @Test
  fun `malformed inputSchema property value is skipped and a target-tools reference surfaces unknown-name`() {
    // Lead-dev review #2 (round 2): the discovery walk now wraps decode in try/log/skip so
    // a single malformed descriptor doesn't tank unrelated packs. The referenced-name
    // resolution downstream surfaces the failure clearly when `target.tools:` names a tool
    // from a skipped file — the author still sees a clear error.
    val packsDir = newTempDir()
    writePack(
      packsDir, packId = "p",
      packYaml = """
        id: p
        target:
          display_name: P
          tools:
            - badTool
      """.trimIndent(),
    )
    // YAML decodes `mode: string` as a plain string scalar at the property's value position —
    // author probably meant `mode: { type: string }` but forgot the wrapper. The descriptor
    // is now skipped at discovery time (with a stderr warning) rather than fatally aborting
    // the bundler; the `target.tools:` reference then surfaces UnknownScriptedToolName.
    writeTool(
      packsDir, packId = "p", toolFile = "tools/bad.yaml",
      toolYaml = """
        script: ./tools/bad.ts
        name: badTool
        inputSchema:
          mode: string
      """.trimIndent(),
    )

    val ex = assertFailsWith<TrailblazePackBundleException.UnknownScriptedToolName> { runGenerator(packsDir) }
    assertTrue("message: ${ex.message}") {
      ex.message?.contains("'badTool'") == true
    }
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
  fun `target-tools containing a non-string scalar fails because the coerced name has no descriptor`() {
    val packsDir = newTempDir()
    writePack(
      packsDir, packId = "p",
      packYaml = """
        id: p
        target:
          display_name: P
          tools:
            - validTool
            - 42
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "p", toolFile = "tools/valid.yaml",
      toolYaml = """
        script: ./tools/valid.ts
        name: validTool
      """.trimIndent(),
    )
    // kaml coerces the integer `42` to the string `"42"` when decoding into List<String>.
    // The downstream guard fires when the name-keyed registry has no entry called "42" —
    // surfacing as an UnknownScriptedToolName with the discovered name list embedded.
    val ex = assertFailsWith<TrailblazePackBundleException.UnknownScriptedToolName> {
      runGenerator(packsDir)
    }
    assertTrue("message: ${ex.message}") {
      ex.message?.contains("'42'") == true &&
        ex.message?.contains("validTool") == true
    }
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
  fun `inputSchema as a non-map value is skipped and a target-tools reference surfaces unknown-name`() {
    // Same skip-and-log behavior as the malformed-property-value test — the descriptor's
    // decode fails (kaml rejects `inputSchema: "string"` when the typed model expects a map),
    // discovery skips with a stderr warning, and `target.tools:` surfaces UnknownScriptedToolName.
    val packsDir = newTempDir()
    writePack(
      packsDir, packId = "p",
      packYaml = """
        id: p
        target:
          display_name: P
          tools:
            - badInputSchemaTool
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "p", toolFile = "tools/bad.yaml",
      toolYaml = """
        script: ./tools/bad.ts
        name: badInputSchemaTool
        inputSchema: "string"
      """.trimIndent(),
    )
    val ex = assertFailsWith<TrailblazePackBundleException.UnknownScriptedToolName> { runGenerator(packsDir) }
    assertTrue("message: ${ex.message}") { ex.message?.contains("'badInputSchemaTool'") == true }
  }

  @Test
  fun `tool YAML missing the name field is skipped at discovery and target-tools reference surfaces unknown-name`() {
    // Lead-dev review #2 (round 2): a descriptor that declares neither `name:` nor `tools:`
    // is now treated as a WIP file rather than a fatal error — discovery skips it with a
    // stderr warning. If `target.tools:` references the tool that would have lived in that
    // file, the resulting UnknownScriptedToolName surfaces the failure clearly.
    val packsDir = newTempDir()
    writePack(
      packsDir, packId = "p",
      packYaml = """
        id: p
        target:
          display_name: P
          tools:
            - anyName
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "p", toolFile = "tools/nameless.yaml",
      toolYaml = """
        script: ./tools/nameless.ts
        description: Forgot to declare a name.
      """.trimIndent(),
    )
    val ex = assertFailsWith<TrailblazePackBundleException.UnknownScriptedToolName> {
      runGenerator(packsDir)
    }
    assertTrue("message: ${ex.message}") { ex.message?.contains("'anyName'") == true }
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
            - anyName
      """.trimIndent(),
    )
    // Empty `name: ""` decodes successfully via kaml (the field is non-null but the
    // empty string is a valid String). Without explicit blank-string validation the
    // bundler would emit `"": Record<string, never>;` in tools.d.ts — valid TS,
    // semantically broken. This test guards the explicit BlankToolName check.
    writeTool(
      packsDir, packId = "p", toolFile = "tools/blank.yaml",
      toolYaml = """
        script: ./tools/blank.ts
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
            - anyName
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "p", toolFile = "tools/spaces.yaml",
      toolYaml = """
        script: ./tools/spaces.ts
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
            - numKeyTool
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
        script: ./tools/numkey.ts
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
            - emptyEnumTool
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "p", toolFile = "tools/emptyenum.yaml",
      toolYaml = """
        script: ./tools/emptyenum.ts
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
            - mixedEnumTool
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "p", toolFile = "tools/mixed.yaml",
      toolYaml = """
        script: ./tools/mixed.ts
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
  fun `non-list enum value is skipped and a target-tools reference surfaces unknown-name`() {
    val packsDir = newTempDir()
    writePack(
      packsDir, packId = "p",
      packYaml = """
        id: p
        target:
          display_name: P
          tools:
            - scalarEnumTool
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "p", toolFile = "tools/scalarenum.yaml",
      toolYaml = """
        script: ./tools/scalarenum.ts
        name: scalarEnumTool
        inputSchema:
          mode: { type: string, enum: fast }
      """.trimIndent(),
    )
    val ex = assertFailsWith<TrailblazePackBundleException.UnknownScriptedToolName> { runGenerator(packsDir) }
    assertTrue("message: ${ex.message}") { ex.message?.contains("'scalarEnumTool'") == true }
  }

  @Test
  fun `scripted tool with a js script field is rejected with the migration hint`() {
    // Pack-typing locks the authoring language to TypeScript so per-pack `client.tools.<name>`
    // codegen stays meaningful — the bundler can only statically analyse a `.ts` file. A
    // descriptor that still points at a `.js` file would silently produce typed bindings
    // whose runtime shape diverges from the `.d.ts` signature; better to refuse the bundle.
    val packsDir = newTempDir()
    writePack(
      packsDir, packId = "legacyJs",
      packYaml = """
        id: legacyJs
        target:
          display_name: LegacyJs
          tools:
            - legacyJsTool
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "legacyJs", toolFile = "tools/legacyJsTool.yaml",
      toolYaml = """
        script: ./legacyJsTool.js
        name: legacyJsTool
      """.trimIndent(),
    )

    val ex = assertFailsWith<TrailblazePackBundleException.JsToolFileNotAllowed> {
      runGenerator(packsDir)
    }
    // The author needs four pieces of info to fix the bundle: which tool, which pack, the
    // offending path, and the rename target. Assert each surfaces in the error message so a
    // future refactor that loses one piece fails this test rather than silently degrading the
    // diagnostic.
    assertTrue("message: ${ex.message}") { ex.message?.contains("legacyJsTool") == true }
    assertTrue("message: ${ex.message}") { ex.message?.contains("'legacyJs'") == true }
    assertTrue("message: ${ex.message}") { ex.message?.contains("./legacyJsTool.js") == true }
    assertTrue("message: ${ex.message}") { ex.message?.contains("./legacyJsTool.ts") == true }
    assertTrue("message: ${ex.message}") {
      ex.message?.contains("TypeScript is the only supported authoring language") == true
    }
  }

  @Test
  fun `scripted tool with an mjs or cjs script field is also rejected`() {
    // Same policy as `.js` — both `.mjs` (ES modules) and `.cjs` (CommonJS) are JavaScript
    // sources that bypass the TypeScript-only codegen contract. Two-pack fixture, one
    // extension each, keeps the assertion narrow.
    listOf("mjs", "cjs").forEach { ext ->
      val packsDir = newTempDir()
      writePack(
        packsDir, packId = "p_$ext",
        packYaml = """
          id: p_$ext
          target:
            display_name: P
            tools:
              - tool
        """.trimIndent(),
      )
      writeTool(
        packsDir, packId = "p_$ext", toolFile = "tools/tool.yaml",
        toolYaml = """
          script: ./tool.$ext
          name: tool
        """.trimIndent(),
      )

      val ex = assertFailsWith<TrailblazePackBundleException.JsToolFileNotAllowed> {
        runGenerator(packsDir)
      }
      assertTrue("ext=$ext message: ${ex.message}") { ex.message?.contains(".$ext") == true }
      assertTrue("ext=$ext message: ${ex.message}") { ex.message?.contains("./tool.ts") == true }
    }
  }

  @Test
  fun `multi-tool descriptor with a js script field is rejected and names the first member`() {
    // Multi-tool descriptors share one `script:` source — `script:` applies to the whole
    // file. The error message's `displayName` falls back from the top-level `name:` (which
    // is absent in multi-tool shape) to the first entry's `name:`. This test covers that
    // fallback branch — otherwise it's dead code.
    val packsDir = newTempDir()
    writePack(
      packsDir, packId = "multi",
      packYaml = """
        id: multi
        target:
          display_name: Multi
          tools:
            - multiToolFirst
            - multiToolSecond
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "multi", toolFile = "tools/multiTool.yaml",
      toolYaml = """
        script: ./multiTool.js
        tools:
          - name: multiToolFirst
            description: First member.
          - name: multiToolSecond
            description: Second member.
      """.trimIndent(),
    )

    val ex = assertFailsWith<TrailblazePackBundleException.JsToolFileNotAllowed> {
      runGenerator(packsDir)
    }
    assertTrue("message: ${ex.message}") { ex.message?.contains("multiToolFirst") == true }
    assertTrue("message: ${ex.message}") { ex.message?.contains("'multi'") == true }
    assertTrue("message: ${ex.message}") { ex.message?.contains("./multiTool.js") == true }
    assertTrue("message: ${ex.message}") { ex.message?.contains("./multiTool.ts") == true }
  }

  @Test
  fun `js rejection falls back to the descriptor file name when both name and tools are absent`() {
    // The rejection check runs before the BlankToolName guard, so a descriptor with
    // `script: foo.js` and neither `name:` nor `tools:` reaches the third arm of the
    // displayName fallback (`toolFile.nameWithoutExtension`). This case is the only path
    // that exercises it — without coverage the comment claiming the fallback exists is
    // unverifiable.
    val packsDir = newTempDir()
    // Any non-empty `target.tools:` entry triggers the discovery walk, which is where the
    // JS rejection fires. The entry doesn't have to resolve — the rejection throws inside
    // `buildScriptedToolRegistry` (during the per-descriptor decode loop) BEFORE the
    // name-lookup step gets a chance to surface UnknownScriptedToolName.
    writePack(
      packsDir, packId = "namelessPack",
      packYaml = """
        id: namelessPack
        target:
          display_name: Nameless
          tools:
            - placeholderToExerciseDiscovery
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "namelessPack", toolFile = "tools/nameless.yaml",
      toolYaml = """
        script: ./nameless.js
      """.trimIndent(),
    )

    val ex = assertFailsWith<TrailblazePackBundleException.JsToolFileNotAllowed> {
      runGenerator(packsDir)
    }
    // The file-name fallback surfaces as the displayName, NOT the BlankToolName message —
    // confirming the .js policy fires ahead of the blank-name check (which the author
    // would otherwise see only after migrating their file extension).
    assertTrue("message: ${ex.message}") { ex.message?.contains("'nameless'") == true }
    assertTrue("message: ${ex.message}") { ex.message?.contains("'namelessPack'") == true }
    assertTrue("message: ${ex.message}") { ex.message?.contains("./nameless.js") == true }
  }

  @Test
  fun `scripted tool with a ts script field is accepted`() {
    // Happy-path counterpart: the same fixture as the rejection test but pointing at a `.ts`
    // file should produce a valid `.d.ts`. Guards against a regression where the suffix
    // matcher matches too aggressively (e.g. starts rejecting `.ts` because of a typo'd
    // `endsWith`).
    val packsDir = newTempDir()
    writePack(
      packsDir, packId = "okPack",
      packYaml = """
        id: okPack
        target:
          display_name: OK
          tools:
            - okTool
      """.trimIndent(),
    )
    writeTool(
      packsDir, packId = "okPack", toolFile = "tools/okTool.yaml",
      toolYaml = """
        script: ./okTool.ts
        name: okTool
      """.trimIndent(),
    )

    runGenerator(packsDir)
    val rendered = dtsFile(packsDir, "okPack").readText()
    assertTrue("rendered: $rendered") { rendered.contains("okTool: Record<string, never>;") }
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

  private fun writePack(packsDir: File, packId: String, packYaml: String) {
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
}
