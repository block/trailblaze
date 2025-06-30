package xyz.block.trailblaze.toolcalls

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class TrailblazeToolSetClass(
  val description: String,
)
