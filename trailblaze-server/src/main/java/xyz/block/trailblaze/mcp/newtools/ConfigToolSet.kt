package xyz.block.trailblaze.mcp.newtools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.mcp.McpToolProfile
import xyz.block.trailblaze.mcp.ScreenshotFormat
import xyz.block.trailblaze.mcp.TrailblazeMcpBridge
import xyz.block.trailblaze.mcp.TrailblazeMcpSessionContext
import xyz.block.trailblaze.mcp.ViewHierarchyVerbosity
import xyz.block.trailblaze.mcp.toolsets.ToolLoadingStrategy
import xyz.block.trailblaze.util.Console

/**
 * MCP tool for querying and updating Trailblaze configuration.
 *
 * Exposes both global settings (persisted to disk via settings repo) and
 * session-level settings (MCP session context). The user doesn't need to
 * know which layer a setting lives in.
 */
@Suppress("unused")
class ConfigToolSet(
  private val sessionContext: TrailblazeMcpSessionContext?,
  private val mcpBridge: TrailblazeMcpBridge,
) : ToolSet {

  enum class ConfigAction {
    /** Show current config values (all or one key) */
    GET,
    /** Update a config value */
    SET,
    /** Show all configurable keys with descriptions and options */
    LIST,
  }

  @LLMDescription(
    """
    Query or update Trailblaze configuration.

    config(action=GET) → show all current settings
    config(action=GET, key="androidDriver") → show one setting with options
    config(action=SET, key="androidDriver", value="ANDROID_ONDEVICE_ACCESSIBILITY")
    config(action=LIST) → show all configurable keys with descriptions

    Settings include device drivers, LLM model, agent implementation, and more.
    """
  )
  @Tool
  suspend fun config(
    @LLMDescription("Action: GET, SET, or LIST")
    action: ConfigAction,
    @LLMDescription("Config key (for GET one setting or SET)")
    key: String? = null,
    @LLMDescription("New value (for SET)")
    value: String? = null,
  ): String {
    return when (action) {
      ConfigAction.GET -> handleGet(key)
      ConfigAction.SET -> handleSet(key, value)
      ConfigAction.LIST -> handleList()
    }
  }

  private fun handleGet(key: String?): String {
    val allValues = getAllConfigValues()

    if (key == null) {
      return ConfigGetResult(
        values = allValues,
        message = "Current configuration (${allValues.size} settings)",
      ).toJson()
    }

    val configKey = findConfigKey(key)
      ?: return ConfigGetResult(
        error = "Unknown config key '$key'. Use config(action=LIST) to see available keys.",
      ).toJson()

    val currentValue = allValues[configKey.key]
    return ConfigGetResult(
      values = mapOf(configKey.key to (currentValue ?: "not set")),
      options = configKey.validValues,
      description = configKey.description,
      message = "${configKey.key} = ${currentValue ?: "not set"}",
    ).toJson()
  }

  private fun handleSet(key: String?, value: String?): String {
    if (key == null) {
      return ConfigSetResult(
        success = false,
        error =
          "Missing key. Example: config(action=SET, key=\"androidDriver\", value=\"ANDROID_ONDEVICE_ACCESSIBILITY\")",
      ).toJson()
    }
    if (value == null) {
      return ConfigSetResult(
        success = false,
        error = "Missing value. Use config(action=GET, key=\"$key\") to see available options.",
      ).toJson()
    }

    val configKey = findConfigKey(key)
      ?: return ConfigSetResult(
        success = false,
        error = "Unknown config key '$key'. Use config(action=LIST) to see available keys.",
      ).toJson()

    // Validate value if there are restricted options
    if (configKey.validValues != null) {
      val matched = configKey.validValues.find { it.equals(value, ignoreCase = true) }
      if (matched == null) {
        return ConfigSetResult(
          success = false,
          error =
            "Invalid value '$value' for ${configKey.key}. " +
              "Valid options: ${configKey.validValues.joinToString(", ")}",
        ).toJson()
      }
    }

    val error = applyConfigValue(configKey.key, value)
    if (error != null) {
      return ConfigSetResult(success = false, error = error).toJson()
    }

    Console.log("")
    Console.log("┌──────────────────────────────────────────────────────────────────────────────")
    Console.log("│ [config] Updated: ${configKey.key} = $value")
    Console.log("└──────────────────────────────────────────────────────────────────────────────")

    return ConfigSetResult(
      success = true,
      key = configKey.key,
      value = value,
      message = "Updated ${configKey.key} to $value",
    ).toJson()
  }

  private fun handleList(): String {
    val allValues = getAllConfigValues()
    val keys = CONFIG_KEYS.map { configKey ->
      ConfigKeyInfo(
        key = configKey.key,
        description = configKey.description,
        currentValue = allValues[configKey.key] ?: "not set",
        options = configKey.validValues,
      )
    }
    return ConfigListResult(
      keys = keys,
      message = "${keys.size} configurable settings",
    ).toJson()
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Config key definitions
  // ─────────────────────────────────────────────────────────────────────────────

  private data class ConfigKeyDef(
    val key: String,
    val description: String,
    val validValues: List<String>? = null,
  )

  private fun findConfigKey(key: String): ConfigKeyDef? =
    CONFIG_KEYS.find { it.key.equals(key, ignoreCase = true) }

  private fun getAllConfigValues(): Map<String, String> {
    val values = mutableMapOf<String, String>()

    // Global settings (from bridge → settings repo)
    mcpBridge.getConfiguredDriverType(TrailblazeDevicePlatform.ANDROID)?.let {
      values["androidDriver"] = it.name
    }
    mcpBridge.getConfiguredDriverType(TrailblazeDevicePlatform.IOS)?.let {
      values["iosDriver"] = it.name
    }
    mcpBridge.getConfiguredDriverType(TrailblazeDevicePlatform.WEB)?.let {
      values["webDriver"] = it.name
    }

    // LLM settings from bridge
    mcpBridge.getLlmConfig()?.let { (provider, model) ->
      values["llmProvider"] = provider
      values["llmModel"] = model
    }

    // Global agent implementation from bridge
    mcpBridge.getAgentImplementation()?.let { values["agentImplementation"] = it.name }

    // Session-level settings
    sessionContext?.let { ctx ->
      values["screenshotFormat"] = ctx.screenshotFormat.name
      values["viewHierarchyVerbosity"] = ctx.viewHierarchyVerbosity.name
      values["toolLoadingStrategy"] = ctx.toolLoadingStrategy.name
      values["toolProfile"] = ctx.toolProfile.name
    }

    return values
  }

  private fun applyConfigValue(key: String, value: String): String? {
    return when (key) {
      "androidDriver" -> setDriverType(TrailblazeDevicePlatform.ANDROID, value)
      "iosDriver" -> setDriverType(TrailblazeDevicePlatform.IOS, value)
      "webDriver" -> setDriverType(TrailblazeDevicePlatform.WEB, value)
      "llmProvider" -> mcpBridge.setLlmConfig(provider = value, model = null)
      "llmModel" -> mcpBridge.setLlmConfig(provider = null, model = value)
      "agentImplementation" -> {
        val impl = AgentImplementation.entries.find { it.name.equals(value, ignoreCase = true) }
          ?: return "Invalid implementation: $value"
        sessionContext?.agentImplementation = impl
        mcpBridge.setAgentImplementation(impl)
      }
      "screenshotFormat" -> {
        val format = ScreenshotFormat.entries.find { it.name.equals(value, ignoreCase = true) }
          ?: return "Invalid screenshot format: $value"
        sessionContext?.screenshotFormat = format
        null
      }
      "viewHierarchyVerbosity" -> {
        val verbosity =
          ViewHierarchyVerbosity.entries.find { it.name.equals(value, ignoreCase = true) }
            ?: return "Invalid verbosity: $value"
        sessionContext?.viewHierarchyVerbosity = verbosity
        null
      }
      "toolLoadingStrategy" -> {
        val strategy =
          ToolLoadingStrategy.entries.find { it.name.equals(value, ignoreCase = true) }
            ?: return "Invalid strategy: $value"
        sessionContext?.toolLoadingStrategy = strategy
        null
      }
      "toolProfile" -> {
        val profile = McpToolProfile.entries.find { it.name.equals(value, ignoreCase = true) }
          ?: return "Invalid tool profile: $value"
        sessionContext?.toolProfile = profile
        null
      }
      else -> "Unknown config key: $key"
    }
  }

  private fun setDriverType(platform: TrailblazeDevicePlatform, value: String): String? {
    val driverType = TrailblazeDriverType.entries.find {
      it.name.equals(value, ignoreCase = true) && it.platform == platform
    } ?: return "Invalid ${platform.displayName} driver type: $value"
    return mcpBridge.setConfiguredDriverType(platform, driverType)
  }

  companion object {
    private val CONFIG_KEYS = listOf(
      ConfigKeyDef(
        key = "androidDriver",
        description = "Android device driver type",
        validValues = TrailblazeDriverType.entries
          .filter { it.platform == TrailblazeDevicePlatform.ANDROID }
          .map { it.name },
      ),
      ConfigKeyDef(
        key = "iosDriver",
        description = "iOS device driver type",
        validValues = TrailblazeDriverType.entries
          .filter { it.platform == TrailblazeDevicePlatform.IOS }
          .map { it.name },
      ),
      ConfigKeyDef(
        key = "webDriver",
        description = "Web device driver type",
        validValues = TrailblazeDriverType.entries
          .filter { it.platform == TrailblazeDevicePlatform.WEB }
          .map { it.name },
      ),
      ConfigKeyDef(
        key = "llmProvider",
        description = "LLM provider (e.g., openai, anthropic, google, ollama)",
      ),
      ConfigKeyDef(
        key = "llmModel",
        description = "LLM model ID (e.g., gpt-4.1, claude-sonnet-4-20250514)",
      ),
      ConfigKeyDef(
        key = "agentImplementation",
        description = "Agent architecture for automation",
        validValues = AgentImplementation.entries.map { it.name },
      ),
      ConfigKeyDef(
        key = "screenshotFormat",
        description = "How screenshots are included in tool responses",
        validValues = ScreenshotFormat.entries.map { it.name },
      ),
      ConfigKeyDef(
        key = "viewHierarchyVerbosity",
        description = "Detail level for view hierarchy data",
        validValues = ViewHierarchyVerbosity.entries.map { it.name },
      ),
      ConfigKeyDef(
        key = "toolLoadingStrategy",
        description = "How tools are loaded (ALL_TOOLS or PROGRESSIVE)",
        validValues = ToolLoadingStrategy.entries.map { it.name },
      ),
      ConfigKeyDef(
        key = "toolProfile",
        description = "Which tools are exposed (FULL or MINIMAL)",
        validValues = McpToolProfile.entries.map { it.name },
      ),
    )
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Result types
// ─────────────────────────────────────────────────────────────────────────────

@Serializable
data class ConfigGetResult(
  val values: Map<String, String>? = null,
  val options: List<String>? = null,
  val description: String? = null,
  val message: String? = null,
  val error: String? = null,
) {
  fun toJson(): String = TrailblazeJsonInstance.encodeToString(serializer(), this)
}

@Serializable
data class ConfigSetResult(
  val success: Boolean,
  val key: String? = null,
  val value: String? = null,
  val message: String? = null,
  val error: String? = null,
) {
  fun toJson(): String = TrailblazeJsonInstance.encodeToString(serializer(), this)
}

@Serializable
data class ConfigKeyInfo(
  val key: String,
  val description: String,
  val currentValue: String,
  val options: List<String>? = null,
)

@Serializable
data class ConfigListResult(
  val keys: List<ConfigKeyInfo>,
  val message: String? = null,
) {
  fun toJson(): String = TrailblazeJsonInstance.encodeToString(serializer(), this)
}
