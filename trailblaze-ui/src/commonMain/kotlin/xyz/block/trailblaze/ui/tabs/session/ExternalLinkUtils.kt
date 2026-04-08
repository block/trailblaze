package xyz.block.trailblaze.ui.tabs.session

import xyz.block.trailblaze.devices.TrailblazeDeviceInfo

/**
 * A clickable link derived from device metadata.
 *
 * @property label Human-readable display text for the link.
 * @property url Fully-qualified URL to open.
 */
data class ExternalLink(val label: String, val url: String)

/**
 * Extracts external links from device metadata using the `*_url` key convention.
 *
 * For each metadata entry whose key ends with `_url` and whose value is non-blank,
 * a link is produced. The label is resolved from a sibling `*_label` key if present,
 * otherwise auto-generated from the key prefix (e.g. `revyl_viewer_url` becomes
 * "Open Revyl viewer").
 *
 * @param deviceInfo Device info containing the metadata map. Returns empty list if null.
 * @return Ordered list of external links.
 */
fun extractExternalLinks(deviceInfo: TrailblazeDeviceInfo?): List<ExternalLink> {
  val metadata = deviceInfo?.metadata.orEmpty()
  return metadata.entries
    .filter { it.key.endsWith("_url") && it.value.isNotBlank() }
    .filter { (_, url) ->
      val scheme = url.substringBefore("://", "").lowercase()
      scheme == "http" || scheme == "https"
    }
    .map { (key, url) ->
      val prefix = key.removeSuffix("_url")
      val autoLabel = "Open ${prefix.replace('_', ' ').replaceFirstChar { c -> c.uppercase() }}"
      val label = metadata["${prefix}_label"]?.takeIf { it.isNotBlank() } ?: autoLabel
      ExternalLink(label, url)
    }
}
