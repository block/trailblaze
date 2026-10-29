package xyz.block.trailblaze.android

import xyz.block.trailblaze.exception.TrailblazeException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Verifies [AndroidTrailblazeRule]'s screen-state selection across the (driver, migration-mode,
 * service-bound) matrix.
 *
 * Regression cover for the OSS bug where the rule built a Maestro-shape screen state under the
 * accessibility driver, causing every LLM-generated selector to `NoMatch` at dispatch against the
 * live accessibility tree — and for the silent UiAutomator fallback that reproduced it whenever
 * the service was not bound.
 */
class ScreenStateKindSelectionTest {

  @Test
  fun `accessibility driver + service running picks accessibility screen state`() {
    assertEquals(
      ScreenStateKind.ACCESSIBILITY,
      chooseScreenStateKind(
        isAccessibilityDriver = true,
        isMigrationMode = false,
        isAccessibilityServiceRunning = true,
      ),
    )
  }

  @Test
  fun `accessibility driver with the service not running fails by name`() {
    val error = assertFailsWith<TrailblazeException> {
      chooseScreenStateKind(
        isAccessibilityDriver = true,
        isMigrationMode = false,
        isAccessibilityServiceRunning = false,
      )
    }
    assertContains(error.message.orEmpty(), "TrailblazeAccessibilityService is not running")
  }

  @Test
  fun `non-accessibility agent outside migration mode fails by name`() {
    for (serviceRunning in listOf(false, true)) {
      val error = assertFailsWith<TrailblazeException>("serviceRunning=$serviceRunning") {
        chooseScreenStateKind(
          isAccessibilityDriver = false,
          isMigrationMode = false,
          isAccessibilityServiceRunning = serviceRunning,
        )
      }
      assertContains(error.message.orEmpty(), "non-accessibility agent")
    }
  }

  @Test
  fun `migration mode keeps UiAutomator primary for every agent and service state`() {
    for (accessibility in listOf(false, true)) {
      for (serviceRunning in listOf(false, true)) {
        assertEquals(
          ScreenStateKind.UIAUTOMATOR,
          chooseScreenStateKind(
            isAccessibilityDriver = accessibility,
            isMigrationMode = true,
            isAccessibilityServiceRunning = serviceRunning,
          ),
          "accessibility=$accessibility serviceRunning=$serviceRunning",
        )
      }
    }
  }
}
