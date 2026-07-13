package xyz.block.trailblaze.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for [AndroidForegroundParser].
 *
 * The resumed-activity parse is the fix for the slow cold-launch tax (block/trailblaze#210): the
 * launch wait polled the window-focus signal, which on some images stays `null` for the whole life
 * of a freshly-launched screen, so every cold launch burned the full 30s timeout. Reading the
 * resumed activity instead names the app immediately. These cases pin the parse against the real
 * `dumpsys activity activities` shapes so a refactor can't silently reintroduce the stall.
 */
class AndroidForegroundParserTest {

  @Test
  fun `parses the resumed activity a cold-launched app reports`() {
    // Real line observed on AOSP while Contacts was mid-cold-start (mCurrentFocus was null).
    val output = "  ResumedActivity: ActivityRecord{7f0c6e1 u0 com.android.contacts/.activities.PeopleActivity t522}"
    val component = AndroidForegroundParser.parseResumedActivityComponent(output)
    assertEquals("com.android.contacts/.activities.PeopleActivity", component)
    assertEquals("com.android.contacts", AndroidForegroundParser.packageFromComponent(component))
    assertEquals("PeopleActivity", AndroidForegroundParser.shortActivityFromComponent(component))
  }

  @Test
  fun `parses the older mResumedActivity field name`() {
    val output = "    mResumedActivity: ActivityRecord{abc123 u0 com.example.app/com.example.app.HomeActivity t42}"
    val component = AndroidForegroundParser.parseResumedActivityComponent(output)
    assertEquals("com.example.app/com.example.app.HomeActivity", component)
    assertEquals("com.example.app", AndroidForegroundParser.packageFromComponent(component))
    assertEquals("HomeActivity", AndroidForegroundParser.shortActivityFromComponent(component))
  }

  @Test
  fun `handles the equals-separated topResumedActivity and picks the first resumed line`() {
    // Faithful to real `dumpsys activity activities`: `topResumedActivity=` (equals) appears near
    // the top, the `ResumedActivity:` (colon) summary lower down — both name the same app.
    val output = buildString {
      appendLine("    topResumedActivity=ActivityRecord{4f88661 u0 com.android.settings/.Settings t526}")
      appendLine("  Stack #0:")
      appendLine("  ResumedActivity: ActivityRecord{4f88661 u0 com.android.settings/.Settings t526}")
    }
    assertEquals(
      "com.android.settings",
      AndroidForegroundParser.packageFromComponent(
        AndroidForegroundParser.parseResumedActivityComponent(output),
      ),
    )
  }

  @Test
  fun `split-screen returns every resumed app and the singular view picks the first`() {
    // Two visible tasks (split-screen / multi-display) emit one resumed line each. The plural
    // view must name BOTH apps — `waitUntilAppInForeground` matches any of them, so a first-only
    // parse would burn its full timeout with the target app on screen.
    val output = buildString {
      appendLine("    topResumedActivity=ActivityRecord{4f88661 u0 com.android.settings/.Settings t526}")
      appendLine("  ResumedActivity: ActivityRecord{9a11b22 u0 com.example.other/.MainActivity t527}")
    }
    assertEquals(
      listOf("com.android.settings/.Settings", "com.example.other/.MainActivity"),
      AndroidForegroundParser.parseResumedActivityComponents(output),
    )
    assertEquals(
      "com.android.settings/.Settings",
      AndroidForegroundParser.parseResumedActivityComponent(output),
    )
  }

  @Test
  fun `duplicate resumed lines for the same component dedupe`() {
    // `topResumedActivity=` near the top and the `ResumedActivity:` summary lower down name the
    // same record — the plural view reports the component once.
    val output = buildString {
      appendLine("    topResumedActivity=ActivityRecord{4f88661 u0 com.android.settings/.Settings t526}")
      appendLine("  ResumedActivity: ActivityRecord{4f88661 u0 com.android.settings/.Settings t526}")
    }
    assertEquals(
      listOf("com.android.settings/.Settings"),
      AndroidForegroundParser.parseResumedActivityComponents(output),
    )
  }

  @Test
  fun `returns null when no resumed-activity line is present`() {
    assertNull(AndroidForegroundParser.parseResumedActivityComponent("  mCurrentFocus=null"))
    assertNull(AndroidForegroundParser.parseResumedActivityComponent(""))
  }

  @Test
  fun `returns null when the resumed-activity line carries no component`() {
    // Device between activities: the field is present but names no `pkg/activity`.
    assertNull(AndroidForegroundParser.parseResumedActivityComponent("  mResumedActivity: null"))
  }

  @Test
  fun `component helpers tolerate null and blanks`() {
    assertNull(AndroidForegroundParser.packageFromComponent(null))
    assertNull(AndroidForegroundParser.shortActivityFromComponent(null))
    assertNull(AndroidForegroundParser.packageFromComponent("/.MainActivity"))
  }
}
