package xyz.block.trailblaze.host.yaml
import xyz.block.trailblaze.model.TrailblazeHostAppTarget

data class DesktopYamlRunnerParams(
  val forceStopTargetApp: Boolean,
  val trailblazeHostAppTarget: TrailblazeHostAppTarget?,
)
