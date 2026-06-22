package xyz.block.trailblaze.host

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
 *     parsing the per-driver section (plus the driver-agnostic scripted-tools section) out of
 *     the rendered Markdown.
 *  4. Asserting the two tool-name sets are identical.
 *
 * The compared surface is the full set the LLM sees: class-backed, YAML-defined, AND scripted
 * (`.ts` / `.js`) tools delivered by a toolset. Scripted tools matter here because converting a
 * tool from class-backed to scripted (as PR #3803 did for `openUrl`) is exactly the kind of
 * change that can drop it from one compositor but not the other.
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

  @Test
  fun `excluding a toolset-delivered scripted tool removes it from resolver and report`() {
    // Baseline: without `excluded_tools:`, openUrl — a scripted (`.ts`) tool delivered by the
    // always-enabled `core_interaction` toolset — surfaces in BOTH the resolver and the report
    // under android. (If it didn't, this fixture couldn't catch exclusion drift.)
    val withScripted = assertPrimitivesMatch(
      targetId = "primitives_scripted_present",
      platformKey = "android",
      toolSets = listOf("core_interaction", "memory"),
      driverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
    )
    assertTrue(
      "baseline fixture must surface the scripted tool `openUrl`, else it can't catch " +
        "exclusion drift. Got: $withScripted",
    ) { "openUrl" in withScripted }

    // With `excluded_tools: [openUrl]`, the scripted tool must disappear from BOTH the live
    // resolver and the report — and the two must still agree (the drift guard's whole point).
    // Before the scripted-exclusion wiring, `excluded_tools: [openUrl]` was honored for class /
    // YAML tools but silently ignored for toolset-delivered scripted tools, so `openUrl` stayed
    // advertised.
    val withExclusion = assertPrimitivesMatch(
      targetId = "primitives_scripted_excluded",
      platformKey = "android",
      toolSets = listOf("core_interaction", "memory"),
      driverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
      excludedTools = listOf("openUrl"),
    )
    assertFalse(
      "openUrl was excluded via `excluded_tools:` — it must be absent from both the resolver and " +
        "the report. Got: $withExclusion",
    ) { "openUrl" in withExclusion }
    // Sanity: excluding one scripted tool must not drop its sibling core_interaction tools
    // (e.g. the class-backed `tap`) — we excluded a tool, not the whole toolset.
    assertTrue(
      "excluding openUrl must not drop sibling core_interaction tools like `tap`. Got: $withExclusion",
    ) { "tap" in withExclusion }
  }

  /**
   * Build the same (target, driver) two ways — once through the live
   * [TrailblazeHostAppTarget.getAgentToolboxForDriver] extension, once through
   * [ResolvedTargetReportEmitter.emit] — and assert the tool-name sets are identical. Returns the
   * agreed-upon tool-name set (the two are equal once the assertion passes) so callers can make
   * further membership assertions (e.g. that an `excluded_tools:` entry really vanished from both).
   * Shared by every per-driver case so a new driver can be added with one method call.
   */
  private fun assertPrimitivesMatch(
    targetId: String,
    platformKey: String,
    toolSets: List<String>,
    driverType: TrailblazeDriverType,
    excludedTools: List<String>? = null,
  ): Set<String> {
    val platforms = mapOf(
      platformKey to PlatformConfig(
        appIds = listOf("com.example.$targetId"),
        toolSets = toolSets,
        excludedTools = excludedTools,
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
        liveToolbox.yamlToolNames.map { it.toolName } +
        // Scripted (`.ts` / `.js`) tools the toolsets delivered (e.g. `openUrl` via
        // `core_interaction`). The LLM sees these too, so the drift guard compares them — this is
        // the surface that diverged after `openUrl` became a scripted tool in PR #3803.
        liveToolbox.scriptedToolNames.map { it.toolName }
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
    // Equal by the assertion above; return one of them so callers can assert membership.
    return liveNames
  }

  /**
   * Extracts the LLM-visible tool names the report renders for [platform] / [driverKey] inside the
   * "Agent toolbox" section. That surface is the union of two report blocks:
   *
   *  - **`#### Driver <key>` bullets** — the per-(platform, driver) class-backed, YAML-defined, and
   *    `tools:`-addition tools.
   *  - **`### Scripted tools` bullets** — toolset-delivered + target-root scripted (`.ts` / `.js`)
   *    tools (e.g. `openUrl`). The report renders these once below the per-driver slices because it
   *    treats them as driver-agnostic; for the single-(platform, driver) fixtures this helper builds
   *    they belong to the one driver under test.
   *
   * Both block kinds are walked heading-by-heading so the scripted section is captured intentionally
   * rather than by the prior implementation's slice bleeding past the last `#### Driver` block. Linkified
   * rows like `- [\`web_click\`](primitives/tools/web_click.md)` and bare `- \`web_click\`` rows both
   * match; the `(YAML-defined)`, `(individual tools: addition)`, and scripted-origin suffixes are
   * ignored — only the tool name matters.
   */
  private fun extractToolNamesForDriver(report: String, platform: String, driverKey: String): Set<String> {
    val toolboxSection = report
      .substringAfter("## Agent toolbox (what the LLM sees at session start)")
      .substringBefore("## Resolution trace")
    // Slice to just the requested platform's block — between `### Platform <p>` and the next
    // `### Platform` — for the per-driver bullets.
    val platformSlice = toolboxSection
      .substringAfter("### Platform `$platform`")
      .substringBefore("### Platform `")
    // (a) Per-(platform, driver) bullets — class-backed + YAML + `tools:` additions, scoped to the
    //     requested driver's block within the platform slice.
    val driverNames = bulletsUnderHeading(platformSlice) { it.startsWith("#### Driver `$driverKey`") }
    // (b) Driver-agnostic scripted-tools section. Parsed from the FULL toolbox section, NOT the
    //     platform slice: the report renders `### Scripted tools` once, after every `### Platform`
    //     block, so slicing to a non-last platform would miss it (Copilot review on PR #3851).
    val scriptedNames = bulletsUnderHeading(toolboxSection) { it.startsWith("### Scripted tools") }
    return driverNames + scriptedNames
  }

  /**
   * Collects linkified (`- [\`name\`](path)`) and bare (`- \`name\``) bullet tool names that sit
   * under the first heading matching [isWanted], stopping at the next Markdown heading. Suffixes
   * like `(YAML-defined)` / `(individual tools: addition)` / scripted-origin notes are ignored —
   * only the tool name matters.
   *
   * `- \`name\` (excluded)` bullets are skipped: the report renders the "Excluded by
   * `excluded_tools:`" list as bullets directly after the per-driver block (no intervening
   * heading), so a naive capture would scoop those names up — but an excluded tool is by
   * definition NOT part of the LLM surface this helper reconstructs. Skipping the `(excluded)`
   * marker keeps the parsed set to what the agent actually sees, matching `getAgentToolboxForDriver`.
   */
  private fun bulletsUnderHeading(text: String, isWanted: (String) -> Boolean): Set<String> {
    val bulletRegex = Regex("""^- (?:\[`([^`]+)`]\([^)]+\)|`([^`]+)`)""")
    val names = mutableSetOf<String>()
    var capturing = false
    for (rawLine in text.lineSequence()) {
      val line = rawLine.trim()
      if (line.startsWith("#")) {
        capturing = isWanted(line)
        continue
      }
      if (capturing && !line.endsWith("(excluded)")) {
        bulletRegex.find(line)?.let { match ->
          names += match.groupValues[1].ifEmpty { match.groupValues[2] }
        }
      }
    }
    return names
  }
}
