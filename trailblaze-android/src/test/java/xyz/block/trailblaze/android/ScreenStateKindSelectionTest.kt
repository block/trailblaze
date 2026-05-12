package xyz.block.trailblaze.android

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies [AndroidTrailblazeRule]'s screen-state selection across the four-cell
 * (driver, migration-mode) matrix plus the service-binding gate.
 *
 * Regression cover for the OSS bug where the rule unconditionally built a Maestro-shape
 * screen state under the accessibility driver, causing every LLM-generated selector to
 * `NoMatch` at dispatch against the live accessibility tree.
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
  fun `accessibility driver but service not yet bound falls back to UiAutomator`() {
    assertEquals(
      ScreenStateKind.UIAUTOMATOR,
      chooseScreenStateKind(
        isAccessibilityDriver = true,
        isMigrationMode = false,
        isAccessibilityServiceRunning = false,
      ),
    )
  }

  @Test
  fun `migration mode keeps UiAutomator primary even under accessibility driver`() {
    assertEquals(
      ScreenStateKind.UIAUTOMATOR,
      chooseScreenStateKind(
        isAccessibilityDriver = true,
        isMigrationMode = true,
        isAccessibilityServiceRunning = true,
      ),
    )
  }

  @Test
  fun `non-accessibility driver always picks UiAutomator`() {
    for (migration in listOf(false, true)) {
      for (serviceRunning in listOf(false, true)) {
        assertEquals(
          ScreenStateKind.UIAUTOMATOR,
          chooseScreenStateKind(
            isAccessibilityDriver = false,
            isMigrationMode = migration,
            isAccessibilityServiceRunning = serviceRunning,
          ),
          "migration=$migration serviceRunning=$serviceRunning",
        )
      }
    }
  }
}
