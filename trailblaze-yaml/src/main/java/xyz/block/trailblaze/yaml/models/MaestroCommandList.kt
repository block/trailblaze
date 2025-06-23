package xyz.block.trailblaze.yaml.models

import kotlinx.serialization.Serializable
import maestro.orchestra.Command

@Serializable
data class MaestroCommandList(
  val maestroCommands: List<Command>,
)
