package xyz.block.trailblaze.yaml.internal

import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.yaml.TrailblazeYaml

/**
 * Prepares recording/session identity from the original declaration. Bare tools retain their
 * current identity. Parse tolerance belongs to the caller; locale conflicts are always strict.
 */
@InternalTrailblazeApi
fun TrailblazeYaml.recordingClassifiersFor(
  yaml: String,
  selection: List<TrailblazeDeviceClassifier>,
  current: List<TrailblazeDeviceClassifier>? = selection,
  tolerateMalformedYaml: Boolean = false,
  selectedDeviceConfiguration: String? = null,
): List<TrailblazeDeviceClassifier>? {
  if (isBareToolEnvelope(yaml)) return current
  val selected = if (tolerateMalformedYaml) {
    runCatching { extractTrailConfig(yaml, selection, selectedDeviceConfiguration) }.getOrNull()
  } else {
    extractTrailConfig(yaml, selection, selectedDeviceConfiguration)
  }
  return if (selectedDeviceConfiguration != null) selection
    else DeclaredLocaleClassifiers.project(selection, selected?.locale)
}
