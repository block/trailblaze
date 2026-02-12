package xyz.block.trailblaze.device

/**
 * Represents a broadcast intent with action, component, and extras.
 * This is a multiplatform abstraction over Android's Intent class.
 */
class BroadcastIntent(
  val action: String,
  val componentPackage: String,
  val componentClass: String,
  val extras: Map<String, Any> = emptyMap()
) {
  companion object

  /**
   * Builder for constructing BroadcastIntent instances.
   */
  class Builder(
    private val action: String,
    private val componentPackage: String,
    private val componentClass: String
  ) {
    private val extras = mutableMapOf<String, Any>()

    fun putExtra(key: String, value: String): Builder = apply {
      extras[key] = value
    }

    fun putExtra(key: String, value: Boolean): Builder = apply {
      extras[key] = value
    }

    fun putExtra(key: String, value: Int): Builder = apply {
      extras[key] = value
    }

    fun putExtra(key: String, value: Long): Builder = apply {
      extras[key] = value
    }

    fun build(): BroadcastIntent {
      return BroadcastIntent(
        action = action,
        componentPackage = componentPackage,
        componentClass = componentClass,
        extras = extras.toMap()
      )
    }
  }
}
