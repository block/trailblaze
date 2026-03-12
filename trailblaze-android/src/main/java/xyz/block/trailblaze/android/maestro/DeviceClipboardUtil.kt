package xyz.block.trailblaze.android.maestro

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Utility for setting the device clipboard from on-device instrumentation code.
 *
 * Uses the same approach as [xyz.block.trailblaze.device.AndroidDeviceCommandExecutor.setClipboard]
 * but as a static utility so it can be called without a device ID (e.g. from Orchestra).
 */
object DeviceClipboardUtil {

  fun setClipboard(text: String) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("trailblaze", text)

    // ClipboardManager.setPrimaryClip must be called on the main thread
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      clipboard.setPrimaryClip(clip)
    }
  }
}
