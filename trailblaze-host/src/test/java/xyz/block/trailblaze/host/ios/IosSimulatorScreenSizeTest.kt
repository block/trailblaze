package xyz.block.trailblaze.host.ios

import java.io.File
import java.nio.file.Files
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import xyz.block.trailblaze.host.ios.IosSimulatorScreenSize.Pixels
import xyz.block.trailblaze.util.SimctlCommand

/**
 * Pins how [IosSimulatorScreenSize] reads a simulator's screen size from disk: the JSON parsers,
 * and the whole read against a stand-in Mac. The JSON shapes are trimmed copies of real
 * `simctl list devicetypes -j` and `plutil -convert json capabilities.plist` output.
 */
class IosSimulatorScreenSizeTest {

  @Test
  fun `the integrated display is the screen, not the external or CarPlay displays`() {
    val json = """
      {"capabilities": {"displays": [
        {"screenID": 1, "displayType": "integrated", "width": 1206, "height": 2622, "scale": 3},
        {"screenID": 2, "displayType": "tvOut", "width": 720, "height": 480, "scale": 1},
        {"screenID": 3, "displayType": "carPlay", "width": 720, "height": 480, "scale": 1}
      ]}}
    """.trimIndent()
    assertEquals(Pixels(1206, 2622), IosSimulatorScreenSize.parseIntegratedDisplay(json))
  }

  @Test
  fun `a display list with no integrated display reads as unknown`() {
    val json = """{"capabilities": {"displays": [{"screenID": 2, "displayType": "tvOut", "width": 720, "height": 480}]}}"""
    assertNull(IosSimulatorScreenSize.parseIntegratedDisplay(json))
  }

  @Test
  fun `a missing or non-positive size reads as unknown`() {
    assertNull(IosSimulatorScreenSize.parseIntegratedDisplay("""{"capabilities": {"displays": [{"displayType": "integrated", "width": 1206}]}}"""))
    assertNull(IosSimulatorScreenSize.parseIntegratedDisplay("""{"capabilities": {"displays": [{"displayType": "integrated", "width": 0, "height": 2622}]}}"""))
    assertNull(IosSimulatorScreenSize.parseIntegratedDisplay("not json"))
  }

  @Test
  fun `device types map from identifier to bundle path`() {
    val json = """
      {"devicetypes": [
        {"productFamily": "iPhone", "identifier": "com.apple.CoreSimulator.SimDeviceType.iPhone-17-Pro",
         "bundlePath": "/Library/Developer/CoreSimulator/Profiles/DeviceTypes/iPhone 17 Pro.simdevicetype", "name": "iPhone 17 Pro"},
        {"productFamily": "iPad", "identifier": "com.apple.CoreSimulator.SimDeviceType.iPad-mini-A17-Pro",
         "bundlePath": "/Library/Developer/CoreSimulator/Profiles/DeviceTypes/iPad mini (A17 Pro).simdevicetype", "name": "iPad mini (A17 Pro)"},
        {"identifier": "no.bundle.path"}
      ]}
    """.trimIndent()
    assertEquals(
      mapOf(
        "com.apple.CoreSimulator.SimDeviceType.iPhone-17-Pro" to
          "/Library/Developer/CoreSimulator/Profiles/DeviceTypes/iPhone 17 Pro.simdevicetype",
        "com.apple.CoreSimulator.SimDeviceType.iPad-mini-A17-Pro" to
          "/Library/Developer/CoreSimulator/Profiles/DeviceTypes/iPad mini (A17 Pro).simdevicetype",
      ),
      IosSimulatorScreenSize.parseDeviceTypeBundles(json),
    )
    assertEquals(emptyMap(), IosSimulatorScreenSize.parseDeviceTypeBundles("not json"))
  }

  /**
   * A stand-in Mac: simulators as `device.plist` files under a temp home, device-type bundles as
   * temp folders holding `capabilities.plist`, and `plutil` / `simctl` answered from those maps.
   * Lets [IosSimulatorScreenSize.read] run end to end without Xcode.
   */
  private class FakeMac {
    val root: File = Files.createTempDirectory("ios-screen-size-").toFile()
    val home = File(root, "home")
    private val deviceTypeByPlist = mutableMapOf<String, String>()
    private val capabilitiesByPlist = mutableMapOf<String, String>()
    var listing: SimctlCommand.Result? = null

    fun simulator(udid: String, deviceType: String) {
      val plist = File(SimctlCli.deviceDirectory(udid, home), "device.plist")
      plist.parentFile.mkdirs()
      plist.writeText("<plist/>")
      deviceTypeByPlist[plist.path] = deviceType
    }

    fun bundle(name: String, width: Int, height: Int): File {
      val bundle = File(root, "$name.simdevicetype")
      val capabilities = File(bundle, "Contents/Resources/capabilities.plist")
      capabilities.parentFile.mkdirs()
      capabilities.writeText("binary plist")
      capabilitiesByPlist[capabilities.path] =
        """{"capabilities": {"displays": [{"displayType": "integrated", "width": $width, "height": $height}]}}"""
      return bundle
    }

    fun list(vararg types: Pair<String, File>) {
      val entries = types.joinToString(",") { (id, bundle) -> """{"identifier": "$id", "bundlePath": "${bundle.path}"}""" }
      listing = SimctlCommand.Result(0, """{"devicetypes": [$entries]}""", "")
    }

    fun run(args: List<String>, @Suppress("UNUSED_PARAMETER") timeoutSeconds: Long): SimctlCommand.Result = when {
      args.first() == "plutil" && "-extract" in args -> answer(deviceTypeByPlist[args.last()])
      args.first() == "plutil" && "-convert" in args -> answer(capabilitiesByPlist[args.last()])
      "devicetypes" in args -> listing ?: SimctlCommand.Result(1, "", "simctl failed")
      else -> error("unexpected command $args")
    }

    private fun answer(stdout: String?) =
      if (stdout != null) SimctlCommand.Result(0, stdout, "") else SimctlCommand.Result(1, "", "no such key")

    fun reader() = IosSimulatorScreenSize(runCommand = ::run, userHome = { home })
  }

  private fun withMac(block: (FakeMac) -> Unit) {
    val mac = FakeMac()
    try {
      block(mac)
    } finally {
      mac.root.deleteRecursively()
    }
  }

  @Test
  fun `an iPhone and an iPad read their screen sizes from disk`() = withMac { mac ->
    mac.simulator("PHONE", IPHONE)
    mac.simulator("TABLET", IPAD)
    mac.list(IPHONE to mac.bundle("iPhone 17 Pro", 1206, 2622), IPAD to mac.bundle("iPad A16", 1640, 2360))
    val reader = mac.reader()
    assertEquals(Pixels(1206, 2622), reader.read("PHONE"))
    assertEquals(Pixels(1640, 2360), reader.read("TABLET"))
  }

  @Test
  fun `a failed device-type listing reads as unknown, and the next read retries it`() = withMac { mac ->
    mac.simulator("PHONE", IPHONE)
    val bundle = mac.bundle("iPhone 17 Pro", 1206, 2622)
    val reader = mac.reader()
    assertNull(reader.read("PHONE"))
    mac.list(IPHONE to bundle)
    assertEquals(Pixels(1206, 2622), reader.read("PHONE"))
  }

  @Test
  fun `a device type whose bundle moved is found at its new location`() = withMac { mac ->
    mac.simulator("PHONE", IPHONE)
    val oldBundle = mac.bundle("old/iPhone 17 Pro", 1206, 2622)
    mac.list(IPHONE to oldBundle)
    val reader = mac.reader()
    assertEquals(Pixels(1206, 2622), reader.read("PHONE"))

    oldBundle.deleteRecursively()
    mac.list(IPHONE to mac.bundle("new/iPhone 17 Pro", 1206, 2622))
    assertEquals(Pixels(1206, 2622), reader.read("PHONE"))
  }

  @Test
  fun `a simulator with no device plist, or an unlisted device type, reads as unknown`() = withMac { mac ->
    mac.simulator("UNLISTED", "com.apple.CoreSimulator.SimDeviceType.Apple-Vision-Pro")
    mac.list(IPHONE to mac.bundle("iPhone 17 Pro", 1206, 2622))
    val reader = mac.reader()
    assertNull(reader.read("NO-SUCH-SIMULATOR"))
    assertNull(reader.read("UNLISTED"))
  }

  private companion object {
    const val IPHONE = "com.apple.CoreSimulator.SimDeviceType.iPhone-17-Pro"
    const val IPAD = "com.apple.CoreSimulator.SimDeviceType.iPad-A16"
  }
}
