package xyz.block.trailblaze.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class JvmLiveSessionDataProviderStopTest {

  @Test
  fun `Stop drains the on-device runner before killing it`() = runBlocking {
    val steps = mutableListOf<String>()

    JvmLiveSessionDataProvider.stopOnDeviceRunner(drain = { steps += "drain" }, forceStop = { steps += "forceStop" })

    assertEquals(listOf("drain", "forceStop"), steps, "a killed runner can't finish the last action's log uploads")
  }
}
