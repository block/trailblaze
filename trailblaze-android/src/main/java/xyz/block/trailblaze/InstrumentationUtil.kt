package xyz.block.trailblaze

import android.app.Instrumentation
import android.app.UiAutomation
import android.util.Log
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
import xyz.block.trailblaze.AdbCommandUtil.execShellCommand

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

  private fun setText(uiDevice: UiDevice, text: String) {
    for (element in text) {
      Log.d("Maestro", element.code.toString())
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
    }
  }

  fun inputTextByTyping(text: String) {
    withUiDevice {
      setText(this, text)
    }
  }
}
