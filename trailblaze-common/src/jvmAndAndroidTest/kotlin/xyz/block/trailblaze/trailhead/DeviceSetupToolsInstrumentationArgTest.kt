package xyz.block.trailblaze.trailhead

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.DeviceSetupToolsMemory

class DeviceSetupToolsInstrumentationArgTest {

  private fun urlSafe(json: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())

  @Test
  fun `a configuration arrives in memory as the JSON the host would have seeded`() {
    val json = """[{"sample_setAppearance":{"appearance":"dark"}},{"mobile_setDeviceLocale":{"locale":"ja-JP"}}]"""
    val memory = AgentMemory()

    DeviceSetupToolsInstrumentationArg.seed(memory, urlSafe(json))

    assertEquals(json, memory.variables[DeviceSetupToolsMemory.DEVICE_SETUP_TOOLS_MEMORY_KEY])
  }

  @Test
  fun `the farm's argument alphabet survives bytes that standard base64 renders as plus and slash`() {
    // Chosen so the standard encoding contains both `+` and `/`.
    val json = """[{"t":{"a":"~~~???>>>"}}]"""
    assertEquals(true, Base64.getEncoder().encodeToString(json.toByteArray()).let { '+' in it || '/' in it })

    assertEquals(json, DeviceSetupToolsInstrumentationArg.decode(urlSafe(json)))
  }

  @Test
  fun `no argument leaves memory untouched`() {
    val memory = AgentMemory()

    DeviceSetupToolsInstrumentationArg.seed(memory, null)
    DeviceSetupToolsInstrumentationArg.seed(memory, "  ")

    assertNull(memory.variables[DeviceSetupToolsMemory.DEVICE_SETUP_TOOLS_MEMORY_KEY])
    assertNull(DeviceSetupToolsInstrumentationArg.decode(null))
  }

  @Test
  fun `an argument that is not a base64 JSON array fails rather than running without them`() {
    assertFailsWith<IllegalArgumentException> { DeviceSetupToolsInstrumentationArg.decode("not base64 !!") }
    assertFailsWith<IllegalArgumentException> { DeviceSetupToolsInstrumentationArg.decode(urlSafe("not json")) }
    assertFailsWith<IllegalArgumentException> { DeviceSetupToolsInstrumentationArg.decode(urlSafe("""{"a":1}""")) }
  }
}
