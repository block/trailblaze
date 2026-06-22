package xyz.block.trailblaze

import android.app.Instrumentation
import android.app.UiAutomation
import android.view.KeyEvent.KEYCODE_1
import android.view.KeyEvent.KEYCODE_4
import android.view.KeyEvent.KEYCODE_5
import android.view.KeyEvent.KEYCODE_6
import android.view.KeyEvent.KEYCODE_7
import android.view.KeyEvent.KEYCODE_APOSTROPHE
import android.view.KeyEvent.KEYCODE_AT
import android.view.KeyEvent.KEYCODE_BACKSLASH
import android.view.KeyEvent.KEYCODE_COMMA
import android.view.KeyEvent.KEYCODE_EQUALS
import android.view.KeyEvent.KEYCODE_GRAVE
import android.view.KeyEvent.KEYCODE_LEFT_BRACKET
import android.view.KeyEvent.KEYCODE_MINUS
import android.view.KeyEvent.KEYCODE_NUMPAD_ADD
import android.view.KeyEvent.KEYCODE_NUMPAD_LEFT_PAREN
import android.view.KeyEvent.KEYCODE_NUMPAD_RIGHT_PAREN
import android.view.KeyEvent.KEYCODE_PERIOD
import android.view.KeyEvent.KEYCODE_POUND
import android.view.KeyEvent.KEYCODE_RIGHT_BRACKET
import android.view.KeyEvent.KEYCODE_SEMICOLON
import android.view.KeyEvent.KEYCODE_SLASH
import android.view.KeyEvent.KEYCODE_SPACE
import android.view.KeyEvent.KEYCODE_STAR
import android.view.KeyEvent.META_SHIFT_LEFT_ON
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import maestro.DeviceInfo
import maestro.KeyCode
import maestro.Point
import maestro.SwipeDirection
import xyz.block.trailblaze.AdbCommandUtil.directionalSwipe
import xyz.block.trailblaze.AdbCommandUtil.execShellCommand
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.UiAutomationHandleErrors

/**
 * Utilities when running with Instrumentation and UiAutomation.
 * Provides access to the Android Instrumentation, UiDevice, and UiAutomation
 * for testing and automation purposes.
 */
object InstrumentationUtil {

  private val instrumentation: Instrumentation get() = InstrumentationRegistry.getInstrumentation()

  private val uiDevice: UiDevice get() = UiDevice.getInstance(instrumentation)

  // Always request FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES so that every withUiAutomation
  // call — including the stale-handle recovery path — reconnects with flags=1 rather than the
  // default flags=0 that would suppress accessibility services and collapse the Compose semantic
  // tree in any app process (AccessibilityManager.isEnabled() goes false, Compose stops exposing
  // its tree to UiAutomator2). getUiAutomation(flags) returns the cached connection when flags
  // match, so there is no reconnect cost on the hot path.
  private val uiAutomation: UiAutomation
    get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

  // Stable lock for UiAutomation access. We deliberately do NOT synchronize on the `uiAutomation`
  // getter result: evaluating that getter calls `getUiAutomation(flags)`, which can itself throw
  // the stale-handle signature ("Cannot call disconnect() while connecting" / "Already connected")
  // when the platform tears down a half-connected handle to swap flags (see
  // [runWithStaleUiAutomationRecovery]). If that throw happened while evaluating the `synchronized`
  // lock argument it would escape the recovery block entirely, the cached handle would never be
  // cleared, and every subsequent call would keep hitting the same wedge. Locking on a dedicated
  // object keeps the only `getUiAutomation()` evaluation inside `work(uiAutomation)` — i.e. inside
  // the recovery block — so an acquisition-time stale handle is healed exactly like a use-time one.
  private val uiAutomationLock = Any()

  fun <T> withInstrumentation(work: Instrumentation.() -> T): T = with(instrumentation) {
    work(instrumentation)
  }

  fun <T> withUiAutomation(work: UiAutomation.() -> T): T = synchronized(uiAutomationLock) {
    runWithStaleUiAutomationRecovery { work(uiAutomation) }
  }

  fun <T> withUiDevice(work: UiDevice.() -> T): T = synchronized(uiDevice) {
    runWithStaleUiAutomationRecovery { work(uiDevice) }
  }

  /**
   * Forces the cached [UiAutomation] onto [UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES],
   * reconnecting when the active connection was established with different flags (e.g. the default
   * flags=0 that suppresses accessibility services).
   *
   * Acquiring the automation during that flags=0 -> flags=1 transition is the one moment the
   * platform can throw the transient "Cannot call disconnect() while connecting" race:
   * [Instrumentation.getUiAutomation] disconnects the cached handle to swap flags, but the handle
   * may still be mid-handshake from an earlier UiDevice / test-runner acquisition. Routing through
   * [runWithStaleUiAutomationRecovery] clears the half-connected handle and reconnects fresh, so
   * the race no longer aborts on-device startup — it had been surfacing as flaky "Run Eval Android
   * Trails" / CLI-smoke failures on `main`.
   *
   * Idempotent: once the connection already carries the flag, [Instrumentation.getUiAutomation]
   * returns the cached handle without reconnecting, so repeat calls are cheap no-ops.
   *
   * This is the resilient counterpart to a bare `getUiAutomation(flag)` call — callers that just
   * need the non-suppressing connection established (e.g. on-device accessibility-service setup)
   * should use this rather than touching [Instrumentation] directly.
   *
   * Threading: call during single-threaded setup (before the device action loop drives the
   * device), not concurrently with [withUiAutomation] / [withUiDevice]. It reconnects the shared
   * cached handle without holding `uiAutomationLock` (the dedicated monitor [withUiAutomation]
   * now uses), so it isn't serialized against those callers. Setup is the only caller today and
   * runs before any concurrent device work; a future concurrent caller should wrap it in
   * `synchronized(uiAutomationLock)`.
   */
  fun reconnectWithoutSuppressingAccessibility() {
    runWithStaleUiAutomationRecovery {
      instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    }
  }

  /**
   * Shared one-shot recovery path for callers that hit any platform-level
   * `IllegalStateException` indicating the cached [UiAutomation] handle on [Instrumentation] is
   * in a non-functional transitional state. The cache is keyed by flags; it can land in a
   * "constructed-but-never-connected" state (id=-1) when a previous instrumentation cycle was
   * torn down mid-action — a cancelled YAML run typically reproduces this. The cache survives
   * across requests on the same long-running on-device server, so the next request re-uses the
   * stale handle and explodes — either inside `UiDevice.waitForIdle` / `dumpWindowHierarchy`
   * ("UiAutomation not connected") or inside the platform's own teardown when [getUiAutomation]
   * tries to disconnect the half-connected handle to swap flags ("Cannot call disconnect() while
   * connecting", "Already connected"). All three are the same root cause; the recovery path is
   * the same.
   *
   * Recovery: on the first hit we clear the cached handle via reflection on [Instrumentation]'s
   * private `mUiAutomation` field, force a fresh `getUiAutomation(FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)`
   * (which constructs a new handle and calls `connect()`), and retry once. If the retry also fails — most likely the
   * underlying system service is genuinely gone — we re-throw with a wrapped message that points
   * the operator at the actual recovery (restart the on-device server / test APK).
   *
   * Reflection is the only path here because the platform doesn't expose a public way to drop
   * the cached handle. The field name is stable across all Android versions Trailblaze targets;
   * the catch makes us tolerant if it ever isn't.
   */
  private inline fun <T> runWithStaleUiAutomationRecovery(work: () -> T): T {
    return try {
      work()
    } catch (e: RuntimeException) {
      val msg = e.message.orEmpty()
      // The catch is widened to `RuntimeException` (not just `IllegalStateException`) because
      // `Error while disconnecting UiAutomation` is a bare `RuntimeException`, while the others are
      // `IllegalStateException`-flavored. All the recognized signatures share the same root cause (a
      // stale / half-connected cached handle) and the same cache-clear-and-retry recovery — see
      // [UiAutomationHandleErrors] for each one. A non-stale `RuntimeException` falls through via the
      // `throw e` below.
      if (!UiAutomationHandleErrors.isStaleHandleSignature(msg)) throw e
      Console.log(
        "[InstrumentationUtil] UiAutomation handle was stale (likely from a cancelled prior " +
          "session); attempting recovery. Original: $msg"
      )
      val cleared = clearInstrumentationUiAutomationCache()
      if (!cleared) {
        throw IllegalStateException(
          "UiAutomation is not connected and the cached handle could not be cleared via " +
            "reflection (both Instrumentation.disconnectUiAutomation() and the mUiAutomation " +
            "field are inaccessible — Android internal API may have changed). " +
            "Recover by restarting the Trailblaze on-device server (kill + re-launch the test " +
            "APK process). Original error: $msg",
          e,
        )
      }
      try {
        val result = work()
        // Log on success so an operator reading the log can correlate "stale connection
        // happened, but we recovered" — without this, only the failure case is visible and
        // the cause of one missed beat looks like a flake.
        Console.log(
          "[InstrumentationUtil] UiAutomation recovered after stale-handle reset; subsequent " +
            "operations should be healthy until the next instrumentation lifecycle event."
        )
        result
      } catch (retry: RuntimeException) {
        throw IllegalStateException(
          "UiAutomation reconnect retry also failed. The on-device server's instrumentation is " +
            "in a non-recoverable state — kill the test APK process (`adb shell am force-stop " +
            "<your test apk>`) and re-launch the Trailblaze on-device server to recover. " +
            "Original error: ${retry.message}",
          retry,
        )
      }
    }
  }

  /**
   * Best-effort reset of [Instrumentation]'s cached UiAutomation handle so the next
   * `getUiAutomation()` call constructs and connects a fresh one.
   *
   * Tries two paths in order, since either may break across Android SDK bumps:
   * 1. **`Instrumentation.disconnectUiAutomation()`** — package-private but historically the
   *    most-stable name for this purpose (it's the platform's own teardown API). When
   *    available, this is the right hook because it lets the platform run its connection
   *    bookkeeping rather than leaving a half-torn-down state.
   * 2. **Reflective null-out of the `mUiAutomation` field** — fallback if the method above
   *    isn't accessible. We also call the existing handle's package-private `disconnect()` if
   *    we can, then null the field. The field name has been `mUiAutomation` across all
   *    Android versions Trailblaze targets today; the outer try/catch absorbs a future rename.
   *
   * Returns true on either success path; false if both attempts threw.
   *
   * Public so the host->on-device drain RPC can call it from its handler in
   * `trailblaze-android-ondevice-mcp` (separate Gradle module — `internal` would not
   * cross the module boundary). Both call sites share the same private-API risk surface,
   * so the reflection logic stays centralized here.
   */
  fun clearInstrumentationUiAutomationCache(): Boolean {
    // Path 1: the platform's own disconnect.
    runCatching {
      val method = Instrumentation::class.java.getDeclaredMethod("disconnectUiAutomation")
      method.isAccessible = true
      method.invoke(instrumentation)
      Console.log("[InstrumentationUtil] cleared cached UiAutomation via Instrumentation.disconnectUiAutomation()")
      return true
    }
      .onFailure {
        Console.log(
          "[InstrumentationUtil] Instrumentation.disconnectUiAutomation() reflective call " +
            "failed (${it::class.java.simpleName}: ${it.message}); falling back to mUiAutomation field reset."
        )
      }

    // Path 2: reflective field null-out, with a best-effort UiAutomation.disconnect() first.
    return try {
      val field = Instrumentation::class.java.getDeclaredField("mUiAutomation")
      field.isAccessible = true
      (field.get(instrumentation) as? UiAutomation)?.let { existing ->
        runCatching {
          val disconnect = UiAutomation::class.java.getDeclaredMethod("disconnect")
          disconnect.isAccessible = true
          disconnect.invoke(existing)
        }
      }
      field.set(instrumentation, null)
      Console.log("[InstrumentationUtil] cleared cached UiAutomation via mUiAutomation field reset")
      true
    } catch (t: Throwable) {
      Console.log(
        "[InstrumentationUtil] Failed to clear Instrumentation.mUiAutomation reflectively: " +
          "${t::class.java.simpleName}: ${t.message}"
      )
      false
    }
  }

  fun inputTextFast(text: String) {
    execShellCommand("input text ${text.replace(" ", "%s")}")
  }

  private fun keyPressShiftedToEvents(uiDevice: UiDevice, keyCode: Int) {
    uiDevice.pressKeyCode(keyCode, META_SHIFT_LEFT_ON)
  }

  /**
   * This Matches Maestro's Implementation with a 300ms delay after key press.
   * https://github.com/mobile-dev-inc/Maestro/blob/0a38a9468cb769ecbc1edc76974fd2f8a8b0b64e/maestro-client/src/main/java/maestro/drivers/AndroidDriver.kt#L324
   *
   * Note: Use [inputTextByTyping] if you are doing normal typing and not pressing a specific key
   */
  fun pressKey(code: KeyCode) {
    AdbCommandUtil.pressKey(code)
    Thread.sleep(300)
  }

  /**
   * Types text by character.  Note: There is a 75ms delay between characters.  This matches Maestro's Implementation:
   * https://github.com/mobile-dev-inc/Maestro/blob/1fbb13d643e159d646a1d51ebbfad4533cb5b9f1/maestro-android/src/androidTest/java/dev/mobile/maestro/MaestroDriverService.kt#L312
   */
  fun inputTextByTyping(text: String) {
    for (element in text) {
      when (element.code) {
        in 48..57 -> {
          /** 0~9 **/
          uiDevice.pressKeyCode(element.code - 41)
        }

        in 65..90 -> {
          /** A~Z **/
          uiDevice.pressKeyCode(element.code - 36, 1)
        }

        in 97..122 -> {
          /** a~z **/
          uiDevice.pressKeyCode(element.code - 68)
        }

        ';'.code -> uiDevice.pressKeyCode(KEYCODE_SEMICOLON)
        '='.code -> uiDevice.pressKeyCode(KEYCODE_EQUALS)
        ','.code -> uiDevice.pressKeyCode(KEYCODE_COMMA)
        '-'.code -> uiDevice.pressKeyCode(KEYCODE_MINUS)
        '.'.code -> uiDevice.pressKeyCode(KEYCODE_PERIOD)
        '/'.code -> uiDevice.pressKeyCode(KEYCODE_SLASH)
        '`'.code -> uiDevice.pressKeyCode(KEYCODE_GRAVE)
        '\''.code -> uiDevice.pressKeyCode(KEYCODE_APOSTROPHE)
        '['.code -> uiDevice.pressKeyCode(KEYCODE_LEFT_BRACKET)
        ']'.code -> uiDevice.pressKeyCode(KEYCODE_RIGHT_BRACKET)
        '\\'.code -> uiDevice.pressKeyCode(KEYCODE_BACKSLASH)
        ' '.code -> uiDevice.pressKeyCode(KEYCODE_SPACE)
        '@'.code -> uiDevice.pressKeyCode(KEYCODE_AT)
        '#'.code -> uiDevice.pressKeyCode(KEYCODE_POUND)
        '*'.code -> uiDevice.pressKeyCode(KEYCODE_STAR)
        '('.code -> uiDevice.pressKeyCode(KEYCODE_NUMPAD_LEFT_PAREN)
        ')'.code -> uiDevice.pressKeyCode(KEYCODE_NUMPAD_RIGHT_PAREN)
        '+'.code -> uiDevice.pressKeyCode(KEYCODE_NUMPAD_ADD)
        '!'.code -> keyPressShiftedToEvents(uiDevice, KEYCODE_1)
        '$'.code -> keyPressShiftedToEvents(uiDevice, KEYCODE_4)
        '%'.code -> keyPressShiftedToEvents(uiDevice, KEYCODE_5)
        '^'.code -> keyPressShiftedToEvents(uiDevice, KEYCODE_6)
        '&'.code -> keyPressShiftedToEvents(uiDevice, KEYCODE_7)
        '"'.code -> keyPressShiftedToEvents(uiDevice, KEYCODE_APOSTROPHE)
        '{'.code -> keyPressShiftedToEvents(uiDevice, KEYCODE_LEFT_BRACKET)
        '}'.code -> keyPressShiftedToEvents(uiDevice, KEYCODE_RIGHT_BRACKET)
        ':'.code -> keyPressShiftedToEvents(uiDevice, KEYCODE_SEMICOLON)
        '|'.code -> keyPressShiftedToEvents(uiDevice, KEYCODE_BACKSLASH)
        '<'.code -> keyPressShiftedToEvents(uiDevice, KEYCODE_COMMA)
        '>'.code -> keyPressShiftedToEvents(uiDevice, KEYCODE_PERIOD)
        '?'.code -> keyPressShiftedToEvents(uiDevice, KEYCODE_SLASH)
        '~'.code -> keyPressShiftedToEvents(uiDevice, KEYCODE_GRAVE)
        '_'.code -> keyPressShiftedToEvents(uiDevice, KEYCODE_MINUS)
      }
      Thread.sleep(75)
    }
  }

  /**
   * Matches Maestro's Implementation
   * https://github.com/mobile-dev-inc/Maestro/blob/0a38a9468cb769ecbc1edc76974fd2f8a8b0b64e/maestro-client/src/main/java/maestro/drivers/AndroidDriver.kt#L429-L477
   */
  fun swipeDirectionAndDuration(deviceInfo: DeviceInfo, swipeDirection: SwipeDirection, durationMs: Long) {
    when (swipeDirection) {
      SwipeDirection.UP -> {
        val startX = (deviceInfo.widthGrid * 0.5f).toInt()
        val startY = (deviceInfo.heightGrid * 0.5f).toInt()
        val endX = (deviceInfo.widthGrid * 0.5f).toInt()
        val endY = (deviceInfo.heightGrid * 0.1f).toInt()
        directionalSwipe(
          durationMs,
          Point(startX, startY),
          Point(endX, endY),
        )
      }

      SwipeDirection.DOWN -> {
        val startX = (deviceInfo.widthGrid * 0.5f).toInt()
        val startY = (deviceInfo.heightGrid * 0.2f).toInt()
        val endX = (deviceInfo.widthGrid * 0.5f).toInt()
        val endY = (deviceInfo.heightGrid * 0.9f).toInt()
        directionalSwipe(
          durationMs,
          Point(startX, startY),
          Point(endX, endY),
        )
      }

      SwipeDirection.RIGHT -> {
        val startX = (deviceInfo.widthGrid * 0.1f).toInt()
        val startY = (deviceInfo.heightGrid * 0.5f).toInt()
        val endX = (deviceInfo.widthGrid * 0.9f).toInt()
        val endY = (deviceInfo.heightGrid * 0.5f).toInt()
        directionalSwipe(
          durationMs,
          Point(startX, startY),
          Point(endX, endY),
        )
      }

      SwipeDirection.LEFT -> {
        val startX = (deviceInfo.widthGrid * 0.9f).toInt()
        val startY = (deviceInfo.heightGrid * 0.5f).toInt()
        val endX = (deviceInfo.widthGrid * 0.1f).toInt()
        val endY = (deviceInfo.heightGrid * 0.5f).toInt()
        directionalSwipe(
          durationMs,
          Point(startX, startY),
          Point(endX, endY),
        )
      }
    }
  }

  /**
   * Matches Maestro's Implementation
   * https://github.com/mobile-dev-inc/Maestro/blob/0a38a9468cb769ecbc1edc76974fd2f8a8b0b64e/maestro-client/src/main/java/maestro/drivers/AndroidDriver.kt#L483-L504
   */
  fun swipe(deviceInfo: DeviceInfo, elementPoint: Point, direction: SwipeDirection, durationMs: Long) {
    Console.log("swipe $elementPoint, $direction, $durationMs")
    when (direction) {
      SwipeDirection.UP -> {
        val endY = (deviceInfo.heightGrid * 0.1f).toInt()
        directionalSwipe(durationMs, elementPoint, Point(elementPoint.x, endY))
      }

      SwipeDirection.DOWN -> {
        val endY = (deviceInfo.heightGrid * 0.9f).toInt()
        directionalSwipe(durationMs, elementPoint, Point(elementPoint.x, endY))
      }

      SwipeDirection.RIGHT -> {
        val endX = (deviceInfo.widthGrid * 0.9f).toInt()
        directionalSwipe(durationMs, elementPoint, Point(endX, elementPoint.y))
      }

      SwipeDirection.LEFT -> {
        val endX = (deviceInfo.widthGrid * 0.1f).toInt()
        directionalSwipe(durationMs, elementPoint, Point(endX, elementPoint.y))
      }
    }
  }

  /**
   * This matches Maestro's Implementation with the 400ms duration
   * https://github.com/mobile-dev-inc/Maestro/blob/0a38a9468cb769ecbc1edc76974fd2f8a8b0b64e/maestro-client/src/main/java/maestro/drivers/AndroidDriver.kt#L404
   */
  fun scrollVertical(deviceInfo: DeviceInfo) {
    swipeDirectionAndDuration(deviceInfo, SwipeDirection.UP, 400)
  }
}
