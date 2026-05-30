package xyz.block.trailblaze.host

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.block.trailblaze.config.AppTargetYamlConfig
import xyz.block.trailblaze.config.PlatformConfig
import xyz.block.trailblaze.config.ToolNameResolver
import xyz.block.trailblaze.config.YamlBackedHostAppTarget
import xyz.block.trailblaze.config.project.TrailmapSource
import xyz.block.trailblaze.config.project.TrailmapTargetConfig
import xyz.block.trailblaze.config.project.ResolvedTrailmap
import xyz.block.trailblaze.config.project.TrailblazeTrailmapManifest
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.toolcalls.getAgentToolboxForDriver

/**
 * Drift guard for the per-(target, driver) agent-toolbox primitives.
 *
 * Two independent callers compose "what does the LLM see at session start?":
 *
 *  - **The report** (`ResolvedTargetReportEmitter.computeAgentToolbox`) renders each
 *    (platform, driver) slice into Markdown for `<id>.report.md`.
 *  - **The runtime** (`TrailblazeMcpServer`'s inner-agent-tools-provider lambda + the
 *    canonical [TrailblazeHostAppTarget.getAgentToolboxForDriver] extension) feeds the
 *    LLM at session start.
 *
 * This test pins those two paths to the same primitive resolution by:
 *
 *  1. Building a synthetic [AppTargetYamlConfig] declaring `web` + `playwright-native`
 *     with the framework's `web_core` + `memory` toolsets.
 *  2. Constructing a [YamlBackedHostAppTarget] from the same config and calling
 *     [TrailblazeHostAppTarget.getAgentToolboxForDriver].
 *  3. Running [ResolvedTargetReportEmitter.emit] against the same config + trailmap and
 *     parsing the per-driver section out of the rendered Markdown.
 *  4. Asserting the two tool-name sets are identical.
 *
 * Diverging here is the source of the bug pinned by
 * `docs/internal/devlog/2026-05-22-agent-toolbox-report-driver-leak.md` — a future
 * refactor that adds or moves a filter in only one of the two compositors would
 * silently re-introduce the leak. The set-equality assertion here catches that
 * before any user-visible report regenerates with the wrong shape.
 *
 * **Deferred:** the third composition site (`TrailblazeMcpServer`'s inner-agent-tools-
 * provider lambda) is also expected to share these primitives, but its bridge-vs-target
 * switch (when the bridge supplies its own tool classes for Playwright/Compose/Revyl)
 * means a direct three-way assertion needs server bootstrap. Tracked alongside the
 * remaining handoff items from PR #3315.
 */
class AgentToolboxPrimitivesMatchTest {

  private val tempDirs = mutableListOf<File>()

  @AfterTest
  fun cleanup() {
    tempDirs.forEach { it.deleteRecursively() }
    tempDirs.clear()
  }

  private fun newDir(name: String): File {
    val parent = createTempDirectory("primitives-match-test").toFile()
    tempDirs += parent
    return File(parent, name).apply { mkdirs() }
  }

  @Test
  fun `report toolbox matches getAgentToolboxForDriver under playwright-native`() {
    assertPrimitivesMatch(
      targetId = "primitives_web_pw",
      platformKey = "web",
      toolSets = listOf("web_core", "memory"),
      driverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
    )
  }

  @Test
  fun `report toolbox matches getAgentToolboxForDriver under android-ondevice-instrumentation`() {
    // Originally the driver-leak bug bit Android-bound tools leaking onto web targets;
    // adding the symmetric Android case here catches the dual failure mode (e.g. web-only
    // tools accidentally appearing under an Android driver).
    assertPrimitivesMatch(
      targetId = "primitives_android",
      platformKey = "android",
      toolSets = listOf("core_interaction", "memory"),
      driverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
    )
  }

  @Test
  fun `report toolbox matches getAgentToolboxForDriver under ios-host`() {
    // iOS uses the same `core_interaction` shorthand toolset but routes through a
    // different driver. This case catches drift where the iOS-host path diverges from
    // the report's iOS rendering — historically the third "must not leak Android tools
    // into iOS" failure mode the original devlog called out.
    assertPrimitivesMatch(
      targetId = "primitives_ios",
      platformKey = "ios",
      toolSets = listOf("core_interaction", "memory"),
      driverType = TrailblazeDriverType.IOS_HOST,
    )
  }

  /**
   * Build the same (target, driver) two ways — once through the live
   * [TrailblazeHostAppTarget.getAgentToolboxForDriver] extension, once through
   * [ResolvedTargetReportEmitter.emit] — and assert the tool-name sets are identical.
   * Shared by every per-driver case so a new driver can be added with one method call.
   */
  private fun assertPrimitivesMatch(
    targetId: String,
    platformKey: String,
    toolSets: List<String>,
    driverType: TrailblazeDriverType,
  ) {
    val platforms = mapOf(
      platformKey to PlatformConfig(
        appIds = listOf("com.example.$targetId"),
        toolSets = toolSets,
        drivers = listOf(driverType.yamlKey),
      ),
    )
    val config = AppTargetYamlConfig(
      id = targetId,
      displayName = targetId,
      platforms = platforms,
    )

    // --- Path A: live target primitives (what TrailblazeMcpServer calls at session start) ---
    val target = YamlBackedHostAppTarget(
      config = config,
      toolNameResolver = ToolNameResolver.fromBuiltInAndCustomTools(),
    )
    val liveToolbox = target.getAgentToolboxForDriver(driverType = driverType)
    val liveNames = (
      liveToolbox.toolClasses.map { kclass ->
        // The runtime composes the LLM-visible name via @TrailblazeToolClass annotation.
        // Use the same path the catalog uses internally so the comparison is apples-to-apples.
        kclass.java.getAnnotation(xyz.block.trailblaze.toolcalls.TrailblazeToolClass::class.java)
          .name
      } +
        liveToolbox.yamlToolNames.map { it.toolName }
      ).toSet()

    // --- Path B: report primitives (what trailblaze check renders) ---
    val trailmap = ResolvedTrailmap(
      manifest = TrailblazeTrailmapManifest(
        id = targetId,
        target = TrailmapTargetConfig(displayName = targetId, platforms = platforms),
      ),
      source = TrailmapSource.Filesystem(newDir(targetId)),
      target = config,
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )
    val outDir = newDir("out_$targetId")
    ResolvedTargetReportEmitter.emit(
      resolvedTargets = listOf(config),
      resolvedTrailmaps = listOf(trailmap),
      outputDir = outDir,
    )
    val report = File(outDir, "$targetId.report.md").readText()
    val reportNames = extractToolNamesForDriver(report, platform = platformKey, driverKey = driverType.yamlKey)

    // --- Drift guard ---
    assertTrue(
      "report should list at least one tool under $platformKey/${driverType.yamlKey}; the test " +
        "fixture won't catch drift if the resolver returns empty here. Got:\nliveNames=$liveNames\n" +
        "reportNames=$reportNames\nreport excerpt:\n$report",
    ) {
      reportNames.isNotEmpty()
    }
    assertEquals(
      liveNames,
      reportNames,
      "Report's per-driver toolbox MUST equal getAgentToolboxForDriver's result on " +
        "$platformKey/${driverType.yamlKey} — if these drift, the report misleads about what " +
        "the LLM sees. liveOnly=${(liveNames - reportNames).sorted()}, " +
        "reportOnly=${(reportNames - liveNames).sorted()}.",
    )
  }

  /**
   * Extracts the bullet-list tool names rendered under the `#### Driver <key>` heading
   * inside the report's "Agent toolbox" section for [platform]. Linkified rows like
   * `- [\`web_click\`](primitives/tools/web_click.md)` and bare `- \`web_click\`` rows
   * both match; the `(YAML-defined)` and `(individual tools: addition)` suffixes are
   * preserved as separate row variants but the tool name is the same.
   */
  private fun extractToolNamesForDriver(report: String, platform: String, driverKey: String): Set<String> {
    val toolboxSection = report
      .substringAfter("## Agent toolbox (what the LLM sees at session start)")
      .substringBefore("## Resolution trace")
    // Slice to just the requested platform's block — between `### Platform <p>` and the
    // next `### ` (or the section's end).
    val platformSlice = toolboxSection
      .substringAfter("### Platform `$platform`")
      .substringBefore("### Platform `")
    // Slice further to the requested driver block — between `#### Driver <d>` and the
    // next `#### ` (or end of platform slice).
    val driverSlice = platformSlice
      .substringAfter("#### Driver `$driverKey`")
      .substringBefore("#### Driver `")
    val bulletRegex = Regex("""^- (?:\[`([^`]+)`]\([^)]+\)|`([^`]+)`)""")
    return driverSlice.lineSequence()
      .mapNotNull { line ->
        val trimmed = line.trim()
        bulletRegex.find(trimmed)?.let { match ->
          match.groupValues[1].ifEmpty { match.groupValues[2] }
        }
      }
      .toSet()
  }
}
