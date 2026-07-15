package xyz.block.trailblaze.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import xyz.block.trailblaze.model.TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.util.Console
import java.util.concurrent.Callable

/**
 * Outcome of rendering a single `toolbox` daemon response. `OK` is the success
 * path — the catalogue or a single tool's details was printed. `NOT_FOUND` is
 * the "asked about something that isn't there" envelope (unknown `--name`,
 * zero-match `--search` or role filter, unknown `--target`, malformed daemon
 * JSON). The mapping to [TrailblazeExitCode] is on the enum so [ToolboxCommand]
 * and the test suite share one source of truth.
 */
internal enum class ToolboxRenderOutcome(val exitCode: Int) {
  OK(TrailblazeExitCode.SUCCESS.code),
  NOT_FOUND(TrailblazeExitCode.MISUSE.code),
}

/**
 * Discover available Trailblaze tools.
 *
 * Shows what tools are available for the current target and platform,
 * organized by category. Does not require a device connection.
 *
 * Examples:
 *   trailblaze toolbox                           - Show tool index
 *   trailblaze toolbox --detail                  - Show full parameter descriptions
 *   trailblaze toolbox --name tap                - Show single tool details
 *   trailblaze toolbox --target default          - Show tools for a target app
 */
@Command(
  name = "toolbox",
  mixinStandardHelpOptions = true,
  description = ["Browse available tools by target app and platform"],
)
class ToolboxCommand : Callable<Int> {

  @CommandLine.Parameters(
    index = "0",
    arity = "0..1",
    paramLabel = "ROLE",
    description = [
      "Optional role filter — show only tools tagged with this role.",
      "Valid values: trailheads, shortcuts.",
      "  trailheads — tools that bring the device to a known starting state " +
        "(launch + sign-in, deep-link, etc.). Use one at the start of every trail.",
      "  shortcuts  — tools that jump between named waypoints during a trail.",
      "Omit to show everything; trailheads and shortcuts will be called out as " +
        "headline sections above the toolset listing.",
    ],
  )
  var role: String? = null

  @Option(
    names = ["--name", "-n"],
    description = ["Show details for a single tool by name"],
  )
  var name: String? = null

  @Option(
    names = ["--target", "-t"],
    description = [
      "Target app to show tools for. Optional — defaults to \$TRAILBLAZE_TARGET " +
        "(per-shell pin), then the workspace `trailblaze config target`, falling " +
        "back to the built-in 'default'.",
    ],
  )
  var target: String? = null

  @Option(
    names = ["--search", "-s"],
    description = ["Substring search on tool name and description."],
  )
  var search: String? = null

  @Option(
    names = ["-d", "--device"],
    description = [DEVICE_OPTION_DESCRIPTION],
  )
  var device: String? = null

  @Option(
    names = ["--detail"],
    description = ["Show full parameter descriptions for all tools"],
  )
  var detail: Boolean = false

  @Option(
    names = ["-v", "--verbose"],
    description = ["Enable verbose output"],
  )
  var verbose: Boolean = false

  override fun call(): Int {
    // Validate the optional positional role filter early — surface usage rather than letting
    // a typo silently render the unfiltered grouped view.
    role?.let { r ->
      if (r.lowercase() !in setOf("trailheads", "shortcuts")) {
        Console.error("Unknown role '$r'. Valid roles: trailheads, shortcuts.")
        Console.error("")
        Console.error("Examples:")
        Console.error("  trailblaze toolbox trailheads --device android --target square")
        Console.error("  trailblaze toolbox shortcuts --device android --target square")
        Console.error("  trailblaze toolbox --device android --target square    # everything")
        return TrailblazeExitCode.MISUSE.code
      }
    }

    // --name lookups work without --device (asking about a specific tool wherever it's
    // defined). Every other mode needs --device so the listing matches what's runnable on
    // a real platform; --target is optional and resolved from workspace config below.
    //
    // Resolution chain for non-name modes: explicit --device flag → TRAILBLAZE_DEVICE env
    // (set by `eval $(trailblaze device connect <platform>)`) → autodetect when exactly
    // one device is connected. The autodetect tier closes the OOBE gap for single-device
    // users: `trailblaze toolbox` works with zero setup when only one emulator/sim/browser
    // is connected. `resolveDeviceOrErrorBlocking` emits the right envelope when the
    // chain bottoms out and returns a typed [DeviceResolution] so we exit with MISUSE
    // for 0/multiple-device misuse but INFRA_FAILED when the daemon was unreachable
    // mid-detect.
    val resolvedDevice = if (name != null) {
      // --name mode: device is purely a filter, not a requirement. Use static resolution
      // only — no autodetect noise when the user is asking about a specific tool.
      resolveCliDevice(device)
    } else {
      // On miss the resolver already emitted the envelope; propagate the right
      // exit code (MISUSE for 0/multiple-devices, INFRA_FAILED for daemon down).
      when (val r = resolveDeviceOrErrorBlocking(flag = device, verb = "Toolbox")) {
        is DeviceResolution.Resolved -> r.deviceSpec
        else -> return r.exitCodeFallback()
      }
    }

    // Resolve --target if the user didn't pass one: TRAILBLAZE_TARGET env → the saved
    // selection (`trailblaze config target`) → the workspace's `defaults.target` →
    // built-in default. Matches the daemon's own fallback so toolbox behaves the same
    // on first use — no need to know the word "target" exists to browse the catalogue.
    //
    // `--name X` is platform/target-agnostic (the daemon searches every target for
    // the named tool), so we send no `target` argument at all in that mode and let
    // the daemon's NAME-mode handler decide.
    val resolvedTarget: String?
    val resolvedSource: ResolvedCliTargetSource
    if (name != null && target == null) {
      resolvedTarget = null
      resolvedSource = ResolvedCliTargetSource.Explicit
    } else {
      val resolution = resolveCliTarget(target)
      resolvedTarget = resolution.id
      resolvedSource = resolution.source
    }

    // The resolved-target header is a first-time-discovery affordance for plain
    // `toolbox --device <p>` invocations. We skip it for `--name`, `--search`, and
    // role-filtered modes (`toolbox shortcuts`, `toolbox trailheads`) because those
    // modes already render their own target/platform context line — emitting the
    // header on top would double up the context for users who clearly know what
    // they're looking for. Explicit `--target` always suppresses the header.
    val showResolvedTargetHeader = resolvedSource != ResolvedCliTargetSource.Explicit &&
      name == null && search == null && role == null

    return cliWithDaemon(verbose) { client ->
      val arguments = mutableMapOf<String, Any?>()
      if (name != null) arguments["name"] = name
      if (resolvedTarget != null) arguments["target"] = resolvedTarget
      if (search != null) arguments["search"] = search
      // Send the env-resolved device id (not the raw flag) so the daemon's per-platform
      // tool filter matches whatever the shell pinned via TRAILBLAZE_DEVICE.
      if (resolvedDevice != null) arguments["platform"] = resolvedDevice
      if (detail) arguments["detail"] = true

      val result = client.callTool("toolbox", arguments)

      if (result.isError) {
        Console.error("Error: ${extractErrorMessage(result.content)}")
        return@cliWithDaemon TrailblazeExitCode.INFRA_FAILED.code
      }

      // Banner first — names the resolved (target, platform) so a downstream LLM consumer
      // (Claude / Codex / etc.) reading this output knows the scope of what follows. Emitted
      // before the existing OOBE "Using target:" header so the banner is always the first
      // line, regardless of resolution chain. `--name` mode appends a `tool: <id>` suffix
      // to mark the output as a single-tool drill-down rather than a target-wide listing.
      Console.info(
        ToolboxFormatter.renderToolboxBanner(
          target = resolvedTarget,
          // `?.takeIf { it.isNotBlank() }` keeps a malformed-but-non-null device spec from
          // producing a `(<empty>)` suffix on the banner. The empty-platform branch of
          // `renderToolboxBanner` already handles `null`, so funnel any blank string through
          // the same path rather than relying on a downstream substring check.
          platform = resolvedDevice?.substringBefore('/')?.takeIf { it.isNotBlank() },
          toolName = name,
        ),
      )
      Console.info("")

      if (showResolvedTargetHeader && resolvedTarget != null) {
        // The header is suppressed when source == Explicit (above), so the
        // attribution label is non-null here by construction. The !! is the
        // contract checkpoint: a future source added without a label would
        // trip here loudly rather than rendering an empty `(null)` phrase.
        ToolboxFormatter.renderResolvedTargetHeader(
          resolved = resolvedTarget,
          sourceLabel = resolvedSource.attributionLabel!!,
          availableTargets = discoverTargetSummaries().map { it.first },
        ).forEach { Console.info(it) }
      }

      val outcome = formatToolsResult(
        content = result.content,
        nameFilter = name,
        targetFilter = resolvedTarget,
        searchQuery = search,
        showDetail = detail,
        roleFilter = role?.lowercase(),
        suppressIndexContextLine = showResolvedTargetHeader,
      )

      // Agents shelling out distinguish "rendered the catalog" from "you asked about
      // something that isn't there." Map empty-result envelopes (unknown --name,
      // zero-match --search, unknown --target) to MISUSE so a wrapping script can
      // branch on `$?` instead of grepping stderr. The mapping lives on the enum
      // itself (see [ToolboxRenderOutcome.exitCode]) so the test suite asserts
      // the same source of truth this call uses.
      outcome.exitCode
    }
  }

  internal fun formatToolsResult(
    content: String,
    nameFilter: String?,
    targetFilter: String?,
    searchQuery: String?,
    showDetail: Boolean,
    roleFilter: String?,
    suppressIndexContextLine: Boolean,
  ): ToolboxRenderOutcome {
    val json = try {
      Json.parseToJsonElement(content).jsonObject
    } catch (_: Exception) {
      // Daemon contract is a JsonObject — anything else (non-JSON, top-level
      // array, primitive) is a protocol violation. Print the raw bytes so the
      // operator has something to grep on, but exit non-zero so a wrapping
      // `&&`-chain stops here instead of treating garbage as success.
      Console.error(content)
      return ToolboxRenderOutcome.NOT_FOUND
    }

    // Top-level error envelope (e.g. `--device=typo` rejected by the daemon before
    // mode dispatch). Treat it the same as the mode-specific not-found envelopes.
    val error = json["error"]?.jsonPrimitive?.content
    if (error != null) {
      Console.error(error)
      return ToolboxRenderOutcome.NOT_FOUND
    }

    // Name mode: single tool detail. Skip the system-prompt section — `--name <tool>` is
    // a tool-specific drill-down, not a target-wide listing, so the target's curated prose
    // would be off-topic noise. The same suppression policy lives in
    // [ToolboxFormatter.systemPromptBlockForResponse], so a test pins the contract without
    // having to capture Console output.
    if (nameFilter != null) {
      return formatNameResult(json)
    }

    // System prompt block — inlines the resolved target's curated LLM-facing prose (the
    // contents of `system_prompt_file:` from its trailmap manifest) plus the `## Tools`
    // divider before the tool catalog. Silently omitted when the target has no system
    // prompt configured. See [ToolboxFormatter.systemPromptBlockForResponse] for the
    // cross-mode policy.
    ToolboxFormatter.systemPromptBlockForResponse(json, isNameMode = false)
      .forEach { Console.info(it) }

    // Search mode: keyword matches
    if (searchQuery != null) {
      return formatSearchResult(json)
    }

    // Role-filtered mode: just the trailhead-tagged (or shortcut-tagged) tools for the current
    // target/platform, with descriptions inlined. Renders before the target/index dispatch so
    // `toolbox trailheads --target=square` doesn't get routed through the broader target view.
    if (roleFilter != null) {
      return formatRoleFilterResult(json, roleFilter)
    }

    // Target mode: tools for a specific target (default target routes to index mode).
    // Also receives `suppressIndexContextLine` so a workspace-config-resolved target
    // ("trailblaze toolbox --device web" with `selectedTargetAppId = square`) doesn't
    // print its own `$displayName ($currentPlatform)` line below the new
    // resolved-target header — same de-duplication contract as index mode.
    if (targetFilter != null &&
      !targetFilter.equals(DefaultTrailblazeHostAppTarget.id, ignoreCase = true)
    ) {
      return formatTargetResult(json, suppressIndexContextLine)
    }

    // Index mode: full overview (now leads with role-grouped sections when present)
    formatIndexResult(json, showDetail, suppressIndexContextLine)
    return ToolboxRenderOutcome.OK
  }

  private fun formatIndexResult(
    json: kotlinx.serialization.json.JsonObject,
    showDetail: Boolean,
    suppressContextLine: Boolean,
  ) {
    val currentTarget = json["currentTarget"]?.jsonPrimitive?.content
    val currentPlatform = json["currentPlatform"]?.jsonPrimitive?.content

    val hasContext = currentTarget != null && currentPlatform != null

    // When the resolved-target header has already been emitted above (implicit --target
    // resolution), skip the compact `target (platform)` context line — the header carries
    // the same information plus the source attribution and switch hint.
    if (!suppressContextLine) {
      if (hasContext) {
        Console.info("$currentTarget ($currentPlatform)")
      } else {
        if (currentPlatform != null) Console.info("Device: $currentPlatform")
        if (currentTarget != null) Console.info("Target: $currentTarget")
      }
      if (currentTarget != null || currentPlatform != null) {
        Console.info("")
      }
    }

    val platformToolsets = json["platformToolsets"] as? JsonArray
    val targetToolsets = json["targetToolsets"] as? JsonArray

    // Role-grouped headlines — every trail starts with a trailhead, so trailheads and shortcuts
    // get called out before the toolset listing so authors see them first. The data comes from
    // the daemon's response (`trailheadTools` / `shortcutTools` lists), looked up by name into
    // the toolsets we just received so each entry can carry its tool description on the same
    // line. When the lists are empty (no trailhead-tagged tools for this target/platform), the
    // sections are silently omitted.
    renderRoleHeadline(
      json = json,
      roleKey = "trailheadTools",
      header = "Trailheads (start your trail here):",
      platformToolsets = platformToolsets,
      targetToolsets = targetToolsets,
    )
    renderRoleHeadline(
      json = json,
      roleKey = "shortcutTools",
      header = "Shortcuts (jump between waypoints):",
      platformToolsets = platformToolsets,
      targetToolsets = targetToolsets,
    )

    // Platform toolsets
    if (platformToolsets != null && platformToolsets.isNotEmpty()) {
      val label = if (currentPlatform != null) {
        "Platform ($currentPlatform):"
      } else {
        "Platform:"
      }
      Console.info(label)
      formatToolsetList(platformToolsets, showDetail)
    }

    // Target toolsets
    if (targetToolsets != null && targetToolsets.isNotEmpty()) {
      val targetCount = targetToolsets.map { it.jsonObject["name"]?.jsonPrimitive?.content?.substringBefore("/") }
        .distinct().size
      val toolCount = targetToolsets.sumOf { (it.jsonObject["tools"] as? JsonArray)?.size ?: 0 }
      Console.info("")
      Console.info("Target tools ($toolCount tools across $targetCount targets for this platform):")
      formatToolsetList(targetToolsets, showDetail)
    }

    // Other targets — suppressed when the resolved-target header has already rendered
    // the same affordance ("Available targets: a, b, c") at the top of the listing,
    // so the user doesn't see the same target list twice in one screen.
    val otherTargets = json["otherTargets"] as? JsonArray
    if (!suppressContextLine && otherTargets != null && otherTargets.isNotEmpty()) {
      if (hasContext) {
        // Focused mode: user has a target+device, just hint that others exist
        val otherNames = otherTargets.map { it.jsonObject["name"]?.jsonPrimitive?.content ?: "?" }
        Console.info("Other targets: ${otherNames.joinToString(", ")} (use --target <name> to explore)")
      } else {
        // Discovery mode: no target set. Parse the JSON array into typed summaries so a
        // malformed daemon response can't crash the renderer, then hand the typed list
        // to the formatter that unit tests also use.
        val summaries = ToolboxFormatter.parseTargetSummariesJson(otherTargets)
        ToolboxFormatter.renderTargetListBlock(summaries).forEach { Console.info(it) }
      }
    }

    // Usage hints (only in compact mode)
    if (!showDetail) {
      Console.info("")
      Console.info("Usage:")
      Console.info("  trailblaze tool <name> [param=value ...] [--step 'what this step does']")
      Console.info("")
      val driverType = json["currentDriverType"]?.jsonPrimitive?.content
      val examples = examplesForDriver(driverType)
      Console.info("Examples:")
      for (ex in examples) {
        Console.info("  trailblaze tool ${ex.tool} -s '${ex.objective}'")
      }
      Console.info("")
      Console.info("The --step (-s) describes intent so Trailblaze can self-heal if UI changes.")
      Console.info("Optional by default; flip on with `trailblaze config require-steps true` to enforce.")
      Console.info("")
      val yaml = examples.first { it.yamlSnippet != null }
      Console.info("Advanced — run multiple tools in one call via tool --yaml:")
      Console.info("  trailblaze tool -s '${yaml.objective}' --yaml '${yaml.yamlSnippet}'")

      Console.info("")
      Console.info("Explore:")
      Console.info("  toolbox --search <keyword>  Search tools by keyword (e.g. 'launch', 'payment')")
      Console.info("  toolbox --name <tool>       Show full details and parameters for a tool")
      Console.info("  toolbox --detail            Expand all descriptions and parameters")
      // The `--target` switch hint is suppressed when the resolved-target header
      // already emitted its own "To switch: --target <name>" line at the top — same
      // de-duplication reasoning as the "Other targets:" block above.
      if (!suppressContextLine && otherTargets != null && otherTargets.isNotEmpty()) {
        Console.info("  toolbox --target <name>     Show target-specific tools")
      }
    }
  }

  /**
   * Filtered view: only the tools with the given role (`trailheads` or `shortcuts`) for the
   * current target/platform. Fired by the positional ROLE arg on the CLI; rendered via
   * [ToolboxFormatter.renderRoleSection] so the line-shape is unit-tested.
   */
  private fun formatRoleFilterResult(
    json: kotlinx.serialization.json.JsonObject,
    role: String,
  ): ToolboxRenderOutcome {
    val currentTarget = json["currentTarget"]?.jsonPrimitive?.content
    val currentPlatform = json["currentPlatform"]?.jsonPrimitive?.content
    if (currentTarget != null && currentPlatform != null) {
      Console.info("$currentTarget ($currentPlatform)")
      Console.info("")
    }

    val roleKey = roleKeyFor(role) ?: run {
      Console.error("Internal error: unknown role '$role'") // validated upstream
      return ToolboxRenderOutcome.NOT_FOUND
    }
    val names = (json[roleKey] as? JsonArray)?.mapNotNull { it.jsonPrimitive.content } ?: emptyList()
    if (names.isEmpty()) {
      // Same regression class as `--search` with zero matches: the user asked
      // "show me the trailheads (or shortcuts)" and the catalogue has none.
      // Agents shelling out branch on `$?`; the empty-message stderr alone
      // wouldn't distinguish "found 0" from "rendered fine."
      val suffix = if (role == "trailheads") "*.trailhead.yaml" else "*.shortcut.yaml"
      ToolboxFormatter.renderRoleEmptyMessage(role, currentTarget, currentPlatform, suffix)
        .forEach { Console.info(it) }
      return ToolboxRenderOutcome.NOT_FOUND
    }

    // Pull descriptions from both index-mode (`platformToolsets`/`targetToolsets`) and
    // target-mode (`toolGroups`/`toolsByPlatform`) shapes — `toolbox <role> --target=<non-default>`
    // returns a target-mode envelope, and we need its descriptors to inline descriptions.
    val descriptionsByName = ToolboxFormatter.collectToolDescriptions(
      json["platformToolsets"] as? JsonArray,
      json["targetToolsets"] as? JsonArray,
      json["toolGroups"] as? JsonArray,
      json["toolsByPlatform"] as? JsonArray,
    )
    ToolboxFormatter.renderRoleSection(headerFor(role), names, descriptionsByName)
      .forEach { Console.info(it) }
    Console.info("")
    Console.info("Use `trailblaze toolbox --name <tool>` for full description, parameters, and usage.")
    return ToolboxRenderOutcome.OK
  }

  /**
   * Top-of-listing headline section for a role. Reads tool names from the JSON response and
   * delegates rendering to [ToolboxFormatter.renderRoleSection]; emits the section followed
   * by a blank-line separator. Silently emits nothing when the role list is empty.
   */
  private fun renderRoleHeadline(
    json: kotlinx.serialization.json.JsonObject,
    roleKey: String,
    header: String,
    platformToolsets: kotlinx.serialization.json.JsonArray?,
    targetToolsets: kotlinx.serialization.json.JsonArray?,
  ) {
    val names = (json[roleKey] as? JsonArray)?.mapNotNull { it.jsonPrimitive.content } ?: return
    if (names.isEmpty()) return
    val descriptionsByName = ToolboxFormatter.collectToolDescriptions(platformToolsets, targetToolsets)
    ToolboxFormatter.renderRoleSection(header, names, descriptionsByName)
      .forEach { Console.info(it) }
    Console.info("")
  }

  /** Maps the CLI's positional role string to the JSON key in the daemon response. */
  private fun roleKeyFor(role: String): String? = when (role) {
    "trailheads" -> "trailheadTools"
    "shortcuts" -> "shortcutTools"
    else -> null
  }

  /** Maps the CLI's positional role string to the section header rendered in output. */
  private fun headerFor(role: String): String = when (role) {
    "trailheads" -> "Trailheads (start your trail here):"
    else -> "Shortcuts (jump between waypoints):"
  }

  private fun formatNameResult(json: kotlinx.serialization.json.JsonObject): ToolboxRenderOutcome =
    when (val rendered = ToolboxFormatter.renderToolNameLines(json)) {
      is ToolboxFormatter.ToolNameRender.Error -> {
        Console.error(rendered.message)
        ToolboxRenderOutcome.NOT_FOUND
      }
      is ToolboxFormatter.ToolNameRender.Lines -> {
        rendered.lines.forEach { Console.info(it) }
        ToolboxRenderOutcome.OK
      }
    }

  private fun formatTargetResult(
    json: kotlinx.serialization.json.JsonObject,
    suppressContextLine: Boolean,
  ): ToolboxRenderOutcome {
    val target = json["target"]?.jsonPrimitive?.content
    if (target == null) {
      Console.error(json["error"]?.jsonPrimitive?.content ?: "Target not found")
      return ToolboxRenderOutcome.NOT_FOUND
    }

    val displayName = json["displayName"]?.jsonPrimitive?.content ?: target
    val currentPlatform = json["currentPlatform"]?.jsonPrimitive?.content
    val platforms = (json["supportedPlatforms"] as? JsonArray)?.joinToString(", ") {
      it.jsonPrimitive.content
    }

    // When the resolved-target header is already rendered above, skip the context
    // line + supported-platforms block to keep the output from showing the same
    // target/platform info twice. Same contract as `formatIndexResult`.
    if (!suppressContextLine) {
      if (currentPlatform != null) {
        Console.info("$displayName ($currentPlatform)")
      } else {
        Console.info("$displayName ($target)")
        if (platforms != null) {
          Console.info("  Platforms: $platforms")
        }
      }
      Console.info("")
    }

    // Grouped mode (device connected): show tool groups
    val toolGroups = json["toolGroups"] as? JsonArray
    if (toolGroups != null && toolGroups.isNotEmpty()) {
      formatToolsetList(toolGroups, showDetail = false)
      return ToolboxRenderOutcome.OK
    }

    // Flat mode (no device): show tools by platform
    val toolsByPlatform = json["toolsByPlatform"] as? JsonArray
    if (toolsByPlatform != null && toolsByPlatform.isNotEmpty()) {
      for (platformTools in toolsByPlatform) {
        val obj = platformTools.jsonObject
        val platform = obj["platform"]?.jsonPrimitive?.content ?: continue
        val tools = obj["tools"] as? JsonArray ?: continue
        Console.info("  $platform tools:")
        for (tool in tools) {
          val toolObj = tool.jsonObject
          val toolName = toolObj["name"]?.jsonPrimitive?.content ?: continue
          val toolDesc = toolObj["description"]?.jsonPrimitive?.content ?: ""
          Console.info("    - $toolName: $toolDesc")
        }
      }
    } else {
      Console.info("  No custom tools for this target.")
    }
    return ToolboxRenderOutcome.OK
  }

  private fun formatSearchResult(json: kotlinx.serialization.json.JsonObject): ToolboxRenderOutcome {
    val query = json["query"]?.jsonPrimitive?.content ?: ""
    // `as? JsonArray` (not `?.jsonArray`) is the file-wide convention for
    // nullable array reads in this file: the daemon currently omits null
    // fields (`encodeDefaults = false`), but a literal `"matches": null` in a
    // future response shape would crash `?.jsonArray` with
    // `JsonNull is not a JsonArray`. The safe cast returns null in both the
    // "absent" and "explicit null" cases.
    val matches = json["matches"] as? JsonArray

    if (matches == null || matches.isEmpty()) {
      Console.info("No tools found matching \"$query\".")
      Console.info("")
      Console.info("Try a broader search, or use 'tools' to browse all available tools.")
      return ToolboxRenderOutcome.NOT_FOUND
    }

    Console.info("Search results for \"$query\":")
    Console.info("")

    // Group by source for readability
    val grouped = mutableMapOf<String, MutableList<kotlinx.serialization.json.JsonObject>>()
    for (match in matches) {
      val obj = match.jsonObject
      val source = obj["source"]?.jsonPrimitive?.content ?: "Unknown"
      grouped.getOrPut(source) { mutableListOf() }.add(obj)
    }

    for ((source, matchList) in grouped) {
      Console.info("  $source:")
      for (match in matchList) {
        val tool = match["tool"]?.jsonObject ?: continue
        val toolName = tool["name"]?.jsonPrimitive?.content ?: continue
        val toolDesc = tool["description"]?.jsonPrimitive?.content ?: ""
        Console.info("    - $toolName: $toolDesc")
        formatParameters(tool, "        ")
      }
    }

    Console.info("")
    Console.info("Use --name <tool> for full details.")
    return ToolboxRenderOutcome.OK
  }

  private fun formatToolsetList(
    toolsets: kotlinx.serialization.json.JsonArray,
    showDetail: Boolean,
  ) {
    for (toolset in toolsets) {
      val obj = toolset.jsonObject
      val tsName = obj["name"]?.jsonPrimitive?.content ?: continue
      val desc = obj["description"]?.jsonPrimitive?.content ?: ""
      Console.info("")
      Console.info("  [$tsName]  $desc")
      val toolDetails = obj["toolDetails"] as? JsonArray
      toolDetails?.forEach { tool ->
        val toolObj = tool.jsonObject
        val toolName = toolObj["name"]?.jsonPrimitive?.content ?: return@forEach
        val toolDesc = toolObj["description"]?.jsonPrimitive?.content ?: ""
        if (showDetail) {
          // Detail mode: full description + parameters. Description rendered verbatim
          // because users explicitly opted into the firehose.
          Console.info("    - $toolName: $toolDesc")
          formatParameters(toolObj, "        ")
        } else {
          // Compact mode: name + a one-line "peek" of the description (see
          // ToolboxFormatter for ellipsis + truncation rules). Required parameters are
          // NOT shown — `--name <tool>` is the right path for parameter exploration.
          Console.info("    ${ToolboxFormatter.compactToolPeekLine(toolName, toolDesc)}")
        }
      }
    }
    if (!showDetail) {
      Console.info("")
      Console.info("  Use --name <tool> for full description, parameters, and usage.")
    }
  }


  private fun formatParameters(toolObj: kotlinx.serialization.json.JsonObject, indent: String) {
    ToolboxFormatter.renderParameterLines(toolObj, indent).forEach { Console.info(it) }
  }

  private data class ToolExample(
    val tool: String,
    val objective: String,
    val yamlSnippet: String? = null,
  )

  private fun examplesForDriver(driverType: String?): List<ToolExample> {
    val resolved = driverType?.let { key ->
      TrailblazeDriverType.entries.firstOrNull { it.yamlKey == key }
    }
    return when (resolved) {
      TrailblazeDriverType.COMPOSE -> listOf(
        ToolExample(
          tool = "compose_click elementId=e5 element='Submit'",
          objective = "Click the Submit button",
          yamlSnippet = "- compose_click:\\n    elementId: e5",
        ),
        ToolExample(
          tool = "compose_type elementId=e3 text=user@test.com",
          objective = "Enter email address",
        ),
        ToolExample(
          tool = "compose_scroll elementId=e2",
          objective = "Scroll down the list",
        ),
      )
      TrailblazeDriverType.REVYL_ANDROID, TrailblazeDriverType.REVYL_IOS -> listOf(
        ToolExample(
          tool = "revyl_tap target='Sign In button'",
          objective = "Tap the Sign In button",
          yamlSnippet = "- revyl_tap:\\n    target: Sign In button",
        ),
        ToolExample(
          tool = "revyl_type text=user@test.com target='Email field'",
          objective = "Enter email address",
        ),
        ToolExample(
          tool = "revyl_navigate url=https://example.com",
          objective = "Open example.com",
        ),
      )
      TrailblazeDriverType.PLAYWRIGHT_NATIVE, TrailblazeDriverType.PLAYWRIGHT_ELECTRON -> listOf(
        ToolExample(
          tool = "web_snapshot",
          objective = "See what is on the page",
        ),
        ToolExample(
          tool = "web_navigate url=https://example.com",
          objective = "Open example.com",
          yamlSnippet = "- web_navigate:\\n    url: https://example.com",
        ),
        ToolExample(
          tool = "web_click ref=e5 element='Submit'",
          objective = "Click the Submit button",
        ),
        ToolExample(
          tool = "web_type ref=e7 text=user@test.com",
          objective = "Enter email address",
        ),
      )
      else -> listOf(
        ToolExample(
          tool = "tap ref=p386",
          objective = "Tap the Sign In button",
        ),
        ToolExample(
          tool = "inputText text=user@test.com",
          objective = "Enter email address",
        ),
        ToolExample(
          tool = "launchApp appId=com.example.app",
          objective = "Open the app",
          yamlSnippet = "- launchApp:\\n    appId: com.example.app",
        ),
      )
    }
  }
}
