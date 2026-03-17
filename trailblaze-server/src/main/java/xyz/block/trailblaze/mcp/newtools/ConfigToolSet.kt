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

  internal data class ConfigKeyDef(
    val key: String,
    val description: String,
    val validValues: List<String>? = null,
  )

  private fun findConfigKey(key: String): ConfigKeyDef? =
    CONFIG_KEYS.find { it.key.equals(key, ignoreCase = true) }

  private fun getAllConfigValues(): Map<String, String> =
    Companion.getAllConfigValues(sessionContext, mcpBridge)

  private fun applyConfigValue(key: String, value: String): String? {
    return when (key) {
      KEY_TARGET_APP -> {
        mcpBridge.selectAppTarget(value)
          ?: return "Unknown app target '$value'. Available: ${mcpBridge.getAvailableAppTargets().joinToString { it.id }}"
        null // success
      }
      KEY_ANDROID_DRIVER -> setDriverType(TrailblazeDevicePlatform.ANDROID, value)
      KEY_IOS_DRIVER -> setDriverType(TrailblazeDevicePlatform.IOS, value)
      KEY_WEB_DRIVER -> setDriverType(TrailblazeDevicePlatform.WEB, value)
      KEY_LLM_PROVIDER -> mcpBridge.setLlmConfig(provider = value, model = null)
      KEY_LLM_MODEL -> mcpBridge.setLlmConfig(provider = null, model = value)
      KEY_AGENT_IMPLEMENTATION -> {
        val impl = AgentImplementation.entries.find { it.name.equals(value, ignoreCase = true) }
          ?: return "Invalid implementation: $value"
        sessionContext?.agentImplementation = impl
        mcpBridge.setAgentImplementation(impl)
      }
      KEY_SCREENSHOT_FORMAT -> {
        val format = ScreenshotFormat.entries.find { it.name.equals(value, ignoreCase = true) }
          ?: return "Invalid screenshot format: $value"
        sessionContext?.screenshotFormat = format
        null
      }
      KEY_VIEW_HIERARCHY_VERBOSITY -> {
        val verbosity =
          ViewHierarchyVerbosity.entries.find { it.name.equals(value, ignoreCase = true) }
            ?: return "Invalid verbosity: $value"
        sessionContext?.viewHierarchyVerbosity = verbosity
        null
      }
      KEY_TOOL_LOADING_STRATEGY -> {
        val strategy =
          ToolLoadingStrategy.entries.find { it.name.equals(value, ignoreCase = true) }
            ?: return "Invalid strategy: $value"
        sessionContext?.toolLoadingStrategy = strategy
        null
      }
      KEY_TOOL_PROFILE -> {
        val profile = McpToolProfile.entries.find { it.name.equals(value, ignoreCase = true) }
          ?: return "Invalid tool profile: $value"
        sessionContext?.toolProfile = profile
        null
      }
      else -> "Unknown config key: $key"
    }
  }

  private fun setDriverType(platform: TrailblazeDevicePlatform, value: String): String? {
    val driverType = TrailblazeDriverType.fromString(value)
      ?: return "Invalid ${platform.displayName} driver type: $value"
    return mcpBridge.setConfiguredDriverType(platform, driverType)
  }

  companion object {
    // Config key constants
    const val KEY_TARGET_APP = "targetApp"
    const val KEY_ANDROID_DRIVER = "androidDriver"
    const val KEY_IOS_DRIVER = "iosDriver"
    const val KEY_WEB_DRIVER = "webDriver"
    const val KEY_LLM_PROVIDER = "llmProvider"
    const val KEY_LLM_MODEL = "llmModel"
    const val KEY_AGENT_IMPLEMENTATION = "agentImplementation"
    const val KEY_SCREENSHOT_FORMAT = "screenshotFormat"
    const val KEY_VIEW_HIERARCHY_VERBOSITY = "viewHierarchyVerbosity"
    const val KEY_TOOL_LOADING_STRATEGY = "toolLoadingStrategy"
    const val KEY_TOOL_PROFILE = "toolProfile"

    internal fun getAllConfigValues(
      sessionContext: TrailblazeMcpSessionContext?,
      mcpBridge: TrailblazeMcpBridge,
    ): Map<String, String> {
      val values = mutableMapOf<String, String>()

      // Target app selection
      mcpBridge.getCurrentAppTargetId()?.let { values[KEY_TARGET_APP] = it }

      // Global settings (from bridge → settings repo)
      mcpBridge.getConfiguredDriverType(TrailblazeDevicePlatform.ANDROID)?.let {
        values[KEY_ANDROID_DRIVER] = it.name
      }
      mcpBridge.getConfiguredDriverType(TrailblazeDevicePlatform.IOS)?.let {
        values[KEY_IOS_DRIVER] = it.name
      }
      mcpBridge.getConfiguredDriverType(TrailblazeDevicePlatform.WEB)?.let {
        values[KEY_WEB_DRIVER] = it.name
      }

      // LLM settings from bridge
      mcpBridge.getLlmConfig()?.let { (provider, model) ->
        values[KEY_LLM_PROVIDER] = provider
        values[KEY_LLM_MODEL] = model
      }

      // Global agent implementation from bridge
      mcpBridge.getAgentImplementation()?.let { values[KEY_AGENT_IMPLEMENTATION] = it.name }

      // Session-level settings
      sessionContext?.let { ctx ->
        values[KEY_SCREENSHOT_FORMAT] = ctx.screenshotFormat.name
        values[KEY_VIEW_HIERARCHY_VERBOSITY] = ctx.viewHierarchyVerbosity.name
        values[KEY_TOOL_LOADING_STRATEGY] = ctx.toolLoadingStrategy.name
        values[KEY_TOOL_PROFILE] = ctx.toolProfile.name
      }

      return values
    }

    internal val CONFIG_KEYS = listOf(
      ConfigKeyDef(
        key = KEY_TARGET_APP,
        description = "Target app for device connections and custom tools",
      ),
      ConfigKeyDef(
        key = KEY_ANDROID_DRIVER,
        description = "Android device driver type",
        validValues = TrailblazeDriverType.entries
          .filter { it.platform == TrailblazeDevicePlatform.ANDROID }
          .map { it.name },
      ),
      ConfigKeyDef(
        key = KEY_IOS_DRIVER,
        description = "iOS device driver type",
        validValues = TrailblazeDriverType.entries
          .filter { it.platform == TrailblazeDevicePlatform.IOS }
          .map { it.name },
      ),
      ConfigKeyDef(
        key = KEY_WEB_DRIVER,
        description = "Web device driver type",
        validValues = TrailblazeDriverType.entries
          .filter { it.platform == TrailblazeDevicePlatform.WEB }
          .map { it.name },
      ),
      ConfigKeyDef(
        key = KEY_LLM_PROVIDER,
        description = "LLM provider (e.g., openai, anthropic, google, ollama)",
      ),
      ConfigKeyDef(
        key = KEY_LLM_MODEL,
        description = "LLM model ID (e.g., gpt-4.1, claude-sonnet-4-20250514)",
      ),
      ConfigKeyDef(
        key = KEY_AGENT_IMPLEMENTATION,
        description = "Agent architecture for automation",
        validValues = AgentImplementation.entries.map { it.name },
      ),
      ConfigKeyDef(
        key = KEY_SCREENSHOT_FORMAT,
        description = "How screenshots are included in tool responses",
        validValues = ScreenshotFormat.entries.map { it.name },
      ),
      ConfigKeyDef(
        key = KEY_VIEW_HIERARCHY_VERBOSITY,
        description = "Detail level for view hierarchy data",
        validValues = ViewHierarchyVerbosity.entries.map { it.name },
      ),
      ConfigKeyDef(
        key = KEY_TOOL_LOADING_STRATEGY,
        description = "How tools are loaded (ALL_TOOLS or PROGRESSIVE)",
        validValues = ToolLoadingStrategy.entries.map { it.name },
      ),
      ConfigKeyDef(
        key = KEY_TOOL_PROFILE,
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
