package xyz.block.trailblaze.devices

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

/** Typesafe wrapper for classifiers */
@Serializable
@JvmInline
value class TrailblazeDeviceClassifier(val classifier: String) {
  override fun toString(): String = classifier
}
