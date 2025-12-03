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
import xyz.block.trailblaze.InstrumentationUtil.inputTextByTyping

/**
 * Utilities when running with Instrumentation and UiAutomation.
 */
object InstrumentationUtil {

  private val instrumentation: Instrumentation get() = InstrumentationRegistry.getInstrumentation()

  private val uiDevice: UiDevice get() = UiDevice.getInstance(instrumentation)

  private val uiAutomation: UiAutomation get() = instrumentation.uiAutomation

  fun <T> withInstrumentation(work: Instrumentation.() -> T): T = with(instrumentation) {
    work(instrumentation)
  }

  fun <T> withUiAutomation(work: UiAutomation.() -> T): T = synchronized(uiAutomation) {
    work(uiAutomation)
  }

  fun <T> withUiDevice(work: UiDevice.() -> T): T = synchronized(uiDevice) {
    work(uiDevice)
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
    val intCode: Int = when (code) {
      KeyCode.ENTER -> 66
      KeyCode.BACKSPACE -> 67
      KeyCode.BACK -> 4
      KeyCode.VOLUME_UP -> 24
      KeyCode.VOLUME_DOWN -> 25
      KeyCode.HOME -> 3
      KeyCode.LOCK -> 276
      KeyCode.REMOTE_UP -> 19
      KeyCode.REMOTE_DOWN -> 20
      KeyCode.REMOTE_LEFT -> 21
      KeyCode.REMOTE_RIGHT -> 22
      KeyCode.REMOTE_CENTER -> 23
      KeyCode.REMOTE_PLAY_PAUSE -> 85
      KeyCode.REMOTE_STOP -> 86
      KeyCode.REMOTE_NEXT -> 87
      KeyCode.REMOTE_PREVIOUS -> 88
      KeyCode.REMOTE_REWIND -> 89
      KeyCode.REMOTE_FAST_FORWARD -> 90
      KeyCode.POWER -> 26
      KeyCode.ESCAPE -> 111
      KeyCode.TAB -> 62
      KeyCode.REMOTE_SYSTEM_NAVIGATION_UP -> 280
      KeyCode.REMOTE_SYSTEM_NAVIGATION_DOWN -> 281
      KeyCode.REMOTE_BUTTON_A -> 96
      KeyCode.REMOTE_BUTTON_B -> 97
      KeyCode.REMOTE_MENU -> 82
      KeyCode.TV_INPUT -> 178
      KeyCode.TV_INPUT_HDMI_1 -> 243
      KeyCode.TV_INPUT_HDMI_2 -> 244
      KeyCode.TV_INPUT_HDMI_3 -> 245
    }

    execShellCommand("input keyevent $intCode")
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
    println("swipe $elementPoint, $direction, $durationMs")
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
