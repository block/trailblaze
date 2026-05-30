package xyz.block.trailblaze.cli

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the local-typo fast-path in [ToolCommand.call]: a bare invocation with a tool
 * name that no toolset on the classpath knows about must return [TrailblazeExitCode.MISUSE]
 * **before** `cliReusableWithDevice` opens a daemon connection.
 *
 * Why this matters: pre-fast-path, `trailblaze tool tap_on_text -s "test"` took ~30s
 * to bounce because the CLI dispatched to the daemon's MCP `blaze` tool, the daemon
 * walked its full per-platform tool registry, failed to resolve the name, and emitted
 * `Unknown tool: tap_on_text. Use toolbox() to see available tools.` That round-trip
 * is the real OOBE drag for first-time users who type the wrong name. The local check
 * eliminates the round-trip entirely.
 *
 * The tests do not need a daemon or device — the fast-path returns before any such
 * resource is consulted. If the implementation ever regresses to calling
 * `cliReusableWithDevice` for unknown names, the unknown-name tests will start
 * hanging on the device-bind / daemon-connect path (or fail with
 * `assertRejectsBareDeviceInvocation`), which is the loud signal we want.
 */
class ToolCommandTest {

  @get:Rule
  val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun `unknown tool name short-circuits to MISUSE before daemon dispatch`() {
    val cmd = ToolCommand().apply {
      // A name that no toolset on the classpath knows. Picked to look like a typo
      // a first-time user might actually make (`tap_on_text` vs `tap`); the
      // reproducer in the PR body uses the same name for the before/after timing.
      toolName = "tap_on_text"
      step = "test"
    }
    val (exit, stderr) = captureStderr { cmd.call() }
    assertEquals(
      TrailblazeExitCode.MISUSE.code,
      exit,
      "Local fast-path must return MISUSE (3) for a typo'd tool name; " +
        "anything else means the fast-path didn't fire or the exit-code mapping drifted",
    )
    // Both lines of the envelope must be present. Substring-only assertions used to
    // pass even when one of the two Console.error calls was dropped — pinning the
    // *primary* line ("Unknown tool: …") AND the *Tip* line keeps the contract
    // honest against a future refactor that consolidates one but forgets the other.
    val primaryLine = stderr.lineSequence().firstOrNull { "Unknown tool: tap_on_text" in it }
    assertTrue(
      primaryLine != null,
      "Stderr must contain the primary 'Unknown tool: <name>…' line; got: <<$stderr>>",
    )
    val tipLine = stderr.lineSequence().firstOrNull { "Tip: Run 'trailblaze toolbox" in it }
    assertTrue(
      tipLine != null,
      "Stderr must contain the actionable Tip line directing the user to toolbox; " +
        "got: <<$stderr>>",
    )
  }

  @Test
  fun `unknown tool name with garbage chars also short-circuits`() {
    // Defense in depth: a name with characters that can't appear in a real tool
    // (whitespace, punctuation) still has to land in MISUSE. Otherwise picocli's
    // arg parser might silently coerce it through and a daemon round-trip would
    // fire on a name that's *obviously* not a tool.
    val cmd = ToolCommand().apply {
      toolName = "definitely not a real tool!"
      step = "test"
    }
    val (exit, _) = captureStderr { cmd.call() }
    assertEquals(TrailblazeExitCode.MISUSE.code, exit)
  }

  @Test
  fun `unknown tool name with non-empty --yaml does not short-circuit`() {
    // Inverse contract: when the user supplies `--yaml`, the bare tool-name is
    // ignored at the dispatch level — the YAML body is what gets sent. The
    // fast-path's `!yamlIsProvided` guard exists to keep this escape hatch alive
    // even when the bare name happens to be unknown locally (e.g. a workspace-
    // defined tool referenced by name as a placeholder). If a future refactor
    // drops the `yamlIsProvided` check, this case would start emitting the
    // fast-path's `Unknown tool: …` envelope and break every `--yaml`-using
    // script that pairs a name with a body.
    //
    // We can't assert on exit code alone — both the fast-path (when --yaml is
    // dropped) and the downstream bare-device-rejection path (no daemon in
    // unit-test runtime) both return MISUSE, so the codes collide. The
    // load-bearing assertion is that the fast-path's envelope (`Unknown tool:
    // <name>…`) was NOT emitted: anything else means the fast-path fired
    // erroneously despite --yaml being set. The downstream device-bind
    // rejection emits a different envelope (`✗ Device bind failed on …`) which
    // we don't pin shape on here.
    val cmd = ToolCommand().apply {
      toolName = "tap_on_text"
      yaml = "- tap:\n    ref: p386"
      step = "test"
    }
    val (_, stderr) = captureStderr { cmd.call() }
    assertTrue(
      "Unknown tool: tap_on_text" !in stderr,
      "Fast-path must NOT fire when --yaml is provided — the YAML body is the " +
        "dispatch payload, not the bare name. Seeing the fast-path's `Unknown " +
        "tool: tap_on_text` envelope means a regression dropped the " +
        "`yamlIsProvided` guard and broke the --yaml escape hatch. " +
        "Stderr captured: <<$stderr>>",
    )
  }

  @Test
  fun `workspaceHasToolDefinitions returns false for a directory with no workspace`() {
    // Bare temp dir, no `trails/config/trailblaze.yaml` anywhere up the walk.
    // findWorkspaceRoot returns Scratch → no workspace marker → fast-path safe.
    // Without this guarantee the OOBE case (user running from `~`) loses the
    // typo-detection win.
    val cwd = tempFolder.newFolder("scratch").toPath()
    assertFalse(
      workspaceHasToolDefinitions(cwd),
      "A directory with no `trails/config/trailblaze.yaml` ancestor is Scratch — " +
        "fast-path must apply. Returning true here disables OOBE typo detection.",
    )
  }

  @Test
  fun `workspaceHasToolDefinitions returns false for a Configured workspace with no tool YAML`() {
    // A workspace can be Configured (has `trailblaze.yaml`) but ship zero tool/toolset
    // overrides — that's the common case for a fresh `trailblaze init` workspace and
    // also (verified at PR-author time) the shape of this repo's own `trails/config/`.
    // Fast-path must still apply, otherwise we'd punish workspace users who have NO
    // customizations with the pre-PR 30s typo lag.
    val workspaceRoot = tempFolder.newFolder("workspace").toPath()
    val configDir = workspaceRoot.resolve("trails/config")
    java.nio.file.Files.createDirectories(configDir)
    java.nio.file.Files.writeString(configDir.resolve("trailblaze.yaml"), "")
    // Empty trailmaps/ — a workspace with no tools.
    java.nio.file.Files.createDirectories(configDir.resolve("trailmaps"))
    assertFalse(
      workspaceHasToolDefinitions(workspaceRoot),
      "Configured workspace with no `.tool.yaml`/`.toolset.yaml` files anywhere " +
        "must NOT disable the fast-path; otherwise vanilla workspace users lose " +
        "typo detection for no reason",
    )
  }

  @Test
  fun `workspaceHasToolDefinitions returns true when a per-trailmap tool yaml exists`() {
    // Per-trailmap tools live at `<workspace>/trails/config/trailmaps/<id>/tools/*.tool.yaml`
    // (depth 4 from the config dir). The walk has to descend into trailmaps/ to catch them;
    // a depth-1 check would miss them. This pins the depth-4 contract.
    val workspaceRoot = tempFolder.newFolder("workspace-trailmap-tool").toPath()
    val configDir = workspaceRoot.resolve("trails/config")
    val trailmapToolsDir = configDir.resolve("trailmaps/myflow/tools")
    java.nio.file.Files.createDirectories(trailmapToolsDir)
    java.nio.file.Files.writeString(configDir.resolve("trailblaze.yaml"), "")
    java.nio.file.Files.writeString(
      trailmapToolsDir.resolve("flow_specific.tool.yaml"),
      "id: flow_specific\nclass: com.example.FlowTool\n",
    )
    assertTrue(
      workspaceHasToolDefinitions(workspaceRoot),
      "Per-trailmap `tools/*.tool.yaml` lives 4 levels deep under config/. " +
        "If this returns false, the walk depth is too shallow and trailmap-scope " +
        "custom tools regress to the false-rejection envelope.",
    )
  }

  @Test
  fun `workspaceHasToolDefinitions returns true when a per-trailmap toolset yaml exists`() {
    // Toolsets are authored at `<workspace>/trails/config/trailmaps/<id>/toolsets/<name>.yaml`
    // (note: no `.toolset.yaml` discriminator — toolset YAMLs are plain `<id>.yaml`). A
    // workspace that ships only toolsets — no class-backed tools, no shortcuts/trailheads —
    // must still disable the fast-path so dispatch defers to the daemon. Without this case,
    // the false-rejection envelope kicks in for any tool name that's only reachable via a
    // workspace-defined toolset.
    val workspaceRoot = tempFolder.newFolder("workspace-trailmap-toolset").toPath()
    val configDir = workspaceRoot.resolve("trails/config")
    val toolsetsDir = configDir.resolve("trailmaps/myflow/toolsets")
    java.nio.file.Files.createDirectories(toolsetsDir)
    java.nio.file.Files.writeString(configDir.resolve("trailblaze.yaml"), "")
    java.nio.file.Files.writeString(
      toolsetsDir.resolve("flow_extras.yaml"),
      "id: flow_extras\ntools:\n  - tap\n",
    )
    assertTrue(
      workspaceHasToolDefinitions(workspaceRoot),
      "A workspace with only a toolset (`<config>/trailmaps/<id>/toolsets/<name>.yaml`) " +
        "must disable the fast-path. Pre-fix the helper matched only `.tool.yaml` and " +
        "`.toolset.yaml` suffixes, missing plain-`.yaml` toolsets entirely.",
    )
  }

  @Test
  fun `workspaceHasToolDefinitions returns false when only shortcut and trailhead YAMLs exist`() {
    // Inverse contract: a workspace shipping ONLY shortcuts/trailheads (no `.tool.yaml`
    // and no toolset YAMLs) must NOT disable the fast-path. Shortcuts and trailheads are
    // navigation operations referencing existing tool names — they don't register new
    // dispatch surfaces by themselves. Including them in the detection would false-detect
    // any workspace shipping waypoint navigation primitives (e.g. a `calendar` trailmap
    // with a `shortcuts/` directory), regressing the OOBE typo-detection case for those
    // users.
    val workspaceRoot = tempFolder.newFolder("workspace-trailmap-shortcut-only").toPath()
    val configDir = workspaceRoot.resolve("trails/config")
    val shortcutsDir = configDir.resolve("trailmaps/myflow/shortcuts")
    val trailheadsDir = configDir.resolve("trailmaps/myflow/trailheads")
    java.nio.file.Files.createDirectories(shortcutsDir)
    java.nio.file.Files.createDirectories(trailheadsDir)
    java.nio.file.Files.writeString(configDir.resolve("trailblaze.yaml"), "")
    java.nio.file.Files.writeString(
      shortcutsDir.resolve("flow_hop.shortcut.yaml"),
      "id: flow_hop\ndescription: hop\nshortcut:\n  from: a\n  to: b\ntools:\n  - tap: { selector: ok }\n",
    )
    java.nio.file.Files.writeString(
      trailheadsDir.resolve("flow_start.trailhead.yaml"),
      "id: flow_start\ndescription: start\ntrailhead:\n  to: a\ntools:\n  - tap: { selector: ok }\n",
    )
    assertFalse(
      workspaceHasToolDefinitions(workspaceRoot),
      "Shortcut-/trailhead-only workspaces must keep the fast-path enabled. " +
        "If this regresses, every workspace shipping waypoint navigation primitives loses " +
        "OOBE typo detection.",
    )
  }

  @Test
  fun `known tool name does not short-circuit`() {
    // Inverse contract: a name that IS on the classpath must fall through to
    // `cliReusableWithDevice`. We can't run the full call to completion in a unit
    // test (no daemon, no device), so we just assert the local check passed by
    // confirming the resolver knows the name. If this ever returns false, the
    // fast-path will start eating legitimate calls and producing MISUSE for
    // perfectly valid tool names.
    val resolver = xyz.block.trailblaze.config.ToolNameResolver.fromBuiltInAndCustomTools()
    assertTrue(
      resolver.isKnown("tap"),
      "`tap` is the canonical Maestro tool name and must be classpath-known; " +
        "if this fails, either the toolset YAML discovery is broken or the test " +
        "classpath is missing trailblaze-common's tool registrations",
    )
  }
}
