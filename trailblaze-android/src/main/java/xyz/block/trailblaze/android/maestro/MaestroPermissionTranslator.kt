package xyz.block.trailblaze.android.maestro

/**
 * Translates Maestro's short permission names (as used in trail YAML's
 * `launchApp.permissions:` map) to fully-qualified Android permission strings
 * accepted by `pm grant`. Mirrors `maestro.drivers.AndroidDriver.translatePermissionName`
 * so trails behave the same on Maestro's host driver and on Trailblaze's on-device driver.
 *
 * Without this, `pm grant <appId> notifications` is a no-op (silently rejected as an
 * unknown permission), the POST_NOTIFICATIONS dialog appears mid-trail, and selectors
 * targeting elements behind the dialog fail.
 */
object MaestroPermissionTranslator {
  fun translate(name: String): List<String> = when (name) {
    "location" -> listOf(
      "android.permission.ACCESS_FINE_LOCATION",
      "android.permission.ACCESS_COARSE_LOCATION",
    )
    "camera" -> listOf("android.permission.CAMERA")
    "contacts" -> listOf(
      "android.permission.READ_CONTACTS",
      "android.permission.WRITE_CONTACTS",
    )
    "phone" -> listOf(
      "android.permission.CALL_PHONE",
      "android.permission.ANSWER_PHONE_CALLS",
    )
    "microphone" -> listOf("android.permission.RECORD_AUDIO")
    "bluetooth" -> listOf(
      "android.permission.BLUETOOTH_CONNECT",
      "android.permission.BLUETOOTH_SCAN",
    )
    "storage" -> listOf(
      "android.permission.WRITE_EXTERNAL_STORAGE",
      "android.permission.READ_EXTERNAL_STORAGE",
    )
    "notifications" -> listOf("android.permission.POST_NOTIFICATIONS")
    "medialibrary" -> listOf(
      "android.permission.WRITE_EXTERNAL_STORAGE",
      "android.permission.READ_EXTERNAL_STORAGE",
      "android.permission.READ_MEDIA_AUDIO",
      "android.permission.READ_MEDIA_IMAGES",
      "android.permission.READ_MEDIA_VIDEO",
    )
    "calendar" -> listOf(
      "android.permission.WRITE_CALENDAR",
      "android.permission.READ_CALENDAR",
    )
    "sms" -> listOf(
      "android.permission.READ_SMS",
      "android.permission.RECEIVE_SMS",
      "android.permission.SEND_SMS",
    )
    // Already a fully-qualified permission (or "all", which neither caller currently expands).
    else -> listOf(name.replace("[^A-Za-z0-9._]+".toRegex(), ""))
  }
}
