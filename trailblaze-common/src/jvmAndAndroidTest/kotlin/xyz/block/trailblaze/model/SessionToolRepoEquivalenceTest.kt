package xyz.block.trailblaze.model

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import xyz.block.trailblaze.config.AppTargetYamlLoader
import xyz.block.trailblaze.config.ToolNameResolver
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolSetCatalog
import xyz.block.trailblaze.toolcalls.allToolNames
import xyz.block.trailblaze.toolcalls.toolName
import xyz.block.trailblaze.toolcalls.getAgentToolboxForDriver
import xyz.block.trailblaze.toolcalls.logDeclaredToolSetProblemsOnce
import xyz.block.trailblaze.toolcalls.resetDeclaredToolSetProblemReporting
import xyz.block.trailblaze.toolcalls.ResolvedAgentToolbox
import xyz.block.trailblaze.toolcalls.ResolvedToolExclusions
import xyz.block.trailblaze.toolcalls.resolveToolScopeForDriver
import xyz.block.trailblaze.util.Console

/**
 * A (target, driver) pair must get the same tools wherever it runs — on-device rule, host runner,
 * or daemon. They diverged once: the host and daemon composed against the whole catalog while
 * on-device composed against the trailmap, so the device advertised every other app's tools and
 * blew past the providers' 128-tool array cap.
 *
 * The guard against a repeat is structural — one scope ([resolveToolScopeForDriver]), one composer
 * ([toCustomTrailblazeToolsForDriver]), one repo entry point ([toSessionToolRepo]) — and these tests
 * pin the properties that make it safe to keep it that way.
 */
class SessionToolRepoEquivalenceTest {

  private val drivers = listOf(
    TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
    TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
    TrailblazeDriverType.PLAYWRIGHT_NATIVE,
  )

  @Test
  fun `the repo advertises everything the resolver reports, on every driver`() {
    // `getAgentToolboxForDriver` is what the `trailblaze check` report, discovery, and the CLI all
    // answer "what does the LLM see?" with. If a session's repo can't advertise one of those names,
    // the report is lying about that runtime.
    for (driver in drivers) {
      val target = multiDriverTarget()
      val advertised = target.getAgentToolboxForDriver(driver).allToolNames
      val repo = target.toSessionToolRepo(driver)
      val registered = repo.getRegisteredTrailblazeTools().map { it.toolName() }.toSet() +
        repo.getRegisteredYamlToolNames() +
        repo.getRegisteredScriptedToolNames() +
        repo.allCatalogScriptedToolNames

      val missing = advertised - registered
      assertTrue(
        missing.isEmpty(),
        "driver=${driver.yamlKey}: the resolver advertises ${missing.map { it.toolName }} but the " +
          "session repo can't offer them. registered=${registered.map { it.toolName }.sorted()}",
      )
    }
  }

  @Test
  fun `a target that declares no toolsets is unconfigured, and keeps the whole catalog`() {
    // `getDeclaredToolSetIdsForDriver` can't distinguish "unset" from "declared empty", so empty
    // has to mean unset. Reading it as "declare nothing" collapses the session to `always_enabled`
    // — no verification, no navigation — and that is reachable: `DefaultTrailblazeHostAppTarget`
    // (the discovery fallback) declares nothing, as does any target with no `platforms.web` block
    // running on a web driver.
    val target = AppTargetYamlLoader.loadFromYaml(
      """
      id: bare
      display_name: Bare
      platforms:
        android:
          app_ids:
            - com.example.bare
      """.trimIndent(),
      toolNameResolver = ToolNameResolver.fromBuiltInAndCustomTools(),
    )
    val driver = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION
    val scope = target.resolveToolScopeForDriver(driver)

    assertTrue(scope.declaredToolSetIds.isEmpty(), "fixture declares no tool_sets")
    assertFalse(scope.isScoped, "an empty declaration is unconfigured, not an empty scope")

    // `assertNotVisibleWithText` comes from `verification`, which is driver-compatible but NOT
    // always-enabled. An unconfigured target must still get it, or it cannot verify anything.
    val repo = target.toSessionToolRepo(driver)
    val names = repo.getRegisteredTrailblazeTools().map { it.toolName() }.toSet()
    assertTrue(
      ToolName("assertNotVisibleWithText") in names,
      "an unconfigured target must keep the driver catalog: ${names.map { it.toolName }.sorted()}",
    )

    // ...and the ADVERTISED view has to agree. This is the half that was broken: the fallback lived
    // only in the repo composer, so `getAgentToolboxForDriver` — which the `trailblaze check`
    // report, toolbox discovery, the CLI, and the daemon's `tools/list` all answer from — collapsed
    // an unconfigured target to `always_enabled` alone while its session repo held the whole
    // catalog. The agent was offered a surface that could not verify or navigate, and advertise and
    // dispatch disagreed again one layer below the seam this PR unified.
    val advertised = target.getAgentToolboxForDriver(driver).toolClasses.map { it.toolName() }.toSet()
    assertTrue(
      ToolName("assertNotVisibleWithText") in advertised,
      "an unconfigured target must ADVERTISE the driver catalog, not just be able to dispatch it: " +
        "${advertised.map { it.toolName }.sorted()}",
    )
  }

  @Test
  fun `an unconfigured target resolves the same surface as one with no target at all`() {
    // Both mean "nothing narrowed this session". They are resolved by different code paths — a
    // real scope vs. the nullable-receiver fallback — so pin that they land in the same place.
    val driver = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION
    val unconfigured = AppTargetYamlLoader.loadFromYaml(
      """
      id: bare2
      display_name: Bare Two
      platforms:
        android:
          app_ids:
            - com.example.bare2
      """.trimIndent(),
      toolNameResolver = ToolNameResolver.fromBuiltInAndCustomTools(),
    ).toSessionToolRepo(driver)
    val noTarget = (null as TrailblazeHostAppTarget?).toSessionToolRepo(driver)

    assertEquals(
      noTarget.getRegisteredTrailblazeTools(),
      unconfigured.getRegisteredTrailblazeTools(),
      "unconfigured target vs no target, class-backed",
    )
    assertEquals(
      noTarget.getRegisteredYamlToolNames(),
      unconfigured.getRegisteredYamlToolNames(),
      "unconfigured target vs no target, YAML",
    )
  }

  @Test
  fun `runtime-contributed tools reach the repo on all three backings`() {
    // The `additional:` seam carries the daemon's OTHER bound targets and the host's driver-specific
    // web classes. Only its negative case (exclusion wins) was pinned, so the seam could have been
    // dropped entirely and the suite would still pass. Dropping the YAML/scripted halves is exactly
    // what made sibling-target tools dispatch as "Unknown tool".
    val driver = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION
    val extraClass = xyz.block.trailblaze.toolcalls.commands.ObjectiveStatusTrailblazeTool::class
    val extraYaml = ToolName("someRuntimeContributedYamlTool")
    val extraScripted = ToolName("someRuntimeContributedScriptedTool")

    val repo = multiDriverTarget().toSessionToolRepo(
      driverType = driver,
      additional = ResolvedAgentToolbox(setOf(extraClass), setOf(extraYaml), setOf(extraScripted)),
    )

    assertTrue(
      extraClass in repo.getRegisteredTrailblazeTools(),
      "runtime-contributed class-backed tool must reach the repo",
    )
    assertTrue(
      extraYaml in repo.getRegisteredYamlToolNames(),
      "runtime-contributed YAML tool must reach the repo",
    )
    assertTrue(
      extraScripted in repo.getRegisteredScriptedToolNames(),
      "runtime-contributed scripted tool must reach the repo",
    )
  }

  @Test
  fun `an excluded tool stays decodable so recorded trails still parse`() {
    // Exclusion narrows what the session can be ASKED to do; it must not narrow what the YAML
    // decoder can READ. `registeredAppSpecific*` deliberately skip the subtraction for this reason.
    // Subtracting there turns "declared but not advertised" into "recorded trail fails to parse" —
    // a failure that surfaces far from the target config that caused it.
    val driver = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION
    val target = multiDriverTarget()
    val excluded = target.resolveToolScopeForDriver(driver).excluded.toolClasses
    assertTrue(excluded.isNotEmpty(), "fixture must exclude at least one class-backed tool")

    // Contribute the excluded class as a runtime tool so it is in the custom set the decoder
    // registry is built from — otherwise this asserts nothing about the subtraction.
    val tools = target.toCustomTrailblazeToolsForDriver(
      driverType = driver,
      additional = ResolvedAgentToolbox(excluded, emptySet(), emptySet()),
    )

    assertEquals(
      emptySet(),
      tools.toTrailblazeToolRepo().getRegisteredTrailblazeTools().intersect(excluded),
      "an excluded tool must not be dispatchable",
    )
    assertTrue(
      tools.allForSerializationTools().containsAll(excluded),
      "an excluded tool must remain in the serialization registry, or recorded trails that " +
        "reference it stop parsing entirely",
    )
  }

  @Test
  fun `a runtime-excluded tool stays decodable so recorded trails still parse`() {
    // The mirror of the target's own case, for the `additionalExclusions` seam. The daemon reports
    // a sibling target's opt-outs this way, and a sibling's excluded tool must keep the same
    // property the active target's has: not dispatchable, still decodable. Subtracting it upstream
    // of `additional` instead — which is what the daemon did — drops it from the decoder registry
    // and turns a recorded trail that names it from "not advertised" into "fails to parse".
    val driver = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION
    val siblingTool = xyz.block.trailblaze.toolcalls.commands.ObjectiveStatusTrailblazeTool::class

    val tools = multiDriverTarget().toCustomTrailblazeToolsForDriver(
      driverType = driver,
      additional = ResolvedAgentToolbox(setOf(siblingTool), emptySet(), emptySet()),
      additionalExclusions = ResolvedToolExclusions(setOf(siblingTool), emptySet(), emptySet()),
    )

    assertFalse(
      siblingTool in tools.toTrailblazeToolRepo().getRegisteredTrailblazeTools(),
      "a runtime-excluded tool must not be dispatchable",
    )
    assertTrue(
      siblingTool in tools.allForSerializationTools(),
      "a runtime-excluded tool must stay in the serialization registry, or a recorded trail that " +
        "references it stops parsing entirely",
    )
  }

  @Test
  fun `the scope carries its target id`() {
    val scope = multiDriverTarget()
      .resolveToolScopeForDriver(TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION)
    // The fixture's id and display_name differ, so this catches an id/displayName mix-up.
    assertEquals("equivapp", scope.targetId)
  }

  @Test
  fun `a typo'd toolset id is reported, a good one is not`() {
    val driver = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION

    val clean = multiDriverTarget().resolveToolScopeForDriver(driver)
    assertEquals(
      emptyList(),
      clean.declaredToolSetProblems,
      "a target whose declared toolsets all resolve must report nothing",
    )

    val typo = AppTargetYamlLoader.loadFromYaml(
      """
      id: typoapp
      display_name: Typo App
      platforms:
        android:
          app_ids:
            - com.example.typoapp
          tool_sets:
            - verification
            - no_such_toolset_at_all
      """.trimIndent(),
      toolNameResolver = ToolNameResolver.fromBuiltInAndCustomTools(),
    ).resolveToolScopeForDriver(driver)

    assertTrue(
      typo.declaredToolSetProblems.any { "no_such_toolset_at_all" in it },
      "an id with no catalog entry must be named: ${typo.declaredToolSetProblems}",
    )
  }

  @Test
  fun `a scope whose declared toolsets all fail says the session is unusable`() {
    // Distinct from "one id was bad": nothing resolved, and a scoped target does NOT fall back to
    // the whole catalog, so the agent is left with always-enabled tools alone. The per-id lines
    // read as partial degradation and would understate that.
    val scope = AppTargetYamlLoader.loadFromYaml(
      """
      id: allbadapp
      display_name: All Bad App
      platforms:
        android:
          app_ids:
            - com.example.allbadapp
          tool_sets:
            - nope_one
            - nope_two
      """.trimIndent(),
      toolNameResolver = ToolNameResolver.fromBuiltInAndCustomTools(),
    ).resolveToolScopeForDriver(TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION)

    assertTrue(scope.isScoped, "precondition: declaring bad ids still counts as scoped")
    assertTrue(
      scope.declaredToolSetProblems.any { "NONE of them resolved" in it },
      "all-ids-bad must be called out on its own: ${scope.declaredToolSetProblems}",
    )
  }

  @Test
  fun `a problem is emitted once, then deduped, and re-armed by a reset`() {
    // The dedupe and the re-arm are the parts the `declaredToolSetProblems` assertions above can't
    // see: those pin the message content, and an emitter that fired on every call — or a reset that
    // did nothing — would leave every one of them green while the per-request spam came back.
    // The id is unique to this test so its dedupe keys can't collide with another test's.
    val scope = AppTargetYamlLoader.loadFromYaml(
      """
      id: warnonceapp
      display_name: Warn Once App
      platforms:
        android:
          app_ids:
            - com.example.warnonceapp
          tool_sets:
            - no_such_toolset_for_warn_once
      """.trimIndent(),
      toolNameResolver = ToolNameResolver.fromBuiltInAndCustomTools(),
    ).resolveToolScopeForDriver(TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION)
    resetDeclaredToolSetProblemReporting()

    val first = captureConsole { scope.logDeclaredToolSetProblemsOnce() }
    val repeat = captureConsole { scope.logDeclaredToolSetProblemsOnce() }
    val afterReset = captureConsole {
      resetDeclaredToolSetProblemReporting()
      scope.logDeclaredToolSetProblemsOnce()
    }

    assertTrue(
      "no_such_toolset_for_warn_once" in first,
      "the first call must name the broken toolset: $first",
    )
    assertEquals(
      "",
      repeat.trim(),
      "a repeat must stay silent — this is the per-request noise the dedupe exists to stop",
    )
    assertTrue(
      "no_such_toolset_for_warn_once" in afterReset,
      "a reset must re-arm reporting, or a session started later never hears about it: $afterReset",
    )
  }

  @Test
  fun `re-registering workspace toolsets re-arms reporting`() {
    // A new session is not the only thing that changes what a declared id resolves to. Replacing
    // the workspace overlay does too, and discovery re-runs in a live daemon (Trail Runner's
    // create-target flow), so a developer who reintroduces the same typo without reconnecting has
    // to hear about it again.
    val scope = AppTargetYamlLoader.loadFromYaml(
      """
      id: overlayrearmapp
      display_name: Overlay Rearm App
      platforms:
        android:
          app_ids:
            - com.example.overlayrearmapp
          tool_sets:
            - no_such_toolset_for_overlay_rearm
      """.trimIndent(),
      toolNameResolver = ToolNameResolver.fromBuiltInAndCustomTools(),
    ).resolveToolScopeForDriver(TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION)
    resetDeclaredToolSetProblemReporting()

    try {
      val first = captureConsole { scope.logDeclaredToolSetProblemsOnce() }
      val afterOverlaySwap = captureConsole {
        TrailblazeToolSetCatalog.registerWorkspaceToolSets(emptyList())
        scope.logDeclaredToolSetProblemsOnce()
      }

      assertTrue(
        "no_such_toolset_for_overlay_rearm" in first,
        "precondition: the first call reports: $first",
      )
      assertTrue(
        "no_such_toolset_for_overlay_rearm" in afterOverlaySwap,
        "replacing the workspace overlay must re-arm reporting: $afterOverlaySwap",
      )
    } finally {
      // The overlay is process-wide; leaving a test's version installed would leak into others.
      TrailblazeToolSetCatalog.registerWorkspaceToolSets(emptyList())
    }
  }

  /**
   * Runs [block] with every `PrintStream` sink on [Console] pointed at a buffer, and returns what
   * was written.
   *
   * Selected by field type rather than by name because the sinks differ per platform, and this
   * source set compiles for both: the JVM actual splits `out` (`log`) from `userOut` (`info`),
   * while the Android actual has only `out` and routes `info` through `log`.
   */
  private fun captureConsole(block: () -> Unit): String {
    val buffer = ByteArrayOutputStream()
    val stream = PrintStream(buffer, /* autoFlush = */ true, Charsets.UTF_8)
    val originals = Console::class.java.declaredFields
      .filter { it.type == PrintStream::class.java }
      .map { field ->
        field.isAccessible = true
        field to field.get(Console) as PrintStream
      }
    originals.forEach { (field, _) -> field.set(Console, stream) }
    try {
      block()
    } finally {
      originals.forEach { (field, original) -> field.set(Console, original) }
    }
    return String(buffer.toByteArray(), Charsets.UTF_8)
  }

  @Test
  fun `a runtime's own exclusions are honored alongside the target's`() {
    // The host JUnit harness suppresses specific classes for its own reasons, on top of whatever
    // the target excludes. Both sources must remove a tool, or routing that harness through the
    // shared entry point would silently re-admit tools it had removed for years.
    val driver = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION
    val target = multiDriverTarget()
    val harnessExcluded = target.toSessionToolRepo(driver)
      .getRegisteredTrailblazeTools()
      .first()

    val repo = target.toSessionToolRepo(
      driverType = driver,
      additionalExclusions =
        ResolvedToolExclusions(setOf(harnessExcluded), emptySet(), emptySet()),
    )
    assertFalse(
      harnessExcluded in repo.getRegisteredTrailblazeTools(),
      "a runtime-imposed exclusion must remove the tool even though the target didn't exclude it",
    )
  }

  @Test
  fun `the session repo does not register tools from undeclared toolsets`() {
    // The regression this whole line of work exists to prevent is OVER-inclusion: a session
    // offering every other app's tools and blowing the 128-tool API cap. `missing` above only
    // catches under-inclusion, so this is the direction that matters.
    val driver = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION
    val scoped = multiDriverTarget().toSessionToolRepo(driver)
    val wholeCatalog = TrailblazeToolRepo.withDynamicToolSets(driverType = driver)

    val scopedNames = scoped.getRegisteredTrailblazeTools().map { it.toolName() }.toSet()
    val catalogNames = wholeCatalog.getRegisteredTrailblazeTools().map { it.toolName() }.toSet()
    assertTrue(
      scopedNames.size < catalogNames.size,
      "a target declaring one toolset must resolve to fewer tools than the whole driver catalog " +
        "(scoped=${scopedNames.size}, catalog=${catalogNames.size}) — if these are equal, some " +
        "runtime is composing against the catalog again",
    )
  }

  @Test
  fun `a session with no target keeps the whole-catalog surface`() {
    // The one case where whole-catalog is correct: nothing to scope to. Pinned so a future change
    // can't quietly make a target-less `trailblaze run` resolve to nothing.
    val driver = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION
    val repo = (null as TrailblazeHostAppTarget?).toSessionToolRepo(driver)
    val catalogRepo = TrailblazeToolRepo.withDynamicToolSets(driverType = driver)

    // Set equality, not `isNotEmpty()`: a regression to an always-enabled-only surface would still
    // be non-empty, and that is exactly the failure mode worth catching here.
    assertEquals(
      catalogRepo.getRegisteredTrailblazeTools(),
      repo.getRegisteredTrailblazeTools(),
      "no target must yield the whole driver-compatible catalog, class-backed",
    )
    assertEquals(
      catalogRepo.getRegisteredYamlToolNames(),
      repo.getRegisteredYamlToolNames(),
      "no target must yield the whole driver-compatible catalog, YAML",
    )
    assertEquals(
      catalogRepo.allCatalogScriptedToolNames,
      repo.allCatalogScriptedToolNames,
      "no target must yield the whole driver-compatible catalog, scripted",
    )
  }

  @Test
  fun `additional runtime tool classes are still subject to the target's exclusions`() {
    // The host contributes driver-specific web classes the target's YAML can't name. Exclusion must
    // still win, or `excluded_tools:` means something different on host than on device.
    val target = multiDriverTarget()
    val driver = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION
    val excluded = target.resolveToolScopeForDriver(driver).excluded.toolClasses
    assertTrue(excluded.isNotEmpty(), "fixture must exclude at least one class-backed tool")

    val repo = target.toSessionToolRepo(
      driverType = driver,
      additional = ResolvedAgentToolbox(excluded, emptySet(), emptySet()),
    )
    assertEquals(
      emptySet(),
      repo.getRegisteredTrailblazeTools().intersect(excluded),
      "a runtime-contributed class that the target excludes must not reach the repo",
    )
  }

  private fun multiDriverTarget() = AppTargetYamlLoader.loadFromYaml(
    """
    id: equivapp
    display_name: Equiv App
    platforms:
      android:
        app_ids:
          - com.example.equivapp
        tool_sets:
          - verification
        excluded_tools:
          - tapOnPoint
      web:
        app_ids:
          - equiv-web
    """.trimIndent(),
    toolNameResolver = ToolNameResolver.fromBuiltInAndCustomTools(),
  )
}
