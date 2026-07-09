package xyz.block.trailblaze.trailrunner

import kotlin.test.assertEquals
import org.junit.Test
import xyz.block.trailblaze.device.InstalledApp

/**
 * Coverage for [toInstalledAppPickerDtos] — the pure shaping step behind the Create Target form's
 * "Browse installed apps" picker (`GetInstalledAppsRequest`). The per-platform device probes are
 * thin delegations to `AndroidHostAdbUtils` / `IosHostSimctlUtils` and aren't re-tested here.
 */
class InstalledAppsPickerTest {

  @Test
  fun `drops system apps and maps the picker fields`() {
    val apps = listOf(
      InstalledApp(appId = "com.example.mine", isSystemApp = false, label = "Mine", version = "1.2"),
      InstalledApp(appId = "com.android.settings", isSystemApp = true, label = "Settings"),
    )
    assertEquals(
      listOf(InstalledAppDto(appId = "com.example.mine", label = "Mine", version = "1.2")),
      toInstalledAppPickerDtos(apps),
    )
  }

  @Test
  fun `sorts by label falling back to app id, case-insensitively`() {
    val apps = listOf(
      InstalledApp(appId = "com.zzz.unlabeled", isSystemApp = false),
      InstalledApp(appId = "com.example.b", isSystemApp = false, label = "beta"),
      InstalledApp(appId = "com.example.a", isSystemApp = false, label = "Alpha"),
    )
    assertEquals(
      listOf("com.example.a", "com.example.b", "com.zzz.unlabeled"),
      toInstalledAppPickerDtos(apps).map { it.appId },
    )
  }

  @Test
  fun `includeSystemApps keeps preinstalled apps like the browser or calculator`() {
    val apps = listOf(
      InstalledApp(appId = "com.example.mine", isSystemApp = false, label = "Mine"),
      InstalledApp(appId = "com.android.chrome", isSystemApp = true, label = "Chrome"),
      InstalledApp(appId = "com.android.calculator2", isSystemApp = true, label = "Calculator"),
    )
    assertEquals(
      listOf("com.android.calculator2", "com.android.chrome", "com.example.mine"),
      toInstalledAppPickerDtos(apps, includeSystemApps = true).map { it.appId },
    )
  }

  @Test
  fun `includeSystemApps defaults to false, matching the existing declutter behavior`() {
    val apps = listOf(InstalledApp(appId = "com.android.chrome", isSystemApp = true, label = "Chrome"))
    assertEquals(emptyList(), toInstalledAppPickerDtos(apps))
  }

  @Test
  fun `breaks a label tie by app id, so order is deterministic regardless of probe order`() {
    val apps = listOf(
      InstalledApp(appId = "com.example.app.dev", isSystemApp = false, label = "MyApp"),
      InstalledApp(appId = "com.example.app", isSystemApp = false, label = "MyApp"),
    )
    assertEquals(
      listOf("com.example.app", "com.example.app.dev"),
      toInstalledAppPickerDtos(apps).map { it.appId },
    )
    // Same result with the input order reversed — the tiebreaker, not insertion order, decides.
    assertEquals(
      listOf("com.example.app", "com.example.app.dev"),
      toInstalledAppPickerDtos(apps.reversed()).map { it.appId },
    )
  }
}
