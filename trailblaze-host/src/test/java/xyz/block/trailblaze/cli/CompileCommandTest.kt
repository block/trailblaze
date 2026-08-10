package xyz.block.trailblaze.cli

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import picocli.CommandLine
import xyz.block.trailblaze.logs.client.TrailblazeSerializationInitializer
import xyz.block.trailblaze.scripting.AnalyzerScriptedToolEnrichment
import xyz.block.trailblaze.scripting.MetaOnlyDescriptorTestFixture

/**
 * Tests for [CompileCommand] — the user-facing `trailblaze compile` picocli
 * command. Covers: option defaults, the missing-`trailmaps/` `EXIT_USAGE` path,
 * a successful end-to-end compile, the workspace-root walk-up that lets the
 * command run from any subdirectory of a workspace, and the fall-back when
 * no workspace marker is found.
 *
 * Sister test: `TrailblazeCompilerMainTest` covers the same compile-then-emit
 * flow at the lighter `TrailblazeCompilerMain` entry point. Together they pin
 * both layers of the compile UX (build-time `JavaExec` and user CLI).
 */
class CompileCommandTest {

  private val workDir: File = createTempDirectory("trailblaze-compile-command-test").toFile()

  @AfterTest fun cleanup() {
    workDir.deleteRecursively()
    // `CompileCommand` registers this workspace's `*.tool.yaml` files on the process-global
    // YAML-tool registry (the resolver snapshots it at construction, so there's no scoped
    // alternative). Clear it so a later test in the same JVM doesn't resolve names against this
    // test's temp workspace. The toolset catalog needs no reset — compile passes its catalog to
    // the emitter explicitly instead of installing the global overlay.
    TrailblazeSerializationInitializer.registerWorkspaceYamlTools(emptyMap())
  }

  @Test
  fun `findWorkspaceRoot returns the workspace itself when invoked from the root`() {
    val workspaceRoot = workDir
    File(workspaceRoot, "trails/config/trailmaps").mkdirs()

    val command = CompileCommand()
    val found = command.findWorkspaceRoot(startPath = workspaceRoot.toPath())
    assertEquals(workspaceRoot.canonicalFile.toPath(), found?.toRealPath())
  }

  @Test
  fun `findWorkspaceRoot walks up from a trailmap root to the workspace root`() {
    val workspaceRoot = workDir
    val trailmapRoot = File(workspaceRoot, "trails/config/trailmaps/wikipedia").apply { mkdirs() }
    File(trailmapRoot, "trailmap.yaml").writeText("id: wikipedia\n")

    val command = CompileCommand()
    val found = command.findWorkspaceRoot(startPath = trailmapRoot.toPath())
    assertEquals(workspaceRoot.canonicalFile.toPath(), found?.toRealPath())
  }

  @Test
  fun `findWorkspaceRoot walks up from a trailmap tools dir to the workspace root`() {
    // The canonical "deep dir" scenario — running `trailblaze compile` from
    // inside a trailmap's tools/ directory should still find the workspace root
    // without the user counting `../` segments to construct an --input path.
    val workspaceRoot = workDir
    val trailmapToolsDir = File(workspaceRoot, "trails/config/trailmaps/wikipedia/tools").apply { mkdirs() }

    val command = CompileCommand()
    val found = command.findWorkspaceRoot(startPath = trailmapToolsDir.toPath())
    assertEquals(workspaceRoot.canonicalFile.toPath(), found?.toRealPath())
  }

  @Test
  fun `findWorkspaceRoot returns null when no workspace marker is found`() {
    // workDir is /tmp/<random> with no `trails/config/trailmaps/` anywhere up the tree,
    // so the helper returns null and the caller is expected to emit a usage error
    // rather than silently defaulting to a bogus root.
    val isolated = File(workDir, "isolated").apply { mkdirs() }

    val command = CompileCommand()
    val found = command.findWorkspaceRoot(startPath = isolated.toPath())
    assertNull(found)
  }

  @Test
  fun `findWorkspaceRoot still finds a marker many levels above the start dir`() {
    // No depth cap: a deeply-nested start (deeper than what monorepo CLIs would
    // typically encounter) must still walk all the way up to the marker. Pins
    // the "uncapped walk" contract so a future depth-limit regression fails here.
    File(workDir, "trails/config/trailmaps").mkdirs()
    var deep = workDir
    repeat(20) { deep = File(deep, "level").apply { mkdirs() } }

    val command = CompileCommand()
    val found = command.findWorkspaceRoot(startPath = deep.toPath())
    assertEquals(workDir.canonicalFile.toPath(), found?.toRealPath())
  }

  @Test
  fun `compile from inside a workspace exits OK with no flags`() {
    // End-to-end coverage for the headline UX fix: cwd inside a workspace tree
    // (here, the trailmap tools/ dir 4 levels deep), no flags → exits 0 and emits
    // the materialized target. Uses `CliCallerContext.withCallerCwd` to pin the
    // walk-up start dir without mutating the JVM-wide cwd.
    val workspaceRoot = File(workDir, "workspace").apply { mkdirs() }
    val trailmapToolsDir = File(workspaceRoot, "trails/config/trailmaps/alpha/tools").apply { mkdirs() }
    File(workspaceRoot, "trails/config/trailmaps/alpha/trailmap.yaml").writeText(
      """
      id: alpha
      target:
        display_name: Alpha
        platforms:
          android:
            app_ids: [com.example.alpha]
      """.trimIndent(),
    )

    val exit = CliCallerContext.withCallerCwd(trailmapToolsDir.toPath()) {
      CommandLine(CompileCommand()).execute()
    }

    assertEquals(0, exit, "Expected EXIT_OK from a no-flag run inside a workspace")
    assertTrue(
      File(workspaceRoot, "trails/config/dist/targets/alpha.yaml").exists(),
      "alpha.yaml should land at <workspace>/trails/config/dist/targets/",
    )
  }

  @Test
  fun `compile from outside any workspace exits EXIT_USAGE with no flags`() {
    // End-to-end coverage for the negative branch: cwd outside any workspace,
    // no --input → walks to filesystem root, finds nothing, exits EXIT_USAGE.
    val isolated = File(workDir, "isolated").apply { mkdirs() }

    val exit = CliCallerContext.withCallerCwd(isolated.toPath()) {
      CommandLine(CompileCommand()).execute()
    }

    assertEquals(TrailblazeExitCode.MISUSE.code, exit, "Expected MISUSE when run with no flags outside any workspace")
  }

  @Test
  fun `compile emits target yaml when invoked with explicit input and output`() {
    val trailmapsDir = File(workDir, "trailmaps").apply { mkdirs() }
    File(trailmapsDir, "alpha").mkdirs()
    File(trailmapsDir, "alpha/trailmap.yaml").writeText(
      """
      id: alpha
      target:
        display_name: Alpha
        platforms:
          android:
            app_ids: [com.example.alpha]
      """.trimIndent(),
    )
    val outputDir = File(workDir, "out")

    val command = CompileCommand()
    val exit = CommandLine(command).execute(
      "--input", workDir.absolutePath,
      "--output", outputDir.absolutePath,
    )

    assertEquals(0, exit, "Expected EXIT_OK from a clean compile")
    assertTrue(File(outputDir, "alpha.yaml").exists(), "alpha.yaml should be emitted")
  }

  @Test
  fun `compile returns EXIT_USAGE when no trailmaps directory is present under input`() {
    val emptyInput = File(workDir, "no-trailmaps").apply { mkdirs() }
    val outputDir = File(workDir, "out")

    val command = CompileCommand()
    val exit = CommandLine(command).execute(
      "--input", emptyInput.absolutePath,
      "--output", outputDir.absolutePath,
    )

    assertEquals(TrailblazeExitCode.MISUSE.code, exit, "Expected MISUSE when --input has no trailmaps/ dir")
    assertTrue(!outputDir.exists(), "outputDir should not be created on usage error")
  }

  @Test
  fun `compile returns EXIT_COMPILE_ERROR when a trailmap has a missing dependency`() {
    val trailmapsDir = File(workDir, "trailmaps").apply { mkdirs() }
    File(trailmapsDir, "consumer").mkdirs()
    File(trailmapsDir, "consumer/trailmap.yaml").writeText(
      """
      id: consumer
      dependencies:
        - missing-trailmap
      target:
        display_name: Consumer
        platforms:
          android:
            app_ids: [com.example]
      """.trimIndent(),
    )
    val outputDir = File(workDir, "out")

    val command = CompileCommand()
    val exit = CommandLine(command).execute(
      "--input", workDir.absolutePath,
      "--output", outputDir.absolutePath,
    )

    assertEquals(TrailblazeExitCode.ASSERTION_FAILED.code, exit, "Expected ASSERTION_FAILED on resolution failure")
  }

  @Test
  fun `compile uses workspace-root defaults when --input and --output are omitted`() {
    // Mock a workspace at workDir/workspace with the standard layout.
    val workspaceRoot = File(workDir, "workspace").apply { mkdirs() }
    val trailmapsDir = File(workspaceRoot, "trails/config/trailmaps").apply { mkdirs() }
    File(trailmapsDir, "alpha").mkdirs()
    File(trailmapsDir, "alpha/trailmap.yaml").writeText(
      """
      id: alpha
      target:
        display_name: Alpha
        platforms:
          android:
            app_ids: [com.example.alpha]
      """.trimIndent(),
    )
    val expectedOutputDir = File(workspaceRoot, "trails/config/dist/targets")

    // Inject the workspace root via the test-visible findWorkspaceRoot helper. We can't
    // reliably swap the CWD inside a JVM test, so we set the option fields explicitly to
    // simulate "user ran with no flags + we discovered the workspace root."
    val command = CompileCommand().apply {
      inputDir = File(workspaceRoot, "trails/config")
      outputDir = expectedOutputDir
    }
    val exit = command.call()

    assertEquals(0, exit)
    assertTrue(File(expectedOutputDir, "alpha.yaml").exists(), "Default output dir should land at workspace-root/trails/config/dist/targets")
  }

  @Test
  fun `compile resolves a toolset the workspace authored and puts its tools in the typed surface`() {
    // A trailmap is allowed to ship its own toolsets at `trailmaps/<id>/toolsets/<name>.yaml`
    // and reference them from `platforms.<p>.tool_sets:`. Both halves of that promise are
    // pinned here, because both used to be broken and only showed up in a workspace that had
    // no same-id copy of the trailmap on the classpath to accidentally supply the ids:
    //
    //   1. compile succeeds — reference validation finds `alpha_extra` on disk instead of
    //      failing with "references unknown toolset".
    //   2. the tool that toolset carries lands in the trailmap's generated typed surface, so a
    //      scripted tool can call `ctx.tools.mobile_setClipboard(...)` and typecheck. Runtime
    //      dispatch always resolved it through the global registry; only the emitted `.d.ts`
    //      disagreed.
    //
    // Picking the asserted tool is the whole trick, because `tool_sets:` is only ONE of the three
    // sources `resolveKotlinToolDescriptorsForTrailmap` unions: it also adds every recordable
    // framework class tool and every YAML-defined tool that isn't `isRecordable: false`, both
    // regardless of `tool_sets:`. So a recordable tool, an `always_enabled` toolset member
    // (`mobile_setClipboard`), or any YAML-defined tool (`dumpMemory`, or one this workspace
    // authored) all land in the surface whether or not the workspace catalog reached the emitter —
    // each would be a vacuous assertion.
    //
    // `web_requestDetails` is the discriminating case: class-backed, not recordable, and its only
    // classpath home is `web/toolsets/web_core.yaml`, which is not `always_enabled` (its sibling
    // `web_framework` is, so don't reach for `web_evaluate`). Toolset expansion here is
    // driver-agnostic — the emitter calls `TrailblazeToolSetCatalog.resolve`, not
    // `resolveForDriver` — so a web tool reached from an Android target is resolved on membership
    // alone, which is exactly the edge under test. With `alpha` declaring no `dependencies:`,
    // `alpha_extra` is its only possible route in. Negative control: pass
    // `TrailblazeToolSetCatalog.defaultEntries()` to the emitter in `CompileCommand` instead of
    // the resolved workspace catalog and this goes red.
    val workspaceRoot = File(workDir, "workspace").apply { mkdirs() }
    val trailmapDir = File(workspaceRoot, "trails/config/trailmaps/alpha").apply { mkdirs() }
    File(trailmapDir, "tools").mkdirs()
    File(trailmapDir, "toolsets").mkdirs()
    File(trailmapDir, "toolsets/alpha_extra.yaml").writeText(
      """
      id: alpha_extra
      description: "Toolset authored by the alpha workspace trailmap."
      tools:
        - web_requestDetails
      """.trimIndent(),
    )
    File(trailmapDir, "trailmap.yaml").writeText(
      """
      id: alpha
      target:
        display_name: Alpha
        platforms:
          android:
            app_ids: [com.example.alpha]
            tool_sets: [alpha_extra]
      """.trimIndent(),
    )

    val command = CompileCommand().apply { inputDir = File(workspaceRoot, "trails/config") }
    val exit = command.call()

    assertEquals(0, exit, "A trailmap referencing its own workspace toolset should compile")
    val typedSurface = File(trailmapDir, "tools/trailblaze-client.d.ts")
    assertTrue(typedSurface.exists(), "alpha should get a generated typed surface")
    assertTrue(
      typedSurface.readText().contains("web_requestDetails"),
      "The tool carried by the workspace-authored toolset should be declared in alpha's typed " +
        "surface; got:\n${typedSurface.readText()}",
    )
  }

  @Test
  fun `--help exits zero and prints usage`() {
    // Picocli routes `--help` through `mixinStandardHelpOptions = true`, exiting 0.
    // This pins the convention so a future refactor can't accidentally make help
    // exit non-zero (which would break shell scripts that check `cli --help`).
    val command = CompileCommand()
    val exit = CommandLine(command).execute("--help")
    assertEquals(0, exit)
  }

  @Test
  fun `commandLabel defaults to 'compile' for direct invocation`() {
    // Regression marker: a freshly-constructed CompileCommand emits error prefixes
    // under `trailblaze compile:` unless CheckCommand routes it via
    // `apply { commandLabel = "check" }`. A silent default rename here would break
    // the routing contract without surfacing in any other test.
    assertEquals("compile", CompileCommand().commandLabel)
  }

  @Test
  fun `compile resolves a meta-only descriptor when analyzer is available`() {
    // End-to-end integration test for the meta-only authoring shape's path through
    // `CompileCommand`. The companion unit-level coverage in
    // `TrailblazeCompilerTest.compile threads scriptedToolEnrichment into the loader for
    // meta-only descriptors` already pins the loader contract with a stub enrichment;
    // this test exercises the real wiring — `CompileCommand` must call
    // `AnalyzerScriptedToolEnrichment.resolveFromEnvironment()` and pass it down — and
    // therefore needs an actual Node + SDK install reachable on PATH / via
    // `TRAILBLAZE_SDK_DIR`.
    //
    // `assumeTrue` skips on CI agents / dev machines that don't have the analyzer
    // available, matching how the production code path handles the same gap
    // (graceful null → clear diagnostic at the loader). The skip preserves CI green
    // on minimal images while still pinning the contract whenever the prerequisites
    // are present.
    val enrichment = AnalyzerScriptedToolEnrichment.resolveFromEnvironment()
    assumeTrue(MetaOnlyDescriptorTestFixture.ANALYZER_UNAVAILABLE_SKIP_MESSAGE, enrichment != null)

    val trailmapsDir = File(workDir, "trailmaps").apply { mkdirs() }
    MetaOnlyDescriptorTestFixture.writeMetaOnlyTrailmap(trailmapsDir)

    val outputDir = File(workDir, "out")
    val command = CompileCommand()
    val exit = CommandLine(command).execute(
      "--input", workDir.absolutePath,
      "--output", outputDir.absolutePath,
    )

    assertEquals(0, exit, "Expected EXIT_OK from a meta-only compile when analyzer is wired")
    assertTrue(
      File(outputDir, "metaonly.yaml").exists(),
      "metaonly.yaml should be emitted — meta-only descriptor must resolve via analyzer",
    )
  }
}
