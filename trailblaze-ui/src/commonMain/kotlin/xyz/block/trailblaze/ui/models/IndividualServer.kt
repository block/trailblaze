package xyz.block.trailblaze.ui.models

import kotlinx.serialization.Serializable

@Serializable
data class IndividualServer(
  val name: String,
  val port: Int,
  val serverRunning: Boolean = true,
)
