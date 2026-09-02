package xyz.block.trailblaze.host.driver

import xyz.block.trailblaze.http.DynamicLlmClient
import xyz.block.trailblaze.ui.TrailblazeDeviceManager
import java.io.File

/**
 * The host-side collaborators a [HostDriverDescriptor.runYaml] needs, bundled so the interface
 * doesn't grow a parameter every time one driver needs something a sibling doesn't.
 *
 * [logsDir] is here rather than on `RunOnHostParams` for the reason that class documents: it is
 * `commonMain` and cannot carry a [File].
 */
class HostRunDeps(
  val dynamicLlmClient: DynamicLlmClient,
  val deviceManager: TrailblazeDeviceManager,
  val logsDir: File?,
)
