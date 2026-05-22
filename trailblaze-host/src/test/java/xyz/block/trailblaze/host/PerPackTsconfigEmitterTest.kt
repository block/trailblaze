package xyz.block.trailblaze.host

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import xyz.block.trailblaze.config.AppTargetYamlConfig
import xyz.block.trailblaze.config.project.PackSource
import xyz.block.trailblaze.config.project.PackTargetConfig
import xyz.block.trailblaze.config.project.ResolvedPack
import xyz.block.trailblaze.config.project.TrailblazePackManifest

/**
 * Tests for [PerPackTsconfigEmitter] — the framework-owned writer for per-pack
 * `tools/tsconfig.json` and pack-root `.gitignore` artifacts.
 *
 * Coverage:
 *  - Self-contained tsconfig render: no `extends:`, every compiler option inlined,
 *    `paths` mapping points at the workspace SDK declaration bundle at the correct
 *    relative path.
 *  - npm-portability: moving a pack to a different relative-to-workspace depth and
 *    re-emitting produces an updated tsconfig with the corrected relative path.
 *  - Idempotency — second emit on unchanged input leaves byte content identical.
 *  - Banner-gated overwrite: a hand-authored tsconfig (no framework banner) is
 *    preserved verbatim; a framework-bannered tsconfig is rewritten freely.
 *  - Missing workspace SDK bundle throws with a clear message.
 *  - Generated `include` glob covers both `.ts` and `.js` (workspace inherits
 *    `allowJs: true`, so JS-authoring packs still get typed bindings).
 *  - Gitignore creation when none exists (and when an existing one is blank).
 *  - Gitignore append when one already exists with unrelated entries.
 *  - Gitignore is not duplicated when entries are already present — including
 *    the CRLF case (a Windows-edited gitignore must not grow on every compile).
 *  - Classpath-backed packs are skipped (no write attempted).
 *  - Empty resolvedPacks is a no-op.
 */
class PerPackTsconfigEmitterTest {

  private val tempDirs = mutableListOf<File>()

  @AfterTest
  fun cleanup() {
    tempDirs.forEach { it.deleteRecursively() }
    tempDirs.clear()
  }

  @Test
  fun `canonical pack layout produces a four-level-up paths mapping`() {
    // Standard workspace shape: <workspace>/config/packs/<id>/tools/.
    // From the tools dir up to <workspace>/.trailblaze/sdk/dist/index.d.ts is 4 `..` segments.
    val workspaceRoot = newWorkspaceRootWithBundle()
    val packDir = File(workspaceRoot, "config/packs/sample").apply { mkdirs() }

    val pack = filesystemPack(id = "sample", packDir = packDir)
    PerPackTsconfigEmitter.emit(workspaceRoot = workspaceRoot.toPath(), resolvedPacks = listOf(pack))

    val tsconfig = File(packDir, "tools/tsconfig.json")
    assertTrue(tsconfig.isFile, "expected tsconfig at $tsconfig")
    val rendered = tsconfig.readText()
    assertTrue("expected 4-level paths target for the SDK bundle: $rendered") {
      rendered.contains("\"@trailblaze/scripting\": [\"../../../../.trailblaze/sdk/dist/index.d.ts\"]")
    }
    assertTrue("expected the wildcard paths entry to mirror the bundle dir: $rendered") {
      rendered.contains("\"@trailblaze/scripting/*\": [\"../../../../.trailblaze/sdk/dist/*\"]")
    }
    assertTrue("expected include glob covering ts + js + codegen: $rendered") {
      rendered.contains("\"include\": [\"**/*.ts\", \"**/*.js\", \".trailblaze/**/*\"]")
    }
    assertTrue("expected *.test.ts exclude (mirrors the SDK's own tsconfig): $rendered") {
      rendered.contains("\"exclude\": [\"**/*.test.ts\"]")
    }
    assertTrue("expected framework banner: $rendered") {
      rendered.contains(PerPackTsconfigEmitter.FRAMEWORK_BANNER)
    }
  }

  @Test
  fun `self-contained tsconfig carries no extends chain and inlines compiler options`() {
    // Core npm-portability invariant. If `extends:` ever creeps back in, an npm-distributed
    // pack installed into a different workspace's `node_modules/` would point at a missing
    // base file. The presence of inlined `target`/`lib`/`strict`/`moduleResolution` is what
    // makes the pack survive that move.
    val workspaceRoot = newWorkspaceRootWithBundle()
    val packDir = File(workspaceRoot, "config/packs/portable").apply { mkdirs() }
    val pack = filesystemPack(id = "portable", packDir = packDir)

    PerPackTsconfigEmitter.emit(workspaceRoot = workspaceRoot.toPath(), resolvedPacks = listOf(pack))

    val rendered = File(packDir, "tools/tsconfig.json").readText()
    assertFalse(
      "self-contained tsconfig must not declare extends: $rendered",
    ) { rendered.contains("\"extends\"") }
    // Spot-check the load-bearing inlined options. Each is documented in the emitter
    // kdoc — if a future refactor drops one, the bundled-.d.ts assumption shifts.
    listOf(
      "\"target\": \"ES2022\"",
      "\"lib\": [\"ES2022\"]",
      "\"moduleResolution\": \"Bundler\"",
      "\"strict\": true",
      "\"allowJs\": true",
      "\"noEmit\": true",
    ).forEach { needle ->
      assertTrue("expected $needle in: $rendered") { rendered.contains(needle) }
    }
    assertFalse(
      "must not declare baseUrl — deprecated in TS 5.x (TS5101) and removed in TS 7.0: $rendered",
    ) { rendered.contains("\"baseUrl\"") }
  }

  @Test
  fun `nested pack layout produces a deeper paths mapping`() {
    // A pack one extra level deep — e.g. `<workspace>/config/packs/group/sample/tools/`.
    // Distance is now 5 segments. The relative-path computation must adapt.
    val workspaceRoot = newWorkspaceRootWithBundle()
    val packDir = File(workspaceRoot, "config/packs/group/sample").apply { mkdirs() }

    val pack = filesystemPack(id = "sample", packDir = packDir)
    PerPackTsconfigEmitter.emit(workspaceRoot = workspaceRoot.toPath(), resolvedPacks = listOf(pack))

    val tsconfig = File(packDir, "tools/tsconfig.json")
    val rendered = tsconfig.readText()
    assertTrue("expected 5-level paths target: $rendered") {
      rendered.contains("\"@trailblaze/scripting\": [\"../../../../../.trailblaze/sdk/dist/index.d.ts\"]")
    }
  }

  @Test
  fun `npm-portability — moving a pack to a different depth re-emits a corrected tsconfig`() {
    // Simulates the npm-distribution flow: pack authored at depth A, regenerated, then
    // (post-install) re-located to depth B. The next `trailblaze compile` must produce a
    // tsconfig that still resolves the SDK bundle from the new location. This is exactly
    // the kind of "moves under your feet" scenario the `extends:` chain couldn't survive,
    // and the reason this PR's hard-cut to self-contained tsconfigs landed.
    val workspaceRoot = newWorkspaceRootWithBundle()

    // First emit: standard depth — 4 levels up.
    val shallowPackDir = File(workspaceRoot, "config/packs/sample").apply { mkdirs() }
    PerPackTsconfigEmitter.emit(
      workspaceRoot = workspaceRoot.toPath(),
      resolvedPacks = listOf(filesystemPack(id = "sample", packDir = shallowPackDir)),
    )
    val shallowRendered = File(shallowPackDir, "tools/tsconfig.json").readText()
    assertTrue("baseline depth-4 should be 4 segments: $shallowRendered") {
      shallowRendered.contains("\"../../../../.trailblaze/sdk/dist/index.d.ts\"")
    }

    // Second emit: same pack moved one level deeper. The framework re-derives the
    // relative path and overwrites the tsconfig — no `extends:` chain to break.
    val deepPackDir = File(workspaceRoot, "config/packs/team/sample").apply { mkdirs() }
    File(shallowPackDir, "tools").copyRecursively(File(deepPackDir, "tools"), overwrite = true)
    PerPackTsconfigEmitter.emit(
      workspaceRoot = workspaceRoot.toPath(),
      resolvedPacks = listOf(filesystemPack(id = "sample", packDir = deepPackDir)),
    )
    val deepRendered = File(deepPackDir, "tools/tsconfig.json").readText()
    assertTrue("relocated pack should re-derive to depth-5: $deepRendered") {
      deepRendered.contains("\"../../../../../.trailblaze/sdk/dist/index.d.ts\"")
    }
    assertFalse("relocated tsconfig must not retain the old depth-4 path: $deepRendered") {
      deepRendered.contains("\"../../../../.trailblaze/sdk/dist/index.d.ts\"")
    }
  }

  @Test
  fun `second emit on unchanged input leaves files byte-identical`() {
    val workspaceRoot = newWorkspaceRootWithBundle()
    val packDir = File(workspaceRoot, "config/packs/idempotent").apply { mkdirs() }
    val pack = filesystemPack(id = "idempotent", packDir = packDir)

    PerPackTsconfigEmitter.emit(workspaceRoot = workspaceRoot.toPath(), resolvedPacks = listOf(pack))
    val tsconfigPath = File(packDir, "tools/tsconfig.json")
    val gitignorePath = File(packDir, ".gitignore")
    val firstTsconfig = tsconfigPath.readBytes()
    val firstGitignore = gitignorePath.readBytes()

    PerPackTsconfigEmitter.emit(workspaceRoot = workspaceRoot.toPath(), resolvedPacks = listOf(pack))

    assertTrue(
      firstTsconfig.contentEquals(tsconfigPath.readBytes()),
      "tsconfig bytes should be identical across emits with the same workspace and pack",
    )
    assertTrue(
      firstGitignore.contentEquals(gitignorePath.readBytes()),
      ".gitignore bytes should be identical when entries are already present",
    )
  }

  @Test
  fun `hand-authored tsconfig without framework banner is preserved verbatim and warns once`() {
    // Upgrade-safety contract: the first compile on a workspace that came from
    // the pre-emitter era must NOT silently destroy author overrides like
    // `compilerOptions.allowJs: false`. AND the author must learn about the
    // preservation — the warning is the entire UX of the migration path, so a
    // future refactor that drops the diagnostic shouldn't pass tests silently.
    val workspaceRoot = newWorkspaceRootWithBundle()
    val packDir = File(workspaceRoot, "config/packs/legacy").apply { mkdirs() }
    val toolsDir = File(packDir, "tools").apply { mkdirs() }
    val handAuthored = """
      {
        "extends": "../../../../.trailblaze/tsconfig.base.json",
        "compilerOptions": { "allowJs": false },
        "include": ["**/*.ts"]
      }
    """.trimIndent()
    val tsconfig = File(toolsDir, "tsconfig.json").apply { writeText(handAuthored) }

    val pack = filesystemPack(id = "legacy", packDir = packDir)
    val stderr = captureStderr {
      PerPackTsconfigEmitter.emit(workspaceRoot = workspaceRoot.toPath(), resolvedPacks = listOf(pack))
    }

    assertEquals(
      handAuthored,
      tsconfig.readText(),
      "hand-authored tsconfig should be preserved verbatim",
    )
    assertTrue("expected a per-pack warning naming the pack id: $stderr") {
      stderr.contains("'legacy'") && stderr.contains("hand-authored")
    }
    assertTrue("expected the warning to point at the migration path: $stderr") {
      stderr.contains("Delete this file") || stderr.contains("delete this file")
    }
    assertTrue("expected the warning to reference the current CLI verb: $stderr") {
      stderr.contains("trailblaze check")
    }
  }

  @Test
  fun `legacy-bannered tsconfig is recognized as framework-owned and rewritten without warning`() {
    // Backward-compat migration: pre-#3236 tsconfigs carry the legacy banner that
    // names `trailblaze compile`. The emitter must treat them as framework-owned and
    // overwrite with the current content, not flag them as hand-authored. A regression
    // here would surface as a spurious warning on every `trailblaze check` for users
    // upgrading from before the CLI verb was unified.
    val workspaceRoot = newWorkspaceRootWithBundle()
    val packDir = File(workspaceRoot, "config/packs/legacy-banner").apply { mkdirs() }
    val toolsDir = File(packDir, "tools").apply { mkdirs() }
    val legacyBanner = PerPackTsconfigEmitter.LEGACY_FRAMEWORK_BANNERS.first()
    val stale = """
      $legacyBanner
      {
        "compilerOptions": { "allowJs": false },
        "include": ["**/*.ts"]
      }
    """.trimIndent() + "\n"
    val tsconfig = File(toolsDir, "tsconfig.json").apply { writeText(stale) }

    val pack = filesystemPack(id = "legacy-banner", packDir = packDir)
    val stderr = captureStderr {
      PerPackTsconfigEmitter.emit(workspaceRoot = workspaceRoot.toPath(), resolvedPacks = listOf(pack))
    }

    val updated = tsconfig.readText()
    assertTrue("expected refreshed banner naming the current CLI verb: $updated") {
      updated.startsWith(PerPackTsconfigEmitter.FRAMEWORK_BANNER)
    }
    assertTrue("expected refreshed include with js glob: $updated") {
      updated.contains("\"**/*.js\"")
    }
    assertFalse("expected no hand-authored warning on stderr (got: $stderr)") {
      stderr.contains("hand-authored")
    }
  }

  @Test
  fun `framework-bannered tsconfig is rewritten when content drifts`() {
    val workspaceRoot = newWorkspaceRootWithBundle()
    val packDir = File(workspaceRoot, "config/packs/drifted").apply { mkdirs() }
    val toolsDir = File(packDir, "tools").apply { mkdirs() }
    // Mimic a stale framework-generated tsconfig from the pre-self-contained era —
    // same banner but still carrying the old extends-style content.
    val stale = """
      ${PerPackTsconfigEmitter.FRAMEWORK_BANNER}
      {
        "extends": "../../../../.trailblaze/tsconfig.base.json",
        "include": ["**/*.ts", ".trailblaze/**/*"]
      }
    """.trimIndent() + "\n"
    val tsconfig = File(toolsDir, "tsconfig.json").apply { writeText(stale) }

    val pack = filesystemPack(id = "drifted", packDir = packDir)
    PerPackTsconfigEmitter.emit(workspaceRoot = workspaceRoot.toPath(), resolvedPacks = listOf(pack))

    val updated = tsconfig.readText()
    assertFalse("refreshed tsconfig must drop the old extends chain: $updated") {
      updated.contains("\"extends\"")
    }
    assertTrue("expected refreshed include with js glob: $updated") {
      updated.contains("\"**/*.js\"")
    }
    assertTrue("expected refreshed paths target pointing at the dts bundle: $updated") {
      updated.contains("\"@trailblaze/scripting\": [\"../../../../.trailblaze/sdk/dist/index.d.ts\"]")
    }
  }

  @Test
  fun `missing workspace SDK bundle throws with actionable message`() {
    val workspaceRoot = createTempDirectory("per-pack-tsconfig-no-bundle-test").toFile()
      .also { tempDirs += it }
      .resolve("trails")
      .apply { mkdirs() }
    val packDir = File(workspaceRoot, "config/packs/lonely").apply { mkdirs() }
    val pack = filesystemPack(id = "lonely", packDir = packDir)

    val ex = assertFailsWith<IllegalStateException> {
      PerPackTsconfigEmitter.emit(workspaceRoot = workspaceRoot.toPath(), resolvedPacks = listOf(pack))
    }
    val msg = ex.message ?: ""
    assertTrue("expected message to name the missing bundle path: $msg") {
      msg.contains("dist/index.d.ts") || msg.contains("declaration bundle")
    }
    assertTrue("expected message to point at WorkspaceTypeScriptSetup as the fix: $msg") {
      msg.contains("WorkspaceTypeScriptSetup")
    }
  }

  @Test
  fun `gitignore is created when none exists`() {
    val workspaceRoot = newWorkspaceRootWithBundle()
    val packDir = File(workspaceRoot, "config/packs/fresh").apply { mkdirs() }
    val pack = filesystemPack(id = "fresh", packDir = packDir)

    PerPackTsconfigEmitter.emit(workspaceRoot = workspaceRoot.toPath(), resolvedPacks = listOf(pack))

    val gitignore = File(packDir, ".gitignore")
    assertTrue(gitignore.isFile)
    val content = gitignore.readText()
    assertTrue("expected tsconfig entry: $content") {
      content.contains("tools/tsconfig.json")
    }
    assertTrue("expected .trailblaze entry: $content") {
      content.contains("tools/.trailblaze/")
    }
    assertFalse("freshly-created gitignore should not start with a blank line: $content") {
      content.startsWith("\n")
    }
    assertTrue("expected header comment to name the current CLI verb: $content") {
      content.contains("trailblaze check")
    }
  }

  @Test
  fun `gitignore that already exists but is blank is rewritten cleanly`() {
    val workspaceRoot = newWorkspaceRootWithBundle()
    val packDir = File(workspaceRoot, "config/packs/blank_gi").apply { mkdirs() }
    val gitignore = File(packDir, ".gitignore").apply { writeText("\n\n") }
    val pack = filesystemPack(id = "blank_gi", packDir = packDir)

    PerPackTsconfigEmitter.emit(workspaceRoot = workspaceRoot.toPath(), resolvedPacks = listOf(pack))

    val content = gitignore.readText()
    assertFalse("rewritten blank gitignore should not start with whitespace: $content") {
      content.startsWith("\n")
    }
    assertTrue(content.contains("tools/tsconfig.json"))
  }

  @Test
  fun `gitignore append preserves unrelated entries and adds missing framework entries`() {
    val workspaceRoot = newWorkspaceRootWithBundle()
    val packDir = File(workspaceRoot, "config/packs/with_existing").apply { mkdirs() }
    val gitignore = File(packDir, ".gitignore")
    val authoredContent = "# Authored\nlocal-notes.md\nbuild/\n"
    gitignore.writeText(authoredContent)

    val pack = filesystemPack(id = "with_existing", packDir = packDir)
    PerPackTsconfigEmitter.emit(workspaceRoot = workspaceRoot.toPath(), resolvedPacks = listOf(pack))

    val updated = gitignore.readText()
    assertTrue("expected original entries to be preserved: $updated") {
      updated.startsWith(authoredContent)
    }
    assertTrue("expected framework block appended: $updated") {
      updated.contains("tools/tsconfig.json") && updated.contains("tools/.trailblaze/")
    }
  }

  @Test
  fun `gitignore append does not duplicate framework entries already present`() {
    val workspaceRoot = newWorkspaceRootWithBundle()
    val packDir = File(workspaceRoot, "config/packs/no_dup").apply { mkdirs() }
    val gitignore = File(packDir, ".gitignore")
    gitignore.writeText("local-cache/\ntools/tsconfig.json\n")

    val pack = filesystemPack(id = "no_dup", packDir = packDir)
    PerPackTsconfigEmitter.emit(workspaceRoot = workspaceRoot.toPath(), resolvedPacks = listOf(pack))

    val updated = gitignore.readText()
    val tsconfigOccurrences = updated.lines().count { it == "tools/tsconfig.json" }
    assertEquals(
      1,
      tsconfigOccurrences,
      "tools/tsconfig.json should appear exactly once; got: $updated",
    )
    assertTrue("expected newly-missing entry to be added: $updated") {
      updated.lines().any { it == "tools/.trailblaze/" }
    }

    val secondPassBytes = gitignore.readBytes()
    PerPackTsconfigEmitter.emit(workspaceRoot = workspaceRoot.toPath(), resolvedPacks = listOf(pack))
    assertTrue(
      secondPassBytes.contentEquals(gitignore.readBytes()),
      "second emit should leave the gitignore byte-identical when all entries are present",
    )
  }

  @Test
  fun `gitignore with CRLF line endings is not duplicated on re-emit`() {
    // Windows-edited .gitignore files use \r\n terminators. The original
    // implementation used `split('\n')` which left a trailing '\r' on every
    // token and made the framework entries look perpetually missing — every
    // compile would re-append, growing the file unboundedly.
    val workspaceRoot = newWorkspaceRootWithBundle()
    val packDir = File(workspaceRoot, "config/packs/crlf").apply { mkdirs() }
    val gitignore = File(packDir, ".gitignore").apply {
      writeText("local-cache/\r\ntools/tsconfig.json\r\ntools/.trailblaze/\r\n")
    }

    val pack = filesystemPack(id = "crlf", packDir = packDir)
    PerPackTsconfigEmitter.emit(workspaceRoot = workspaceRoot.toPath(), resolvedPacks = listOf(pack))

    val content = gitignore.readText()
    // Both entries already present → no-op write. Content stays exactly as the
    // CRLF original.
    assertEquals(
      "local-cache/\r\ntools/tsconfig.json\r\ntools/.trailblaze/\r\n",
      content,
      "CRLF gitignore with framework entries already present should not be rewritten",
    )
  }

  @Test
  fun `classpath-backed packs are skipped`() {
    val workspaceRoot = newWorkspaceRootWithBundle()
    val classpathPack = ResolvedPack(
      manifest = TrailblazePackManifest(
        id = "clock",
        target = PackTargetConfig(displayName = "Clock"),
      ),
      source = PackSource.Classpath(resourceDir = "trailblaze-config/packs/clock"),
      target = AppTargetYamlConfig(id = "clock", displayName = "Clock"),
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )

    val emitted = PerPackTsconfigEmitter.emit(
      workspaceRoot = workspaceRoot.toPath(),
      resolvedPacks = listOf(classpathPack),
    )

    assertTrue(emitted.isEmpty(), "expected no emissions for classpath-only pool: $emitted")
  }

  @Test
  fun `empty resolvedPacks is a no-op`() {
    val workspaceRoot = newWorkspaceRootWithBundle()
    val emitted = PerPackTsconfigEmitter.emit(
      workspaceRoot = workspaceRoot.toPath(),
      resolvedPacks = emptyList(),
    )
    assertTrue(emitted.isEmpty())
  }

  @Test
  fun `emit returns both file paths per pack`() {
    val workspaceRoot = newWorkspaceRootWithBundle()
    val packDir = File(workspaceRoot, "config/packs/two_files").apply { mkdirs() }
    val pack = filesystemPack(id = "two_files", packDir = packDir)

    val emitted = PerPackTsconfigEmitter.emit(
      workspaceRoot = workspaceRoot.toPath(),
      resolvedPacks = listOf(pack),
    )

    assertEquals(2, emitted.size, "expected tsconfig + gitignore returned: $emitted")
    assertTrue("returned paths should include tsconfig: $emitted") {
      emitted.any { it.fileName.toString() == "tsconfig.json" }
    }
    assertTrue("returned paths should include .gitignore: $emitted") {
      emitted.any { it.fileName.toString() == ".gitignore" }
    }
  }

  @Test
  fun `generated tsconfig is valid JSON5-ish — contains a single object`() {
    // The generated file uses a leading line-comment (`//`) which is fine for TS tsconfig
    // parsing (tsc accepts JSON with comments) but invalid for strict JSON. This test
    // codifies the on-disk shape so a future refactor doesn't silently drop the banner
    // (banner is load-bearing — both UX AND upgrade-safety: see object kdoc).
    val workspaceRoot = newWorkspaceRootWithBundle()
    val packDir = File(workspaceRoot, "config/packs/shape").apply { mkdirs() }
    val pack = filesystemPack(id = "shape", packDir = packDir)

    PerPackTsconfigEmitter.emit(workspaceRoot = workspaceRoot.toPath(), resolvedPacks = listOf(pack))

    val rendered = File(packDir, "tools/tsconfig.json").readText()
    assertTrue("expected leading line comment: $rendered") { rendered.startsWith("//") }
    assertTrue("expected the JSON body to open with {: $rendered") {
      rendered.lines().any { it.trimStart().startsWith("{") }
    }
    assertTrue("expected the JSON body to close with }: $rendered") {
      rendered.trimEnd().endsWith("}")
    }
    assertFalse("did not expect a stray BOM or other binary byte: $rendered") {
      rendered.any { it.code == 0xFEFF }
    }
  }

  private fun filesystemPack(id: String, packDir: File): ResolvedPack = ResolvedPack(
    manifest = TrailblazePackManifest(
      id = id,
      target = PackTargetConfig(displayName = id),
    ),
    source = PackSource.Filesystem(packDir),
    target = AppTargetYamlConfig(id = id, displayName = id),
    toolsets = emptyList(),
    tools = emptyList(),
    waypoints = emptyList(),
  )

  /**
   * Run [block] with `System.err` temporarily redirected to a buffer and return
   * its captured contents. `Console.error` on JVM goes through `System.err`, so
   * this is the seam for asserting on per-pack diagnostic emissions without
   * adding a test-only callback shim to the production class.
   */
  private fun captureStderr(block: () -> Unit): String {
    val buffer = ByteArrayOutputStream()
    val original = System.err
    System.setErr(PrintStream(buffer, true, Charsets.UTF_8))
    try {
      block()
    } finally {
      System.setErr(original)
    }
    return buffer.toString(Charsets.UTF_8)
  }

  /**
   * Provision a fake workspace root with a stub SDK declaration bundle in place. The
   * emitter asserts the bundle exists before writing per-pack tsconfigs, so every
   * happy-path test needs this fixture. Tests that exercise the missing-bundle path
   * (above) deliberately skip this step.
   */
  private fun newWorkspaceRootWithBundle(): File {
    val parent = createTempDirectory("per-pack-tsconfig-test").toFile()
    tempDirs += parent
    val workspace = File(parent, "trails").apply { mkdirs() }
    val bundleDir = File(workspace, ".trailblaze/sdk/dist").apply { mkdirs() }
    File(bundleDir, "index.d.ts").writeText("// stub bundle\nexport {};\n")
    return workspace
  }
}
