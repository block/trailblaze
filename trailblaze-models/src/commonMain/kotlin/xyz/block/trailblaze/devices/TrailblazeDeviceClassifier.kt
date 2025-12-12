package xyz.block.trailblaze.devices

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * Typesafe wrapper for classifiers
 */
@Serializable
@JvmInline
value class TrailblazeDeviceClassifier(val classifier: String) {
  override fun toString(): String = classifier
}
