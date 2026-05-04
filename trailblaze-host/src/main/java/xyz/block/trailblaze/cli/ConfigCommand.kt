package xyz.block.trailblaze.cli

import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import xyz.block.trailblaze.desktop.LlmTokenStatus
import xyz.block.trailblaze.desktop.TrailblazeDesktopAppConfig
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.llm.LlmProviderEnvVarUtil
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.runQuiet
import java.util.concurrent.Callable
import kotlin.system.exitProcess

/**
 * View and modify Trailblaze configuration.
 *
 * Examples:
 *   trailblaze config                                 - Show current settings
 *   trailblaze config target myapp                    - Set target app
 *   trailblaze config llm openai/gpt-4.1-mini         - Set LLM provider/model
 *   trailblaze config models                           - List available LLM models
 *   trailblaze config target                           - List available target apps
 */
@Command(
  name = "config",
  mixinStandardHelpOptions = true,
  description = ["View and set configuration (target app, device defaults, AI provider)"],
  subcommands = [
    ConfigShowCommand::class,
    ConfigTargetCommand::class,
    ConfigModelsCommand::class,
    ConfigResetCommand::class,
  ],
)
class ConfigCommand : Callable<Int> {

  @CommandLine.ParentCommand
  private lateinit var parent: TrailblazeCliCommand

  @Parameters(index = "0", arity = "0..1", description = ["Config key to get or set"])
  var key: String? = null

  @Parameters(index = "1", arity = "0..1", description = ["Value to set"])
  var value: String? = null

  fun getConfigProvider(): TrailblazeDesktopAppConfig = parent.configProvider()

  override fun call(): Int {
    // `config llm` with no value shows the full LLM details (providers + models)
    if (key == "llm" && value == null) {
      return showLlmConfig()
    }
    return executeConfig(key, value)
  }

  /**
   * Shared config get/set logic used by both [ConfigCommand] (bare `trailblaze config`)
   * and [ConfigShowCommand] (`trailblaze config show` via sq).
   */
  internal fun executeConfig(key: String?, value: String?): Int {
    if (key == null) {
      // No args: show all config + auth status (requires configProvider)
      return showAllConfig()
    }

    val configKey = CONFIG_KEYS[key]
    if (configKey == null) {
      Console.error("Unknown config key: $key")
      Console.error("Valid keys: ${CONFIG_KEYS.keys.joinToString(", ")}")
      return CommandLine.ExitCode.USAGE
    }

    if (value == null) {
      // Key only: show that key's value
      val currentConfig = CliConfigHelper.getOrCreateConfig()
      Console.log(configKey.get(currentConfig))
      return CommandLine.ExitCode.OK
    }

    // Key + value: set and save
    val currentConfig = CliConfigHelper.getOrCreateConfig()
    val updatedConfig = configKey.set(currentConfig, value)
    if (updatedConfig == null) {
      Console.error("Invalid value for $key: $value")
      Console.error("Valid values: ${configKey.validValues}")
      return CommandLine.ExitCode.USAGE
    }
    CliConfigHelper.writeConfig(updatedConfig)
    Console.log("Set $key: ${configKey.get(updatedConfig)}")
    return CommandLine.ExitCode.OK
  }

  private fun showAllConfig(): Int {
    val currentConfig = CliConfigHelper.getOrCreateConfig()

    // Fetch provider data (triggers full init). `runQuiet` ensures the daemon's quiet-mode
    // flag is restored even if `getConfigProvider()` throws — without it, a config-provider
    // failure would silence every subsequent `Console.log` for the lifetime of the daemon.
    val config = Console.runQuiet { getConfigProvider() }

    // Show which settings file is in effect — `getSettingsFile()` prefers a
    // git-root `.trailblaze/` over `~/.trailblaze/`, which surprises users when
    // a project-local file exists with stale or unexpected values. Print the
    // path up front (as the very first line of the report) so what's reported
    // below has an obvious source of truth.
    Console.info("Settings file: ${CliConfigHelper.getSettingsFile().absolutePath}")

    // LLM section: model + auth, selected provider first
    val currentProvider = currentConfig.llmProvider
    Console.info("")
    Console.info("LLM:")
    if (currentProvider == LLM_NONE) {
      Console.info("  * [-] none — no LLM configured")
    }
    val tokenStatuses = config.getAllLlmTokenStatuses()
    if (tokenStatuses.isNotEmpty()) {
      val sorted = tokenStatuses.entries.sortedBy { it.key.display }
      val selectedFirst = sorted.filter { it.key.id == currentProvider } +
        sorted.filter { it.key.id != currentProvider && it.value !is LlmTokenStatus.NotAvailable } +
        sorted.filter { it.key.id != currentProvider && it.value is LlmTokenStatus.NotAvailable }
      selectedFirst.forEach { (provider, status) ->
        val prefix = if (provider.id == currentProvider) "*" else " "
        val statusIcon = when (status) {
          is LlmTokenStatus.Available -> "+"
          is LlmTokenStatus.Expired -> "!"
          is LlmTokenStatus.NotAvailable -> "-"
        }
        val statusText = when (status) {
          is LlmTokenStatus.Available -> "Available"
          is LlmTokenStatus.Expired -> "Expired (may need refresh)"
          is LlmTokenStatus.NotAvailable -> "Not configured"
        }
        val model = if (provider.id == currentProvider) " (${currentConfig.llmModel})" else ""
        Console.info("  $prefix [$statusIcon] ${provider.display}$model: $statusText")
      }
    }

    // Target section: selected first
    val currentTargetId = currentConfig.selectedTargetAppId
    Console.info("")
    Console.info("Targets:")
    val targets = config.availableAppTargets.sortedBy { it.displayName }
    if (targets.isNotEmpty()) {
      val selectedFirst = targets.filter { it.id == currentTargetId } +
        targets.filter { it.id != currentTargetId }
      for (target in selectedFirst) {
        val prefix = if (target.id == currentTargetId) "*" else " "
        Console.info("  $prefix [${target.id}] ${target.displayName}")
      }
    }

    // Drivers section: show per-platform driver selection. `(default)` marks each platform's
    // built-in default regardless of what's currently selected; `*` marks the active driver.
    Console.info("")
    Console.info("Drivers:")
    listOf(
      "Android:" to TrailblazeDevicePlatform.ANDROID,
      "iOS:" to TrailblazeDevicePlatform.IOS,
    ).forEach { (label, platform) ->
      printDriverRow(
        label = label,
        current = currentConfig.selectedTrailblazeDriverTypes[platform],
        default = TrailblazeDriverType.defaultForPlatform(platform),
        options = TrailblazeDriverType.selectableForPlatform(platform),
      )
    }

    Console.info("")
    Console.info("  trailblaze config llm <provider/model>   Set LLM")
    Console.info("  trailblaze config llm none               Disable LLM")
    Console.info("  trailblaze config target <name>          Set target app")
    Console.info("  trailblaze config android-driver <type>  Set Android driver (instrumentation|accessibility)")
    Console.info("  trailblaze config ios-driver <type>      Set iOS driver (host|axe)")
    Console.info("  trailblaze config models                 List available models")
    Console.info("  trailblaze config reset                  Reset all settings to defaults")
    Console.info("")
    Console.info("For advanced settings, use the Trailblaze desktop app.")
    Console.info("")

    // Force exit to terminate background services started by configProvider.
    // When running forwarded inside the daemon (DaemonSettingsBridge wired up),
    // those background services *are* the daemon — killing them takes the whole
    // daemon down. Return normally instead and let the IPC fast path return
    // captured stdout to the shell shim.
    if (DaemonSettingsBridge.settingsRepo != null) return CommandLine.ExitCode.OK
    exitProcess(CommandLine.ExitCode.OK)
  }

  /**
   * Prints a single per-platform driver row. The active row (user selection, falling back to
   * [default] when unset) is marked `* [x]`; every other row is `  [ ]`. Each driver that is
   * the platform's built-in [default] gets a trailing ` (default)` label regardless of the
   * current selection, so readers can see both what's selected and what the platform defaults
   * to without having to guess.
   */
  private fun printDriverRow(
    label: String,
    current: TrailblazeDriverType?,
    default: TrailblazeDriverType?,
    options: List<TrailblazeDriverType>,
  ) {
    val effective = current ?: default
    Console.info("  $label")
    for (driverType in options) {
      val selected = driverType == effective
      val isDefault = driverType == default
      val prefix = if (selected) "*" else " "
      val check = if (selected) "x" else " "
      val defaultTag = if (isDefault) " (default)" else ""
      Console.info("    $prefix [$check] ${driverType.cliShortName}$defaultTag")
    }
  }

  private fun showLlmConfig(): Int {
    val currentConfig = CliConfigHelper.getOrCreateConfig()

    // Fetch provider data (triggers full init). See `showAllConfig` for the runQuiet rationale.
    val config = Console.runQuiet { getConfigProvider() }

    val currentProvider = currentConfig.llmProvider

    // Current selection
    Console.info("")
    if (currentProvider == LLM_NONE) {
      Console.info("Current: none — no LLM configured")
    } else {
      Console.info("Current: ${currentConfig.llmProvider}/${currentConfig.llmModel}")
    }

    // Providers with models listed underneath, ready to copy-paste
    val tokenStatuses = config.getAllLlmTokenStatuses()
    val modelLists = config.getAllSupportedLlmModelLists()
    val modelsByProvider = modelLists.associateBy { it.provider.id }

    // Available providers first (with models), then unconfigured providers (no models)
    val available = tokenStatuses.entries
      .filter { it.value !is LlmTokenStatus.NotAvailable }
      .sortedBy { it.key.display }
    val unavailable = tokenStatuses.entries
      .filter { it.value is LlmTokenStatus.NotAvailable }
      .sortedBy { it.key.display }

    for ((provider, status) in available) {
      val statusIcon = when (status) {
        is LlmTokenStatus.Available -> "+"
        is LlmTokenStatus.Expired -> "!"
        else -> "-"
      }
      val statusText = when (status) {
        is LlmTokenStatus.Available -> "Available"
        is LlmTokenStatus.Expired -> "Expired (may need refresh)"
        else -> ""
      }
      Console.info("")
      Console.info("  [$statusIcon] ${provider.display}: $statusText")
      modelsByProvider[provider.id]?.entries?.forEach { model ->
        val selected = if (
          model.modelId == currentConfig.llmModel &&
          provider.id == currentProvider
        ) "  *" else ""
        Console.info("      ${provider.id}/${model.modelId}$selected")
      }
    }

    if (unavailable.isNotEmpty()) {
      Console.info("")
      for ((provider, _) in unavailable) {
        val envVar = LlmProviderEnvVarUtil.getEnvironmentVariableKeyForProvider(provider)
        val hint = if (envVar != null) " ($envVar)" else ""
        Console.info("  [-] ${provider.display}: Not configured$hint")
      }
    }

    Console.info("")
    Console.info("  trailblaze config llm <provider/model>  Set LLM")
    Console.info("  trailblaze config llm none              Disable LLM")
    Console.info("")

    // Force exit to terminate background services started by configProvider.
    // When running forwarded inside the daemon (DaemonSettingsBridge wired up),
    // those background services *are* the daemon — killing them takes the whole
    // daemon down. Return normally instead and let the IPC fast path return
    // captured stdout to the shell shim.
    if (DaemonSettingsBridge.settingsRepo != null) return CommandLine.ExitCode.OK
    exitProcess(CommandLine.ExitCode.OK)
  }
}

/**
 * Show all settings and authentication status.
 *
 * Explicit subcommand for `sq` CLI integration — equivalent to bare `trailblaze config`.
 */
@Command(
  name = "show",
  mixinStandardHelpOptions = true,
  description = ["Show all settings and authentication status"],
)
class ConfigShowCommand : Callable<Int> {

  @CommandLine.ParentCommand
  private lateinit var parent: ConfigCommand

  override fun call(): Int = parent.executeConfig(key = null, value = null)
}

/**
 * List available target apps, or set the active target.
 *
 * Examples:
 *   trailblaze config target             - List available targets
 *   trailblaze config target myapp       - Set target to "myapp"
 *   trailblaze config target default     - Use the default (no-app) target
 */
@Command(
  name = "target",
  mixinStandardHelpOptions = true,
  description = ["List or set the target app"],
)
class ConfigTargetCommand : Callable<Int> {

  @CommandLine.ParentCommand
  private lateinit var parent: ConfigCommand

  @Parameters(index = "0", arity = "0..1", description = ["Target app ID to set"])
  var targetId: String? = null

  override fun call(): Int {
    if (targetId != null) {
      return applyTarget()
    }
    return listTargets()
  }

  private fun applyTarget(): Int {
    var currentConfig = CliConfigHelper.getOrCreateConfig()
    val normalizedTarget = targetId!!.lowercase()
    if (!TrailblazeHostAppTarget.isValidId(normalizedTarget)) {
      Console.error(
        "Error: target must be lowercase alphanumeric, hyphens, or underscores only (got '$targetId').\n" +
          "Run 'trailblaze config target' to see available targets."
      )
      return CommandLine.ExitCode.USAGE
    }
    // Format-valid but unknown target ids would otherwise persist silently and
    // resurface later as a confusing "Target app changed (X → <unknown>)" line
    // in subsequent device commands. Resolve once against the same target list
    // we'd print in the LIST view, and reject anything that isn't there.
    val availableIds = Console.runQuiet {
      parent.getConfigProvider().availableAppTargets.map { it.id }.toSet()
    }
    Console.log("config target validation: resolved ${availableIds.size} target ids")
    if (normalizedTarget !in availableIds) {
      Console.error(
        "Error: '$normalizedTarget' is not a known target app.\n" +
          "Available: ${availableIds.sorted().joinToString(", ")}"
      )
      return CommandLine.ExitCode.USAGE
    }
    currentConfig = currentConfig.copy(selectedTargetAppId = normalizedTarget)
    CliConfigHelper.writeConfig(currentConfig)
    Console.log("Target set: $normalizedTarget")
    return CommandLine.ExitCode.OK
  }

  private fun listTargets(): Int {
    val config = Console.runQuiet { parent.getConfigProvider() }
    val currentTargetId = CliConfigHelper.readConfig()?.selectedTargetAppId
    val targets = config.availableAppTargets.sortedBy { it.displayName }

    Console.info("")
    Console.info("Available Targets:")
    Console.info(ITEM_DIVIDER)

    for (target in targets) {
      val current = if (target.id == currentTargetId) " (current)" else ""
      Console.info("  [${target.id}] ${target.displayName}$current")
    }

    Console.info("")
    Console.info("Set with: trailblaze config target <id>")
    Console.log("")

    // Force exit to terminate background services started by configProvider.
    // When running forwarded inside the daemon (DaemonSettingsBridge wired up),
    // those background services *are* the daemon — killing them takes the whole
    // daemon down. Return normally instead and let the IPC fast path return
    // captured stdout to the shell shim.
    if (DaemonSettingsBridge.settingsRepo != null) return CommandLine.ExitCode.OK
    exitProcess(CommandLine.ExitCode.OK)
  }
}

/**
 * List available LLM models grouped by provider.
 */
@Command(
  name = "models",
  mixinStandardHelpOptions = true,
  description = ["List available LLM models by provider"],
)
class ConfigModelsCommand : Callable<Int> {

  @CommandLine.ParentCommand
  private lateinit var parent: ConfigCommand

  override fun call(): Int {
    val config = Console.runQuiet { parent.getConfigProvider() }
    val modelLists = config.getAllSupportedLlmModelLists()

    Console.info("")
    Console.info("Available LLM Models:")
    Console.info(SECTION_DIVIDER)

    for (modelList in modelLists.sortedBy { it.provider.id }) {
      Console.info("")
      Console.info("${modelList.provider.display} (${modelList.provider.id}):")
      Console.info("-".repeat(40))
      for (model in modelList.entries) {
        val contextK = model.contextLength / 1000
        val vision = if (model.capabilities.none { it.id == "image" }) "  no vision" else ""
        Console.info("  %-35s %6dK%s".format(model.modelId, contextK, vision))
      }
    }

    Console.info("")

    // Force exit to terminate background services started by configProvider.
    // When running forwarded inside the daemon (DaemonSettingsBridge wired up),
    // those background services *are* the daemon — killing them takes the whole
    // daemon down. Return normally instead and let the IPC fast path return
    // captured stdout to the shell shim.
    if (DaemonSettingsBridge.settingsRepo != null) return CommandLine.ExitCode.OK
    exitProcess(CommandLine.ExitCode.OK)
  }
}

/**
 * Reset all configuration to defaults.
 *
 * Examples:
 *   trailblaze config reset              - Reset all settings to defaults
 */
@Command(
  name = "reset",
  mixinStandardHelpOptions = true,
  description = ["Reset all settings to defaults"],
)
class ConfigResetCommand : Callable<Int> {

  override fun call(): Int {
    val defaults = CliConfigHelper.defaultConfig()
    CliConfigHelper.writeConfig(defaults)
    Console.log("Config reset to defaults.")
    return CommandLine.ExitCode.OK
  }
}

/** Hidden alias: list drivers (advanced, kept for backwards compat). */
@Command(name = "drivers", hidden = true, mixinStandardHelpOptions = true, description = ["List available driver types"])
class ConfigDriversCommand : Callable<Int> {
  override fun call(): Int {
    Console.info("Driver configuration is available in the Trailblaze desktop app.")
    Console.log("")
    return CommandLine.ExitCode.OK
  }
}
