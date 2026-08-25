package xyz.block.trailblaze.host.yaml

/**
 * Which devices of a multi-device session get a network-capture bridge.
 *
 * The default is every bound device, and that default is deliberately eager: a device whose stream
 * is simply missing from the report looks the same as a device that had nothing to report.
 *
 * [CAPTURE_DEVICES_ENV_VAR] narrows it, because some device pairs run a capture-capable app on only
 * one of their displays and on the host path capture is load-bearing evidence — arming a device
 * whose app never dials in fails the session after the discovery timeout. Which display runs what
 * is device knowledge, so it is configured per lane rather than inferred from the trail.
 */
internal object MultiDeviceCaptureSelection {

  const val CAPTURE_DEVICES_ENV_VAR: String = "TRAILBLAZE_NETWORK_CAPTURE_DEVICES"

  /**
   * @param armed the devices to start capture on, in the order they were bound.
   * @param unknownNames names the allowlist asked for that this session bound no device for. A
   *   typo here would otherwise disarm capture for the whole session without saying so.
   */
  data class Selection<T>(val armed: List<T>, val unknownNames: List<String>)

  /**
   * Device names from a raw [CAPTURE_DEVICES_ENV_VAR] value. Blank entries are dropped rather than
   * kept as a name no device can match, so a trailing comma or a padded value still means what it
   * looks like; an empty result means "no allowlist", i.e. every device.
   */
  fun parseDeviceNames(raw: String?): Set<String> =
    raw.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

  /**
   * @param candidates the devices capture could attach to — already filtered to the platforms this
   *   capture path supports, so an allowlist entry naming an unsupported device still reports as
   *   unknown rather than silently looking armed.
   */
  fun <T> select(
    candidates: List<T>,
    allowedNames: Set<String>,
    nameOf: (T) -> String,
  ): Selection<T> {
    if (allowedNames.isEmpty()) return Selection(armed = candidates, unknownNames = emptyList())
    val candidateNames = candidates.map(nameOf).toSet()
    return Selection(
      armed = candidates.filter { nameOf(it) in allowedNames },
      unknownNames = allowedNames.filterNot { it in candidateNames }.sorted(),
    )
  }
}
