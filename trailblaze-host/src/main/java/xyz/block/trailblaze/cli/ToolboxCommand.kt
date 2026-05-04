package xyz.block.trailblaze.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
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

  @Option(
    names = ["--name", "-n"],
    description = ["Show details for a single tool by name"],
  )
  var name: String? = null

  @Option(
    names = ["--target", "-t"],
    description = ["Show tools for a specific target app"],
  )
  var target: String? = null

  @Option(
    names = ["--search", "-s"],
    description = ["Search tools by keyword (matches names and descriptions)"],
  )
  var search: String? = null

  @Option(
    names = ["-d", "--device"],
    description = ["Filter by platform: android, ios, web"],
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
    // --name lookups work without --device/--target (asking about a specific tool)
    // Everything else requires both to avoid showing inapplicable tools
    if (name == null && (device == null || target == null)) {
      Console.error("Both --device and --target are required to show applicable tools.")
      Console.error("")

      // Show available devices
      Console.error("Devices:")
      for (platform in TrailblazeDevicePlatform.entries) {
        Console.error("  ${platform.name.lowercase()}")
      }

      // Show available targets
      val targets = discoverTargetSummaries()
      Console.error("")
      Console.error("Targets:")
      for ((id, displayName) in targets) {
        Console.error("  $id — $displayName")
      }

      Console.error("")
      Console.error("Example:")
      Console.error("  trailblaze toolbox --device android --target default")
      Console.error("")
      Console.error("Use --name <tool> to look up a specific tool without --device/--target.")
      return CommandLine.ExitCode.USAGE
    }

    return cliWithDaemon(verbose) { client ->
      val arguments = mutableMapOf<String, Any?>()
      if (name != null) arguments["name"] = name
      if (target != null) arguments["target"] = target
      if (search != null) arguments["search"] = search
      if (device != null) arguments["platform"] = device
      if (detail) arguments["detail"] = true

      val result = client.callTool("toolbox", arguments)

      if (result.isError) {
        Console.error("Error: ${extractErrorMessage(result.content)}")
        return@cliWithDaemon CommandLine.ExitCode.SOFTWARE
      }

      formatToolsResult(result.content, name, target, search, detail)

      CommandLine.ExitCode.OK
    }
  }

  private fun formatToolsResult(
    content: String,
    nameFilter: String?,
    targetFilter: String?,
    searchQuery: String?,
    showDetail: Boolean,
  ) {
    val json = try {
      Json.parseToJsonElement(content).jsonObject
    } catch (_: Exception) {
      Console.info(content)
      return
    }

    // Check for error
    val error = json["error"]?.jsonPrimitive?.content
    if (error != null) {
      Console.error(error)
      return
    }

    // Name mode: single tool detail
    if (nameFilter != null) {
      formatNameResult(json)
      return
    }

    // Search mode: keyword matches
    if (searchQuery != null) {
      formatSearchResult(json)
      return
    }

    // Target mode: tools for a specific target (default target routes to index mode)
    if (targetFilter != null &&
      !targetFilter.equals(DefaultTrailblazeHostAppTarget.id, ignoreCase = true)
    ) {
      formatTargetResult(json)
      return
    }

    // Index mode: full overview
    formatIndexResult(json, showDetail)
  }

  private fun formatIndexResult(json: kotlinx.serialization.json.JsonObject, showDetail: Boolean) {
    val currentTarget = json["currentTarget"]?.jsonPrimitive?.content
    val currentPlatform = json["currentPlatform"]?.jsonPrimitive?.content

    val hasContext = currentTarget != null && currentPlatform != null

    if (hasContext) {
      Console.info("$currentTarget ($currentPlatform)")
    } else {
      if (currentPlatform != null) Console.info("Device: $currentPlatform")
      if (currentTarget != null) Console.info("Target: $currentTarget")
    }
    if (currentTarget != null || currentPlatform != null) {
      Console.info("")
    }

    val platformToolsets = json["platformToolsets"]?.jsonArray
    val targetToolsets = json["targetToolsets"]?.jsonArray

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
      val toolCount = targetToolsets.sumOf { it.jsonObject["tools"]?.jsonArray?.size ?: 0 }
      Console.info("")
      Console.info("Target tools ($toolCount tools across $targetCount targets for this platform):")
      formatToolsetList(targetToolsets, showDetail)
    }

    // Other targets
    val otherTargets = json["otherTargets"]?.jsonArray
    if (otherTargets != null && otherTargets.isNotEmpty()) {
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
      Console.info("  trailblaze tool <name> [param=value ...] --objective 'what this step does'")
      Console.info("")
      val driverType = json["currentDriverType"]?.jsonPrimitive?.content
      val examples = examplesForDriver(driverType)
      Console.info("Examples:")
      for (ex in examples) {
        Console.info("  trailblaze tool ${ex.tool} -o '${ex.objective}'")
      }
      Console.info("")
      Console.info("The --objective (-o) describes intent so Trailblaze can self-heal if UI changes.")
      Console.info("")
      val yaml = examples.first { it.yamlSnippet != null }
      Console.info("Advanced — run multiple tools in one call via tool --yaml:")
      Console.info("  trailblaze tool -o '${yaml.objective}' --yaml '${yaml.yamlSnippet}'")

      Console.info("")
      Console.info("Explore:")
      Console.info("  toolbox --search <keyword>  Search tools by keyword (e.g. 'launch', 'payment')")
      Console.info("  toolbox --name <tool>       Show full details and parameters for a tool")
      Console.info("  toolbox --detail            Expand all descriptions and parameters")
      if (otherTargets != null && otherTargets.isNotEmpty()) {
        Console.info("  toolbox --target <name>     Show target-specific tools")
      }
    }
  }

  private fun formatNameResult(json: kotlinx.serialization.json.JsonObject) {
    val tool = json["tool"]?.jsonObject
    if (tool == null) {
      Console.error(json["error"]?.jsonPrimitive?.content ?: "Tool not found")
      return
    }

    val toolName = tool["name"]?.jsonPrimitive?.content ?: "?"
    val toolDesc = tool["description"]?.jsonPrimitive?.content ?: ""
    Console.info("$toolName")
    Console.info("  $toolDesc")
    Console.info("")
    formatParameters(tool, "  ")

    val categories = json["foundInCategories"]?.jsonArray
    if (categories != null && categories.isNotEmpty()) {
      Console.info("  Categories: ${categories.joinToString(", ") { it.jsonPrimitive.content }}")
    }
    val targets = json["foundInTargets"]?.jsonArray
    if (targets != null && targets.isNotEmpty()) {
      Console.info("  Targets: ${targets.joinToString(", ") { it.jsonPrimitive.content }}")
    }
  }

  private fun formatTargetResult(json: kotlinx.serialization.json.JsonObject) {
    val target = json["target"]?.jsonPrimitive?.content
    if (target == null) {
      Console.error(json["error"]?.jsonPrimitive?.content ?: "Target not found")
      return
    }

    val displayName = json["displayName"]?.jsonPrimitive?.content ?: target
    val currentPlatform = json["currentPlatform"]?.jsonPrimitive?.content
    val platforms = json["supportedPlatforms"]?.jsonArray?.joinToString(", ") {
      it.jsonPrimitive.content
    }

    if (currentPlatform != null) {
      Console.info("$displayName ($currentPlatform)")
    } else {
      Console.info("$displayName ($target)")
      if (platforms != null) {
        Console.info("  Platforms: $platforms")
      }
    }
    Console.info("")

    // Grouped mode (device connected): show tool groups
    val toolGroups = json["toolGroups"]?.jsonArray
    if (toolGroups != null && toolGroups.isNotEmpty()) {
      formatToolsetList(toolGroups, showDetail = false)
      return
    }

    // Flat mode (no device): show tools by platform
    val toolsByPlatform = json["toolsByPlatform"]?.jsonArray
    if (toolsByPlatform != null && toolsByPlatform.isNotEmpty()) {
      for (platformTools in toolsByPlatform) {
        val obj = platformTools.jsonObject
        val platform = obj["platform"]?.jsonPrimitive?.content ?: continue
        val tools = obj["tools"]?.jsonArray ?: continue
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
  }

  private fun formatSearchResult(json: kotlinx.serialization.json.JsonObject) {
    val query = json["query"]?.jsonPrimitive?.content ?: ""
    val matches = json["matches"]?.jsonArray

    if (matches == null || matches.isEmpty()) {
      Console.info("No tools found matching \"$query\".")
      Console.info("")
      Console.info("Try a broader search, or use 'tools' to browse all available tools.")
      return
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
      val toolDetails = obj["toolDetails"]?.jsonArray
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
    val required = toolObj["requiredParameters"]?.jsonArray
    val optional = toolObj["optionalParameters"]?.jsonArray
    if (required != null && required.isNotEmpty()) {
      for (param in required) {
        val pObj = param.jsonObject
        val pName = pObj["name"]?.jsonPrimitive?.content ?: continue
        val pType = pObj["type"]?.jsonPrimitive?.content ?: ""
        val pDesc = pObj["description"]?.jsonPrimitive?.content ?: ""
        Console.info("${indent}$pName ($pType, required): $pDesc")
      }
    }
    if (optional != null && optional.isNotEmpty()) {
      for (param in optional) {
        val pObj = param.jsonObject
        val pName = pObj["name"]?.jsonPrimitive?.content ?: continue
        val pType = pObj["type"]?.jsonPrimitive?.content ?: ""
        val pDesc = pObj["description"]?.jsonPrimitive?.content ?: ""
        Console.info("${indent}$pName ($pType, optional): $pDesc")
      }
    }
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
