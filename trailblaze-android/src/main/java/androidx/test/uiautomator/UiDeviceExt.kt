package androidx.test.uiautomator

/**
 * https://github.com/mobile-dev-inc/maestro/blob/88d3e9606d62ffe76b49c14ac12d52521b937fab/maestro-android/src/androidTest/java/androidx/test/uiautomator/UiDeviceExt.kt#L1
 */
object UiDeviceExt {

  /**
   * Fix for a UiDevice.click() method that discards taps that happen outside of the screen bounds.
   * The issue with the original method is that it was computing screen bounds incorrectly.
   */
  fun UiDevice.clickExt(x: Int, y: Int) {
    interactionController.clickNoSync(
      x,
      y,
    )
  }
}
