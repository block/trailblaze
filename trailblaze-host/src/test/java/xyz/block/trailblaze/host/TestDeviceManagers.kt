package xyz.block.trailblaze.host

import java.io.File
import xyz.block.trailblaze.host.driver.HostDriverDescriptorRegistry
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.ui.TrailblazeAnalytics
import xyz.block.trailblaze.ui.TrailblazeDeviceManager
import xyz.block.trailblaze.ui.TrailblazeSettingsRepo
import xyz.block.trailblaze.ui.composables.DefaultDeviceClassifierIconProvider
import xyz.block.trailblaze.ui.models.AppIconProvider
import xyz.block.trailblaze.ui.models.TrailblazeServerState.SavedTrailblazeAppConfig

/**
 * A [TrailblazeDeviceManager] with every collaborator stubbed to "not in this test", for tests
 * that need a real manager instance (descriptor deps, registry lookups) but never a device, an
 * LLM, or a YAML run.
 */
fun minimalDeviceManager(
  tempDir: File,
  registry: HostDriverDescriptorRegistry = HostDriverDescriptorRegistry.EMPTY,
  initialConfig: SavedTrailblazeAppConfig = SavedTrailblazeAppConfig(selectedTrailblazeDriverTypes = emptyMap()),
): TrailblazeDeviceManager = TrailblazeDeviceManager(
  logsRepo = LogsRepo(logsDir = File(tempDir, "logs").also { it.mkdirs() }, watchFileSystem = false),
  settingsRepo = TrailblazeSettingsRepo(
    settingsFile = File(tempDir, "settings.json"),
    initialConfig = initialConfig,
    defaultHostAppTarget = TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget,
    allTargetApps = { emptySet() },
    supportedDriverTypes = emptySet(),
  ),
  defaultHostAppTarget = TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget,
  currentTrailblazeLlmModelProvider = { error("LLM not available in tests") },
  initialAppTargets = emptySet(),
  appIconProvider = AppIconProvider.DefaultAppIconProvider,
  deviceClassifierIconProvider = DefaultDeviceClassifierIconProvider,
  runYamlLambda = { error("YAML runner not available in tests") },
  installedAppIdsProviderBlocking = { emptySet() },
  appVersionInfoProviderBlocking = { _, _ -> null },
  onDeviceInstrumentationArgsProvider = { emptyMap() },
  trailblazeAnalytics = TrailblazeAnalytics.NoOp,
  hostDriverDescriptors = registry,
)
