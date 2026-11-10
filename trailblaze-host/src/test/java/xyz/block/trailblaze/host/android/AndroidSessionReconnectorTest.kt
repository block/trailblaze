package xyz.block.trailblaze.host.android

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.logs.model.SessionId

/**
 * Pins the order [AndroidSessionReconnector.reconnect] does things in, with every device touch
 * recorded in one event list: a reconnect before the cached adb state is forgotten would trust
 * forwards the adb server dropped with the old transport, and a stream resume after a failed
 * reconnect would attach logcat to a runner that never came back.
 */
class AndroidSessionReconnectorTest {

  private val events = mutableListOf<String>()

  private fun reconnector(reconnectFails: Boolean = false) = AndroidSessionReconnector(
    onProgressMessage = {},
    reconnectDevice = {
      events += "reconnect"
      if (reconnectFails) throw IOException("runner did not come back")
    },
    onReconnected = { events += "onReconnected(${it.value})" },
    forgetDeviceState = { events += "forget(${it.instanceId})" },
  )

  private suspend fun run(reconnector: AndroidSessionReconnector): String = reconnector.reconnect(
    deviceId = DEVICE,
    sessionId = { SessionId("session-1") },
    beforeReconnect = { events += "beforeReconnect" },
  )

  @Test
  fun `forgets adb state, drops caller state, reconnects, then resumes streams`() = runBlocking {
    val summary = run(reconnector())

    assertEquals(
      listOf("forget(emulator-5554)", "beforeReconnect", "reconnect", "onReconnected(session-1)"),
      events,
    )
    assertEquals("Session reconnected to emulator-5554.", summary)
  }

  @Test
  fun `a failed reconnect propagates without resuming streams`() = runBlocking {
    assertFailsWith<IOException> { run(reconnector(reconnectFails = true)) }

    assertEquals(listOf("forget(emulator-5554)", "beforeReconnect", "reconnect"), events)
  }

  private companion object {
    val DEVICE = TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID)
  }
}
