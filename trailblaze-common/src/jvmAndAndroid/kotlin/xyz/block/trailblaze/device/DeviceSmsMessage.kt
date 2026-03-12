package xyz.block.trailblaze.device

/**
 * SMS payload used for inserting inbox messages on both Android and JVM/adb backends.
 */
data class DeviceSmsMessage(
  val fromNumber: String,
  val body: String,
  val dateMillis: Long = System.currentTimeMillis(),
  val dateSentMillis: Long = dateMillis,
  val isRead: Boolean = false,
)
