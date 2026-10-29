package xyz.block.trailblaze.host.recording.rpc

import java.nio.file.Files
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.minimalDeviceManager
import xyz.block.trailblaze.host.rpc.GetToolCatalogRequest
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.ui.models.TrailblazeServerState.SavedTrailblazeAppConfig

/**
 * The Devices-screen palette lists a `trailblaze/requiredClassifiers` tool only for a device that
 * can load it — the palette must not offer a rig-only tool on a plain browser.
 */
class GetToolCatalogHandlerTest {

  private val rigTarget = object : TrailblazeHostAppTarget(id = "rigapp", displayName = "Rig App") {
    override fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): List<String>? =
      if (platform == TrailblazeDevicePlatform.WEB) listOf("rig-web") else null

    override fun internalGetCustomToolsForDriver(
      driverType: TrailblazeDriverType,
    ): Set<KClass<out TrailblazeTool>> = emptySet()

    override fun getInlineScriptTools(): List<InlineScriptToolConfig> = listOf(
      InlineScriptToolConfig(
        script = "./tools/rig_only.ts",
        name = "rig_only_tool",
        description = "Needs rig hardware",
        meta = buildJsonObject {
          put("trailblaze/requiredClassifiers", buildJsonArray { add(JsonPrimitive("rig")) })
          put("trailblaze/toolset", "rig_core")
        },
      ),
    )
  }

  private val browser = TrailblazeDeviceId("browser-1", TrailblazeDevicePlatform.WEB)

  private fun paletteFor(deviceClassifiers: List<String>, sendDevice: Boolean = true): List<String> {
    val handler = GetToolCatalogHandler(
      deviceManager = minimalDeviceManager(
        tempDir = Files.createTempDirectory("tool-catalog").toFile(),
        initialConfig = SavedTrailblazeAppConfig(
          selectedTrailblazeDriverTypes = emptyMap(),
          selectedTargetAppId = rigTarget.id,
        ),
        targets = setOf(rigTarget),
      ),
      classifyDevice = { deviceClassifiers.map(::TrailblazeDeviceClassifier) },
    )
    val request = GetToolCatalogRequest(
      trailblazeDriverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
      trailblazeDeviceId = browser.takeIf { sendDevice },
    )
    val result = runBlocking { handler.handle(request) }
    return (result as RpcResult.Success).data.tools.map { it.name }
  }

  @Test
  fun `a device carrying the classifier is offered the tool`() {
    assertContains(paletteFor(listOf("web", "rig")), "rig_only_tool")
  }

  @Test
  fun `a device without the classifier is not offered the tool`() {
    assertFalse("rig_only_tool" in paletteFor(listOf("web")))
  }

  @Test
  fun `a request without a device lists the tool`() {
    // No device to judge: an older caller still sees every tool that may apply.
    assertContains(paletteFor(listOf("web"), sendDevice = false), "rig_only_tool")
  }
}
