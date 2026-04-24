package xyz.block.trailblaze.yaml

import xyz.block.trailblaze.logs.client.TrailblazeSerializationInitializer

internal actual fun buildTrailblazeYamlDefault(): TrailblazeYaml =
  createTrailblazeYamlFromAllTools(
    TrailblazeSerializationInitializer.buildAllTools().values.toSet(),
  )
