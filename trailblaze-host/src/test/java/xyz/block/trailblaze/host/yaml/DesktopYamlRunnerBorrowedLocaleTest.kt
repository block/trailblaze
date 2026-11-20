package xyz.block.trailblaze.host.yaml

import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The locale a borrowed run would need its lender to set ([DesktopYamlRunner.declaredLocale]). A
 * borrowed device is refused a trail that declares one, rather than run in the wrong language.
 */
class DesktopYamlRunnerBorrowedLocaleTest {

  private val androidPhone = listOf(TrailblazeDeviceClassifier("android"), TrailblazeDeviceClassifier("phone"))

  @Test
  fun `a locale the trail declares for the device is found`() {
    val yaml = "config:\n  id: myapp/login\n  devices:\n    android-phone:\n      locale: es\ntrail:\n  - step: Open the app\n"
    assertEquals("es", DesktopYamlRunner.declaredLocale(yaml, androidPhone))
  }

  @Test
  fun `a locale declared only for another device is not this device's`() {
    val yaml = "config:\n  id: myapp/login\n  devices:\n    android-phone: {}\n    ios-iphone:\n      locale: es\ntrail:\n  - step: Open the app\n"
    assertNull(DesktopYamlRunner.declaredLocale(yaml, androidPhone))
  }

  @Test
  fun `a trail that declares no locale, or doesn't read, needs none`() {
    assertNull(DesktopYamlRunner.declaredLocale("config:\n  id: myapp/login\ntrail:\n  - step: Open the app\n", androidPhone))
    assertNull(DesktopYamlRunner.declaredLocale("config: [unclosed", androidPhone))
  }
}
