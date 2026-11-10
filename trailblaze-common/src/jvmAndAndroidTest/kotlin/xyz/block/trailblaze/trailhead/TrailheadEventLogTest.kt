package xyz.block.trailblaze.trailhead

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.events.ActiveSessionEventsDir
import xyz.block.trailblaze.events.SessionEvents
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext

class TrailheadEventLogTest {

  private val hostDir: File = Files.createTempDirectory("trailhead-host").toFile()
  private val deviceDir: File = Files.createTempDirectory("trailhead-device").toFile()
  private val session =
    TrailblazeSession(sessionId = SessionId("s-${System.nanoTime()}"), startTime = Clock.System.now())

  @AfterTest
  fun cleanUp() {
    ActiveSessionEventsDir.clear()
    hostDir.deleteRecursively()
    deviceDir.deleteRecursively()
  }

  private fun context(hostSessionDir: File?) =
    TrailblazeToolExecutionContext(
      screenState = null,
      traceId = null,
      trailblazeDeviceInfo =
        TrailblazeDeviceInfo(
          trailblazeDeviceId =
            TrailblazeDeviceId(
              instanceId = "emulator-5554",
              trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
            ),
          trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
          widthPixels = 1080,
          heightPixels = 1920,
        ),
      sessionProvider = TrailblazeSessionProvider { session },
      trailblazeLogger = TrailblazeLogger.createNoOp(),
      memory = AgentMemory(),
      sessionDirProvider = hostSessionDir?.let { dir -> { _: SessionId -> dir } },
    )

  private fun rowTypes(dir: File): List<String> {
    val file = File(dir, "${SessionEvents.DIR_NAME}/${TrailheadEventStream.NAME}.${SessionEvents.EXTENSION}")
    if (!file.isFile) return emptyList()
    return file.readLines().filter { it.isNotBlank() }.map { line ->
      Json.parseToJsonElement(line).jsonObject["data"]!!.jsonObject["type"]!!.jsonPrimitive.content
    }
  }

  @Test
  fun `on a device with no host session dir, the stream is written to the registered folder`() {
    ActiveSessionEventsDir.set(deviceDir)

    TrailheadEventLog.append(context(hostSessionDir = null), TrailheadEventStream.Type.TRAILHEAD_START) {}
    TrailheadEventLog.append(context(hostSessionDir = null), TrailheadEventStream.Type.SETUP) { put("setup", "fresh") }

    assertEquals(
      listOf(TrailheadEventStream.Type.TRAILHEAD_START, TrailheadEventStream.Type.SETUP),
      rowTypes(deviceDir),
    )
  }

  @Test
  fun `a host session dir wins over a registered folder`() {
    ActiveSessionEventsDir.set(deviceDir)

    TrailheadEventLog.append(context(hostSessionDir = hostDir), TrailheadEventStream.Type.TRAILHEAD_START) {}

    assertEquals(listOf(TrailheadEventStream.Type.TRAILHEAD_START), rowTypes(hostDir))
    assertEquals(emptyList(), rowTypes(deviceDir))
  }

  @Test
  fun `with neither a session dir nor a registered folder, nothing is written`() {
    TrailheadEventLog.append(context(hostSessionDir = null), TrailheadEventStream.Type.TRAILHEAD_START) {}

    assertFalse(File(deviceDir, SessionEvents.DIR_NAME).exists())
  }
}
