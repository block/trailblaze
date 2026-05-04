package xyz.block.trailblaze.waypoint

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.logs.client.TrailblazeJson
import java.io.File

object SessionLogScreenState {

  fun loadStep(jsonFile: File): ScreenState {
    require(jsonFile.exists()) { "Session log file not found: $jsonFile" }
    val raw = jsonFile.readText()
    val parsed = TrailblazeJson.defaultWithoutToolsInstance
      .decodeFromString(LlmRequestLogProjection.serializer(), raw)
    val screenshotPath = parsed.screenshotFile?.let { File(jsonFile.parentFile, it) }
    val screenshotBytes = screenshotPath?.takeIf { it.exists() }?.readBytes()
    val platform = parsed.trailblazeDevicePlatform
      ?: TrailblazeDevicePlatform.ANDROID
    return SessionLogScreenStateImpl(
      screenshotBytes = screenshotBytes,
      deviceWidth = parsed.deviceWidth,
      deviceHeight = parsed.deviceHeight,
      viewHierarchy = parsed.viewHierarchy
        ?: ViewHierarchyTreeNode(),
      trailblazeNodeTree = parsed.trailblazeNodeTree,
      trailblazeDevicePlatform = platform,
    )
  }

  fun listLlmRequestLogs(sessionDir: File): List<File> {
    require(sessionDir.isDirectory) { "Not a session directory: $sessionDir" }
    return sessionDir.listFiles { f -> f.name.endsWith("_TrailblazeLlmRequestLog.json") }
      ?.sortedBy { it.name }
      ?: emptyList()
  }

  @Serializable
  private data class LlmRequestLogProjection(
    val deviceWidth: Int = 0,
    val deviceHeight: Int = 0,
    val viewHierarchy: ViewHierarchyTreeNode? = null,
    val trailblazeNodeTree: TrailblazeNode? = null,
    val screenshotFile: String? = null,
    val trailblazeDevicePlatform: TrailblazeDevicePlatform? = null,
  )

  private class SessionLogScreenStateImpl(
    override val screenshotBytes: ByteArray?,
    override val deviceWidth: Int,
    override val deviceHeight: Int,
    override val viewHierarchy: ViewHierarchyTreeNode,
    override val trailblazeNodeTree: TrailblazeNode?,
    override val trailblazeDevicePlatform: TrailblazeDevicePlatform,
  ) : ScreenState {
    override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()
  }
}
