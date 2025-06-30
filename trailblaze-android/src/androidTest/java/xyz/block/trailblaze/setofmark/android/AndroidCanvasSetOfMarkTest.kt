package xyz.block.trailblaze.setofmark.android

import android.graphics.Bitmap
import android.os.Environment
import kotlinx.datetime.Clock
import maestro.DeviceInfo
import maestro.Platform
import org.junit.Test
import xyz.block.trailblaze.android.uiautomator.AndroidOnDeviceUiAutomatorScreenState
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyTreeNodeUtils
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * See https://github.com/takahirom/arbigent/blob/11b7887248ee131ab91a222f0f6d4ef80328853c/arbigent-core/src/main/java/io/github/takahirom/arbigent/ArbigentCanvas.kt#L32
 */
class AndroidCanvasSetOfMarkTest {
  private val publicDownloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).also {
    if (!it.exists()) {
      it.mkdirs()
    }
  }

  private val testAppFilesDir = File(publicDownloadsDir, "set-of-mark").also {
    if (it.exists()) {
      it.deleteRecursively()
    }
    it.mkdirs()
  }

  /**
   * Just runs the code, not validation.
   */
  @Test
  fun captureSetOfMarkScreenshot() {
    val screenState = AndroidOnDeviceUiAutomatorScreenState()
    val originalScreenshotBitmap = AndroidOnDeviceUiAutomatorScreenState.createBlankBitmapForCurrentWindowSize()

    val elementList = ViewHierarchyTreeNodeUtils.from(
      screenState.viewHierarchy,
      DeviceInfo(
        platform = Platform.ANDROID,
        widthPixels = originalScreenshotBitmap.width,
        heightPixels = originalScreenshotBitmap.height,
        widthGrid = originalScreenshotBitmap.width,
        heightGrid = originalScreenshotBitmap.height,
      ),
    )

    AndroidCanvasSetOfMark.drawSetOfMarkOnBitmap(
      originalScreenshotBitmap = originalScreenshotBitmap,
      elements = elementList,
      includeLabel = true,
    )

    val screenshotFile = File(
      testAppFilesDir,
      "${Clock.System.now().toEpochMilliseconds()}.png",
    ).also { it.createNewFile() }
    println("screenshotFile: ${screenshotFile.canonicalPath}")
    screenshotFile.also { file ->
      println("Writing to ${file.canonicalPath}")
      val byteArrayOutputStream = ByteArrayOutputStream()
      originalScreenshotBitmap.compress(Bitmap.CompressFormat.PNG, 100, byteArrayOutputStream)
      file.writeBytes(byteArrayOutputStream.toByteArray())
    }
  }
}
