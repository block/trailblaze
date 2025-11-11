package xyz.block.trailblaze.serialization

import xyz.block.trailblaze.logs.client.TrailblazeJson
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.model.CustomTrailblazeTools

object TrailblazeJsonExt {
  fun TrailblazeJson.initializeWithCustomTools(trailblazeCustomTools: CustomTrailblazeTools) {
    val jsonInstance = TrailblazeJson.createTrailblazeJsonInstance(
      trailblazeCustomTools.allForSerializationToolsByName(),
    )
    TrailblazeJsonInstance = jsonInstance
    TrailblazeJson.defaultWithoutToolsInstance = jsonInstance
  }
}
