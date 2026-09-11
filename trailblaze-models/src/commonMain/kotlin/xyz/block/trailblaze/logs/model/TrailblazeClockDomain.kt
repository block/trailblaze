package xyz.block.trailblaze.logs.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Which clock stamped a [xyz.block.trailblaze.logs.client.TrailblazeLog.timestamp].
 *
 * A session's logs are stamped by more than one clock: the host runner stamps
 * ObjectiveStart/Complete while an on-device executor stamps its tool logs, and a device's wall
 * clock drifts from the host's by whole seconds. Readers that compare timestamps across logs
 * (recording window assignment, screenshot-to-log association) need to know which domain a stamp
 * came from to normalize it onto the host timeline.
 *
 * Serial names match the trace path's precedent (`SessionTraceFile.CLOCK_FIELD`): `"device"` /
 * `"host"`, with ABSENT meaning host — so existing persisted logs decode unchanged and stay
 * host-domain by default.
 *
 * [wireName] restates the serial name as a runtime value so the trace path's constants
 * (`TrailblazeLogServerClient.CLOCK_PARAM` values, `SessionTraceFile`'s clock field) can derive
 * from this enum instead of repeating the literals — one vocabulary, enforced by reference. It
 * must stay identical to the `@SerialName` beside it.
 */
@Serializable
enum class TrailblazeClockDomain(val wireName: String) {
  @SerialName("host")
  HOST("host"),

  @SerialName("device")
  DEVICE("device"),
}
