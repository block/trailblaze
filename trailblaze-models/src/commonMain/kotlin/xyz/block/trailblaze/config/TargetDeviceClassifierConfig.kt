package xyz.block.trailblaze.config

import kotlinx.serialization.Serializable

/**
 * A target's device classifier, resolved from the trailmap's `target.device_classifier:` name.
 *
 * The classifier computes the whole device key from the framework's key and the device's maker and
 * model, so recordings are found and saved under it. It may be declared by the target's own
 * trailmap or by one in its dependency closure, which is how one classifier serves several targets.
 */
@Serializable
data class TargetDeviceClassifierConfig(
  /** The `export const <name> = trailblaze.deviceClassifier(...)` binding the target named. */
  val name: String,
  /** The `.ts` file that declares it: a file on disk, or a classpath resource for a JAR trailmap. */
  val script: String,
)
