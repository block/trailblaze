package xyz.block.trailblaze.ui.models

import kotlinx.serialization.Serializable

@Serializable
data class TrailblazeServerState(
  val logsServer: IndividualServer = IndividualServer(
    name = "Trailblaze Logs and MCP Server",
    port = 52525,
  ),
)
