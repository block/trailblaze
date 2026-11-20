package xyz.block.trailblaze.yaml.internal

import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier

/** Framework implementation sharing; excluded from the supported SDK API. */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class InternalTrailblazeApi

/** The same closed locale projection for runtime identity and planning recording eligibility. */
@InternalTrailblazeApi
object DeclaredLocaleClassifiers {
  fun project(
    classifiers: List<TrailblazeDeviceClassifier>,
    declaredLocale: String?,
  ): List<TrailblazeDeviceClassifier> {
    if (declaredLocale == null) return classifiers
    val locale = declaredLocale.lowercase()
    // Other established locale paths retain their existing classifiers and setup behavior.
    if (locale !in setOf("es", "es-us", "es-mx")) return classifiers
    val base = classifiers.take(2).joinToString("-") { it.classifier }
    if (base !in setOf("android-phone", "ios-iphone", "web-browser")) return classifiers
    val tail = classifiers.drop(2).joinToString("-") { it.classifier }
    require(tail in setOf("", "es", "es-us", "es-mx")) {
      "Cannot project declared locale '$declaredLocale' onto device '${classifiers.joinToString("-") { it.classifier }}': unrecognized trailing variant '$tail'."
    }
    require(tail.isEmpty() || tail == "es" || tail == locale) {
      "Declared locale '$declaredLocale' conflicts with regionally pinned device '$base-$tail'."
    }
    return classifiers.take(2) + locale.split("-").map(::TrailblazeDeviceClassifier)
  }
}
