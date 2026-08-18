package xyz.block.trailblaze.toolcalls

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class TrailblazeToolProperty(
  val description: String,
)
