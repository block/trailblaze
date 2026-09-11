package xyz.block.trailblaze.ui.tabs.session

/** Which measurement produced a [DeviceLogClockOffset], and therefore how much to trust it. */
internal enum class DeviceLogClockBasis {
  /**
   * The session's own device→host offset, measured from the device's log uploads against the
   * host's receipt of them. Pure clock skew.
   */
  INGESTION_ANCHORED,

  /**
   * This stream's first parseable line minus the session's first log. An approximation: it also
   * absorbs however long the device took to emit that first line. Used for sessions that carry no
   * ingestion anchors — a CI run whose logs were pulled off the device's own disk.
   */
  FIRST_LINE_ESTIMATE,

  /** Nothing to measure against. The panel shows lines but can't follow the timeline. */
  UNSYNCED,
}

/** [hostToDeviceMs] is added to a host timestamp to get the device timestamp the lines carry. */
internal data class DeviceLogClockOffset(val hostToDeviceMs: Long, val basis: DeviceLogClockBasis)

/**
 * How far a device log stream's own stamps sit from the host timeline the session viewer scrubs on.
 *
 * Preference order is by measurement quality, not availability: [sessionDeviceClockOffsetMs] is
 * real skew and wins whenever the session produced one (negated — it converts device→host, and
 * this converts the other way); the first-line estimate is a fallback for unanchored sessions; and
 * an unmeasurable stream reports [DeviceLogClockBasis.UNSYNCED] rather than silently passing 0,
 * so the caller can say why scrolling and highlighting stopped tracking.
 */
internal fun deviceLogClockOffset(
  sessionDeviceClockOffsetMs: Long?,
  firstDeviceLineMs: Long?,
  sessionStartMs: Long,
): DeviceLogClockOffset = when {
  sessionDeviceClockOffsetMs != null ->
    DeviceLogClockOffset(-sessionDeviceClockOffsetMs, DeviceLogClockBasis.INGESTION_ANCHORED)
  firstDeviceLineMs != null && sessionStartMs > 0 ->
    DeviceLogClockOffset(firstDeviceLineMs - sessionStartMs, DeviceLogClockBasis.FIRST_LINE_ESTIMATE)
  else -> DeviceLogClockOffset(0L, DeviceLogClockBasis.UNSYNCED)
}
