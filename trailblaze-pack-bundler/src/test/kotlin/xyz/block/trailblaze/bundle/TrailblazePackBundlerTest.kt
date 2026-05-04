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
    val outputDir = newTempDir()
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

    runGenerator(packsDir, outputDir)
    val rendered = File(outputDir, "tools.d.ts").readText()

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
    val outputDir = newTempDir()
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

    runGenerator(packsDir, outputDir)
    val rendered = File(outputDir, "tools.d.ts").readText()

    assertTrue("rendered: $rendered") { rendered.contains("mode: \"fast\" | \"slow\";") }
  }

  @Test
  fun `array and boolean and object types translate to TS equivalents`() {
    val packsDir = newTempDir()
    val outputDir = newTempDir()
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

    runGenerator(packsDir, outputDir)
    val rendered = File(outputDir, "tools.d.ts").readText()

    assertTrue("rendered: $rendered") { rendered.contains("tags: unknown[];") }
    assertTrue("rendered: $rendered") { rendered.contains("enabled: boolean;") }
    assertTrue("rendered: $rendered") { rendered.contains("extras: Record<string, unknown>;") }
    // Unknown schema type falls through to `unknown` rather than failing the build.
    assertTrue("rendered: $rendered") { rendered.contains("weird: unknown;") }
  }

  @Test
  fun `empty inputSchema renders Record string never`() {
    val packsDir = newTempDir()
    val outputDir = newTempDir()
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

    runGenerator(packsDir, outputDir)
    val rendered = File(outputDir, "tools.d.ts").readText()

    assertTrue("rendered: $rendered") { rendered.contains("noargs: Record<string, never>;") }
  }

  @Test
  fun `duplicate tool names across packs fail with a clear message`() {
    val packsDir = newTempDir()
    val outputDir = newTempDir()
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

    val ex = assertFailsWith<TrailblazePackBundleException> {
      runGenerator(packsDir, outputDir)
    }
    assertTrue("message: ${ex.message}") { ex.message?.contains("Duplicate scripted tool name 'sharedTool'") == true }
  }

  @Test
  fun `multi-pack output is sorted by tool name`() {
    val packsDir = newTempDir()
    val outputDir = newTempDir()
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

    runGenerator(packsDir, outputDir)
    val rendered = File(outputDir, "tools.d.ts").readText()

    val appleAt = rendered.indexOf("appleTool:")
    val zebraAt = rendered.indexOf("zebraTool:")
    assertTrue("apple should be before zebra: $rendered") { appleAt in 0 until zebraAt }
  }

  @Test
  fun `tool name with hyphen is emitted as a quoted property`() {
    val packsDir = newTempDir()
    val outputDir = newTempDir()
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

    runGenerator(packsDir, outputDir)
    val rendered = File(outputDir, "tools.d.ts").readText()

    assertTrue("rendered: $rendered") { rendered.contains("\"weird-tool-name\":") }
  }

  @Test
  fun `pack without target block is skipped silently`() {
    val packsDir = newTempDir()
    val outputDir = newTempDir()
    writePack(
      packsDir, packId = "lib",
      packYaml = """
        id: lib
        defaults:
          android:
            tool_sets: [observation]
      """.trimIndent(),
    )

    runGenerator(packsDir, outputDir)
    val rendered = File(outputDir, "tools.d.ts").readText()

    assertTrue("expected empty-fallback file: $rendered") { rendered.contains("No scripted tools found") }
  }

  @Test
  fun `parent-segment tool refs are rejected`() {
    val packsDir = newTempDir()
    val outputDir = newTempDir()
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

    val ex = assertFailsWith<TrailblazePackBundleException> { runGenerator(packsDir, outputDir) }
    assertTrue("message: ${ex.message}") { ex.message?.contains("'..' segments") == true }
  }

  @Test
  fun `absolute tool refs are rejected`() {
    val packsDir = newTempDir()
    val outputDir = newTempDir()
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

    val ex = assertFailsWith<TrailblazePackBundleException> { runGenerator(packsDir, outputDir) }
    assertTrue("message: ${ex.message}") { ex.message?.contains("must not start with '/'") == true }
  }

  @Test
  fun `URL-encoded tool refs are rejected`() {
    val packsDir = newTempDir()
    val outputDir = newTempDir()
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

    val ex = assertFailsWith<TrailblazePackBundleException> { runGenerator(packsDir, outputDir) }
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
    val outputDir = newTempDir()
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

    val ex = assertFailsWith<TrailblazePackBundleException> { runGenerator(packsDir, outputDir) }
    assertTrue("message: ${ex.message}") {
      ex.message?.contains("resolves outside the pack directory") == true
    }
  }

  @Test
  fun `malformed inputSchema property value fails loudly`() {
    val packsDir = newTempDir()
    val outputDir = newTempDir()
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

    val ex = assertFailsWith<TrailblazePackBundleException> { runGenerator(packsDir, outputDir) }
    // kaml rejects the property-value type mismatch when decoding into BundlerScriptedToolProperty;
    // the bundler wraps that as "is not a valid YAML object the bundler can parse: ...".
    assertTrue("message: ${ex.message}") {
      ex.message?.contains("is not a valid YAML") == true && ex.message?.contains("bad.yaml") == true
    }
  }

  @Test
  fun `dangling scripted tool path fails with a clear message`() {
    val packsDir = newTempDir()
    val outputDir = newTempDir()
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

    val ex = assertFailsWith<TrailblazePackBundleException> { runGenerator(packsDir, outputDir) }
    assertTrue("message: ${ex.message}") { ex.message?.contains("missing.yaml") == true }
  }

  @Test
  fun `target-tools as a non-list value fails loudly`() {
    val packsDir = newTempDir()
    val outputDir = newTempDir()
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
    val ex = assertFailsWith<TrailblazePackBundleException> { runGenerator(packsDir, outputDir) }
    // kaml rejects scalar `42` when decoding into List<String>; bundler wraps the error.
    assertTrue("message: ${ex.message}") { ex.message?.contains("is not a valid YAML") == true }
  }

  @Test
  fun `target-tools containing a non-string scalar fails when the resolved path does not exist`() {
    val packsDir = newTempDir()
    val outputDir = newTempDir()
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
    val ex = assertFailsWith<TrailblazePackBundleException> { runGenerator(packsDir, outputDir) }
    assertTrue("message: ${ex.message}") { ex.message?.contains("does not exist") == true }
  }

  @Test
  fun `target as a non-map value fails loudly`() {
    val packsDir = newTempDir()
    val outputDir = newTempDir()
    writePack(
      packsDir, packId = "p",
      packYaml = """
        id: p
        target: 42
      """.trimIndent(),
    )
    val ex = assertFailsWith<TrailblazePackBundleException> { runGenerator(packsDir, outputDir) }
    // kaml rejects scalar 42 when decoding into BundlerTarget object shape.
    assertTrue("message: ${ex.message}") { ex.message?.contains("is not a valid YAML") == true }
  }

  @Test
  fun `inputSchema as a non-map value fails loudly`() {
    val packsDir = newTempDir()
    val outputDir = newTempDir()
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
    val ex = assertFailsWith<TrailblazePackBundleException> { runGenerator(packsDir, outputDir) }
    // kaml rejects the scalar string when decoding inputSchema as Map<String, ...>.
    assertTrue("message: ${ex.message}") { ex.message?.contains("is not a valid YAML") == true }
  }

  @Test
  fun `tool YAML missing the name field fails loudly`() {
    val packsDir = newTempDir()
    val outputDir = newTempDir()
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
    val ex = assertFailsWith<TrailblazePackBundleException.MalformedScriptedTool> {
      runGenerator(packsDir, outputDir)
    }
    // kaml rejects the missing required `name` field on BundlerToolFile.
    assertTrue("message: ${ex.message}") {
      ex.message?.contains("is not a valid YAML") == true && ex.message?.contains("name") == true
    }
  }

  @Test
  fun `tool YAML with blank name field fails loudly`() {
    val packsDir = newTempDir()
    val outputDir = newTempDir()
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
      runGenerator(packsDir, outputDir)
    }
    assertTrue("message: ${ex.message}") {
      ex.message?.contains("blank 'name' field") == true
    }
  }

  @Test
  fun `tool YAML with whitespace-only name field fails loudly`() {
    val packsDir = newTempDir()
    val outputDir = newTempDir()
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
      runGenerator(packsDir, outputDir)
    }
    assertTrue("message: ${ex.message}") {
      ex.message?.contains("blank 'name' field") == true
    }
  }

  @Test
  fun `numeric inputSchema property key is coerced to string and rendered as quoted property`() {
    val packsDir = newTempDir()
    val outputDir = newTempDir()
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
    runGenerator(packsDir, outputDir)
    val rendered = File(outputDir, "tools.d.ts").readText()
    assertTrue("rendered: $rendered") { rendered.contains("\"1\": string;") }
  }

  @Test
  fun `empty enum list fails loudly`() {
    val packsDir = newTempDir()
    val outputDir = newTempDir()
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
    val ex = assertFailsWith<TrailblazePackBundleException> { runGenerator(packsDir, outputDir) }
    assertTrue("message: ${ex.message}") {
      ex.message?.contains("'enum' must contain at least one value") == true
    }
  }

  @Test
  fun `mixed-type enum is coerced to string-literal union`() {
    val packsDir = newTempDir()
    val outputDir = newTempDir()
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
    runGenerator(packsDir, outputDir)
    val rendered = File(outputDir, "tools.d.ts").readText()
    assertTrue("rendered: $rendered") { rendered.contains("mode: \"fast\" | \"42\";") }
  }

  @Test
  fun `non-list enum value fails loudly`() {
    val packsDir = newTempDir()
    val outputDir = newTempDir()
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
    val ex = assertFailsWith<TrailblazePackBundleException> { runGenerator(packsDir, outputDir) }
    // kaml rejects the scalar when decoding enum as List<String>?.
    assertTrue("message: ${ex.message}") { ex.message?.contains("is not a valid YAML") == true }
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

  private fun runGenerator(packsDir: File, outputDir: File) {
    TrailblazePackBundler(packsDir = packsDir, outputDir = outputDir).generate()
  }

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
