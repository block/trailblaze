package xyz.block.trailblaze.device

/**
 * Pure parsing of Android `dumpsys` output for the foreground app, factored out of
 * `AdbCommandUtil` so the string handling can be unit-tested without a device.
 *
 * We read the **resumed (top) activity** rather than the window-focus signal because the resumed
 * activity reflects the foreground app reliably even during a cold start's splash/transition —
 * on some images (observed on AOSP Contacts) `mCurrentFocus` stays `null` for the entire life of a
 * freshly-launched screen while `mResumedActivity` names the app immediately.
 */
object AndroidForegroundParser {

  /**
   * Extracts the `<pkg>/<activity>` component of EVERY resumed (top) activity in the output of
   * `dumpsys activity activities`, in dump order, deduplicated. Split-screen / multi-display
   * dumps carry one resumed line per visible task — a caller asking "is app X in the foreground"
   * must match against all of them, or the target app can be on screen yet never matched.
   */
  fun parseResumedActivityComponents(dumpsysOutput: String): List<String> = dumpsysOutput.lines()
    .filter { it.contains("ResumedActivity") && it.contains("/") }
    .mapNotNull { line ->
      val braceContent = line.substringAfter("{", "").substringBefore("}", "")
      // Within the braces the tokens are `<hash> u0 <pkg>/<activity> t<taskId>`; the component is
      // the single token carrying a "/" (the trailing `t<taskId>` has none).
      braceContent.trim().split(" ").firstOrNull { it.contains("/") }
    }
    .distinct()

  /**
   * Single-answer view of [parseResumedActivityComponents] — the first resumed component, e.g.
   * `  mResumedActivity: ActivityRecord{<hash> u0 com.example/.MainActivity t42}` →
   * `com.example/.MainActivity`. Returns null when no resumed-activity line carrying a component
   * is present.
   */
  fun parseResumedActivityComponent(dumpsysOutput: String): String? =
    parseResumedActivityComponents(dumpsysOutput).firstOrNull()

  /** Package name of a `<pkg>/<activity>` component (e.g. "com.example"), or null. */
  fun packageFromComponent(component: String?): String? =
    component?.substringBefore("/")?.takeIf { it.isNotBlank() }

  /** Short activity class of a `<pkg>/<activity>` component (e.g. "MainActivity"), or null. */
  fun shortActivityFromComponent(component: String?): String? {
    if (component == null) return null
    val activityClass = component.substringAfter("/").trimStart('.')
    return activityClass.substringAfterLast('.').takeIf { it.isNotBlank() && it != "null" }
  }
}
