package xyz.block.trailblaze.android

import android.app.UiModeManager
import android.content.res.Configuration
import android.os.Build
import android.util.DisplayMetrics
import xyz.block.trailblaze.InstrumentationUtil.withInstrumentation
import xyz.block.trailblaze.devices.TrailblazeAndroidDeviceCategory
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceOrientation
import java.util.Locale
import java.util.TimeZone

/**
 * For use on-device only
 */
object AndroidTrailblazeDeviceInfoUtil {

  fun getCurrentLocale(): Locale = withInstrumentation { context.resources.configuration.getLocales().get(0) }

  fun getSmallestScreenWidthDp(): Int = withInstrumentation { context.resources.configuration.smallestScreenWidthDp }

  fun getDisplayMetrics(): DisplayMetrics = withInstrumentation { context.resources.displayMetrics }

  /**
   * https://developer.android.com/develop/ui/compose/layouts/adaptive/use-window-size-classes
   * width < 600dp	99.96% of phones in portrait
   */
  fun getConsumerAndroidDeviceCategory(): TrailblazeAndroidDeviceCategory = if (getSmallestScreenWidthDp() >= 600) {
    TrailblazeAndroidDeviceCategory.TABLET
  } else {
    TrailblazeAndroidDeviceCategory.PHONE
  }

  fun getDeviceMetadata(): Map<String, String> = mutableMapOf<String, String>().apply {
    this["manufacturer"] = Build.MANUFACTURER
    this["model"] = Build.MODEL
    this["release"] = Build.VERSION.RELEASE.toString()
    this["codename"] = Build.VERSION.CODENAME.toString()
    this["base_os"] = Build.VERSION.BASE_OS.toString()
    this["sdk_int"] = Build.VERSION.SDK_INT.toString()
    this["timezone"] = TimeZone.getDefault().id
    this["smallestScreenWidthDp"] = getSmallestScreenWidthDp().toString()
    this["densityDpi"] = getDisplayMetrics().densityDpi.toString()
    this["architecture"] = System.getProperty("os.arch")!!
  }

  fun getDeviceOrientation() = run {
    val displayMetrics = getDisplayMetrics()
    val heightGreaterThanWidth = displayMetrics.heightPixels > displayMetrics.widthPixels
    when (getConsumerAndroidDeviceCategory()) {
      TrailblazeAndroidDeviceCategory.PHONE -> if (heightGreaterThanWidth) TrailblazeDeviceOrientation.PORTRAIT else TrailblazeDeviceOrientation.LANDSCAPE
      TrailblazeAndroidDeviceCategory.TABLET -> if (!heightGreaterThanWidth) TrailblazeDeviceOrientation.PORTRAIT else TrailblazeDeviceOrientation.LANDSCAPE
    }
  }

  /**
   * Same runtime check play-android itself uses (`Context.isTv()` in `ContextExtensions.kt`)
   * to distinguish a TV from a landscape tablet — Android TV isn't a separate device category
   * in [TrailblazeAndroidDeviceCategory], it's detected orthogonally via [UiModeManager].
   */
  fun isTelevision(): Boolean = withInstrumentation {
    val uiModeManager = context.getSystemService(UiModeManager::class.java)
    uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
  }

  fun getConsumerAndroidClassifiers(): List<TrailblazeDeviceClassifier> = buildList {
    // Prepended so it becomes the headline term in the LLM-facing device description
    // (see TrailblazeKoogLlmClientHelper.buildDeviceDescription, which reads classifiers.first()).
    if (isTelevision()) add(TrailblazeDeviceClassifier("tv"))
    add(getDeviceCategoryClassifier())
  }

  private fun getDeviceCategoryClassifier(): TrailblazeDeviceClassifier =
    getConsumerAndroidDeviceCategory().asTrailblazeDeviceClassifier()
}
