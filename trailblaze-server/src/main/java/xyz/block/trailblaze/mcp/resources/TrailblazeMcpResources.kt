package xyz.block.trailblaze.mcp.resources

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.mcp.TrailblazeMcpBridge
import xyz.block.trailblaze.mcp.TrailblazeMcpSessionContext
import xyz.block.trailblaze.mcp.newtools.ConfigToolSet
import xyz.block.trailblaze.mcp.toolsets.generateCategorySummaryForLLM
import xyz.block.trailblaze.recordings.TrailRecordings
import java.io.File

// ── Resource data models ──────────────────────────────────────────────────────

@Serializable
data class ConfigSettingResource(
  val key: String,
  val description: String,
  val currentValue: String,
  val options: List<String>? = null,
)

@Serializable
data class ConfigResource(
  val settings: List<ConfigSettingResource>,
  val currentValues: Map<String, String>,
)

@Serializable
data class DeviceSummaryResource(
  val instanceId: String,
  val platform: String,
  val description: String,
)

@Serializable
data class ConnectedDeviceResource(
  val connected: Boolean,
  val message: String? = null,
  val instanceId: String? = null,
  val platform: String? = null,
  val driver: String? = null,
  val installedApps: List<String>? = null,
  val appTargets: List<String>? = null,
  val currentAppTarget: String? = null,
)

object TrailblazeMcpResourceUris {
  const val ABOUT = "trailblaze://about"
  const val CONFIG = "trailblaze://config"
  const val DEVICES = "trailblaze://devices"
  const val DEVICES_CONNECTED = "trailblaze://devices/connected"
  const val TRAILS = "trailblaze://trails"
  const val TOOLS_CATEGORIES = "trailblaze://tools/categories"
}

fun registerResources(
  mcpServer: Server,
  sessionContext: TrailblazeMcpSessionContext?,
  mcpBridge: TrailblazeMcpBridge,
  trailsDirProvider: () -> File,
) {
  // Static: What Trailblaze is and how to use it
  mcpServer.addResource(
    uri = TrailblazeMcpResourceUris.ABOUT,
    name = "About Trailblaze",
    description = "What Trailblaze is, its capabilities, workflow, and getting started guide",
    mimeType = "text/markdown",
  ) {
    ReadResourceResult(
      contents = listOf(
        TextResourceContents(
          text = ABOUT_CONTENT,
          uri = TrailblazeMcpResourceUris.ABOUT,
          mimeType = "text/markdown",
        ),
      ),
    )
  }

  // Dynamic: Current config values and available keys
  mcpServer.addResource(
    uri = TrailblazeMcpResourceUris.CONFIG,
    name = "Configuration",
    description = "Current config values and all available keys/options",
    mimeType = "application/json",
  ) {
    val allValues = ConfigToolSet.getAllConfigValues(sessionContext, mcpBridge)
    val resource = ConfigResource(
      settings = ConfigToolSet.CONFIG_KEYS.map { configKey ->
        ConfigSettingResource(
          key = configKey.key,
          description = configKey.description,
          currentValue = allValues[configKey.key] ?: "not set",
          options = configKey.validValues,
        )
      },
      currentValues = allValues,
    )
    ReadResourceResult(
      contents = listOf(
        TextResourceContents(
          text = TrailblazeJsonInstance.encodeToString(resource),
          uri = TrailblazeMcpResourceUris.CONFIG,
          mimeType = "application/json",
        ),
      ),
    )
  }

  // Dynamic: Available devices
  mcpServer.addResource(
    uri = TrailblazeMcpResourceUris.DEVICES,
    name = "Available Devices",
    description = "Available devices that can be connected to",
    mimeType = "application/json",
  ) {
    val devices = mcpBridge.getAvailableDevices()
    val configuredAndroid = mcpBridge.getConfiguredDriverType(TrailblazeDevicePlatform.ANDROID)
    val configuredIos = mcpBridge.getConfiguredDriverType(TrailblazeDevicePlatform.IOS)

    val deduped = devices
      .groupBy { it.instanceId to it.platform }
      .map { (_, variants) ->
        val platform = variants.first().platform
        val configuredType = when (platform) {
          TrailblazeDevicePlatform.ANDROID -> configuredAndroid
          TrailblazeDevicePlatform.IOS -> configuredIos
          else -> null
        }
        variants.find { it.trailblazeDriverType == configuredType } ?: variants.first()
      }

    val resources = deduped.map { device ->
      DeviceSummaryResource(
        instanceId = device.instanceId,
        platform = device.platform.displayName,
        description = device.description,
      )
    }
    ReadResourceResult(
      contents = listOf(
        TextResourceContents(
          text = TrailblazeJsonInstance.encodeToString(resources),
          uri = TrailblazeMcpResourceUris.DEVICES,
          mimeType = "application/json",
        ),
      ),
    )
  }

  // Dynamic: Connected device info for this session
  mcpServer.addResource(
    uri = TrailblazeMcpResourceUris.DEVICES_CONNECTED,
    name = "Connected Device",
    description = "Currently connected device info, installed apps, and app targets",
    mimeType = "application/json",
  ) {
    val currentDeviceId = mcpBridge.getCurrentlySelectedDeviceId()
    val resource = if (currentDeviceId == null) {
      ConnectedDeviceResource(
        connected = false,
        message = "No device connected. Use device(action=ANDROID) or device(action=IOS) to connect.",
      )
    } else {
      ConnectedDeviceResource(
        connected = true,
        instanceId = currentDeviceId.instanceId,
        platform = currentDeviceId.trailblazeDevicePlatform.displayName,
        driver = mcpBridge.getDriverType()?.toString(),
        installedApps = mcpBridge.getInstalledAppIds().sorted(),
        appTargets = mcpBridge.getAvailableAppTargets().map { it.id },
        currentAppTarget = mcpBridge.getCurrentAppTargetId(),
      )
    }
    ReadResourceResult(
      contents = listOf(
        TextResourceContents(
          text = TrailblazeJsonInstance.encodeToString(resource),
          uri = TrailblazeMcpResourceUris.DEVICES_CONNECTED,
          mimeType = "application/json",
        ),
      ),
    )
  }

  // Dynamic: Available trail files
  mcpServer.addResource(
    uri = TrailblazeMcpResourceUris.TRAILS,
    name = "Trail Files",
    description = "Available .trail.yaml test files",
    mimeType = "application/json",
  ) {
    val trailsDir = trailsDirProvider()
    val trailFiles = if (trailsDir.exists() && trailsDir.isDirectory) {
      trailsDir.walkTopDown()
        .filter { it.isFile && TrailRecordings.isTrailFile(it.name) }
        .map { it.relativeTo(trailsDir).path }
        .sorted()
        .toList()
    } else {
      emptyList()
    }
    ReadResourceResult(
      contents = listOf(
        TextResourceContents(
          text = TrailblazeJsonInstance.encodeToString(trailFiles),
          uri = TrailblazeMcpResourceUris.TRAILS,
          mimeType = "application/json",
        ),
      ),
    )
  }

  // Static: Tool category descriptions
  mcpServer.addResource(
    uri = TrailblazeMcpResourceUris.TOOLS_CATEGORIES,
    name = "Tool Categories",
    description = "Tool category descriptions and use cases for fine-grained control",
    mimeType = "text/markdown",
  ) {
    ReadResourceResult(
      contents = listOf(
        TextResourceContents(
          text = generateCategorySummaryForLLM(),
          uri = TrailblazeMcpResourceUris.TOOLS_CATEGORIES,
          mimeType = "text/markdown",
        ),
      ),
    )
  }
}

private val ABOUT_CONTENT = """
Trailblaze is an AI-powered mobile UI testing framework. It connects to Android
and iOS devices and automates UI interactions using natural language.

## Core Workflow
1. Connect to a device: `device(action=ANDROID)` or `device(action=IOS)`
2. Automate UI with natural language:
   - `blaze(goal="tap the login button")` — take actions on the device
   - `verify(assertion="the welcome screen is shown")` — check something is true
   - `ask(question="what is the account balance?")` — extract information
3. Save as reusable test: `trail(action=SAVE, name="login_test")`
4. Replay saved test: `trail(action=RUN, name="login_test")`

## When to Use Trailblaze
- Automating mobile app UI testing
- Recording and replaying test scenarios
- Verifying UI state and extracting on-screen information
- Interacting with Android emulators/devices and iOS simulators

## Available Resources
- trailblaze://config — Current settings and available options
- trailblaze://devices — Available devices to connect to
- trailblaze://devices/connected — Connected device info and installed apps
- trailblaze://trails — Saved test trail files
- trailblaze://tools/categories — Tool categories for fine-grained control

## Tips
- Sessions are recorded automatically. Save anytime with trail(action=SAVE).
- Use `blaze` for multi-step goals — it handles screen analysis internally.
- Use `verify` for assertions and `ask` for data extraction.
- Check trailblaze://devices before connecting to see what's available.
""".trimIndent()
