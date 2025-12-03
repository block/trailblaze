package xyz.block.trailblaze.android

import android.os.Build
import android.util.DisplayMetrics
import xyz.block.trailblaze.InstrumentationUtil.withInstrumentation
import xyz.block.trailblaze.devices.TrailblazeAndroidDeviceCategory
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifiersProvider
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDeviceOrientation
import xyz.block.trailblaze.devices.TrailblazeDriverType
import java.util.Locale
import java.util.TimeZone

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

  fun collectCurrentDeviceInfo(
    trailblazeDriverType: TrailblazeDriverType,
    trailblazeDeviceClassifiersProvider: TrailblazeDeviceClassifiersProvider?,
  ): TrailblazeDeviceInfo {
    val displayMetrics = getDisplayMetrics()
    return TrailblazeDeviceInfo(
      trailblazeDriverType = trailblazeDriverType,
      locale = getCurrentLocale().toLanguageTag(),
      orientation = getDeviceOrientation(),
      widthPixels = displayMetrics.widthPixels,
      heightPixels = displayMetrics.heightPixels,
      classifiers = trailblazeDeviceClassifiersProvider?.getDeviceClassifiers() ?: getConsumerAndroidClassifiers(),
      metadata = getDeviceMetadata(),
    )
  }

  fun getConsumerAndroidClassifiers(): List<String> = buildList {
    add(getDeviceCategoryClassifier())
  }

  private fun getDeviceCategoryClassifier(): String = getConsumerAndroidDeviceCategory().classifier
}
