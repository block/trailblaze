package xyz.block.trailblaze.android.devices

import xyz.block.trailblaze.android.AndroidTrailblazeDeviceInfoUtil
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifiersProvider
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform

object TrailblazeAndroidOnDeviceClassifier : TrailblazeDeviceClassifiersProvider {

  private val trailblazeDeviceClassifiers: List<TrailblazeDeviceClassifier> by lazy {
    buildList {
      add(TrailblazeDevicePlatform.ANDROID.asTrailblazeDeviceClassifier())
      addAll(AndroidTrailblazeDeviceInfoUtil.getConsumerAndroidClassifiers())
    }
  }

  override fun getDeviceClassifiers(): List<TrailblazeDeviceClassifier> = trailblazeDeviceClassifiers

  fun getDeviceClassifiersProvider() = { trailblazeDeviceClassifiers }
}
