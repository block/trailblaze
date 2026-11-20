package xyz.block.trailblaze.host.install

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.host.ios.MobileDeviceUtils.InstallResult
import xyz.block.trailblaze.host.lease.RemoteInstallReply
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceInstallEndpointTest {

  private val ownPhone = TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID)
  private val borrowedPhone = TrailblazeDeviceId("127.0.0.1:51663@build-box:31900", TrailblazeDevicePlatform.ANDROID)
  private val simulator = TrailblazeDeviceId("8F2C-SIM", TrailblazeDevicePlatform.IOS)
  private val devices = listOf(ownPhone, borrowedPhone, simulator)

  private val dir = createTempDirectory("install-endpoint-test").toFile()
  private val apk = File(dir, "app.apk").apply { writeText("apk") }

  @AfterTest fun cleanUp() { dir.deleteRecursively() }

  private val json = Json { ignoreUnknownKeys = true }
  private val installedHere = mutableListOf<TrailblazeDeviceId>()
  private val sentToLender = mutableListOf<TrailblazeDeviceId>()

  private fun installApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
    application {
      routing {
        DeviceInstallEndpoint.register(
          routing = this,
          findDevice = { DeviceInstallEndpoint.pick(devices, it) },
          installer = DeviceAppInstaller(
            isBorrowed = { it == borrowedPhone },
            installOnLender = { device, _ -> sentToLender += device; RemoteInstallReply(true, "installed on build-box") },
            installHere = { device, _ -> installedHere += device; InstallResult(true, "installed here") },
            inUse = { _, install -> Result.success(install()) },
          ),
        )
      }
    }
    block()
  }

  private suspend fun ApplicationTestBuilder.install(device: String, path: String = apk.path, browser: Boolean = false) =
    client.post(DeviceInstallEndpoint.PATH) {
      if (browser) header(HttpHeaders.Origin, "https://example.com")
      setBody("""{"device":"$device","path":"$path"}""")
    }

  private suspend fun ApplicationTestBuilder.reply(device: String) =
    json.decodeFromString(DeviceInstallResponse.serializer(), install(device).bodyAsText())

  @Test
  fun `a borrowed device's install goes to its lender, an own device's installs here`() = installApp {
    assertEquals(DeviceInstallResponse(true, "installed on build-box"), reply("android/127.0.0.1:51663@build-box:31900"))
    assertEquals(DeviceInstallResponse(true, "installed here"), reply("android/emulator-5554"))
    assertEquals(listOf(borrowedPhone), sentToLender)
    assertEquals(listOf(ownPhone), installedHere)
  }

  @Test
  fun `an unknown device or missing file installs nothing`() = installApp {
    assertEquals(HttpStatusCode.NotFound, install("android/emulator-9999").status)
    assertEquals(HttpStatusCode.BadRequest, install("android/emulator-5554", path = File(dir, "gone.apk").path).status)
    assertTrue(installedHere.isEmpty() && sentToLender.isEmpty())
  }

  @Test
  fun `a request from a web page is refused`() = installApp {
    assertEquals(HttpStatusCode.Forbidden, install("android/emulator-5554", browser = true).status)
    assertTrue(installedHere.isEmpty())
  }

  @Test
  fun `a device is named by its full id, its instance id, or its platform when it is the only one`() {
    assertEquals(ownPhone, DeviceInstallEndpoint.pick(devices, "android/emulator-5554"))
    assertEquals(borrowedPhone, DeviceInstallEndpoint.pick(devices, "127.0.0.1:51663@build-box:31900"))
    assertEquals(simulator, DeviceInstallEndpoint.pick(devices, "ios"))
    assertNull(DeviceInstallEndpoint.pick(devices, "android"), "two Android devices: the platform alone is ambiguous")
  }

  @Test
  fun `one device listed once per driver is still the only one of its platform`() {
    assertEquals(ownPhone, DeviceInstallEndpoint.pick(listOf(ownPhone, ownPhone, ownPhone, simulator), "android"))
  }
}
