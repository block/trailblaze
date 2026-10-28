package xyz.block.trailblaze.devices

/**
 * A CI/catalog device key (`android-phone-es`) read as device classifiers. Shared by the host CLI's
 * `--device-classifier` and the on-device runner's `trailblaze.deviceClassifier` instrumentation
 * arg, so both accept the same keys and refuse the same mismatches.
 */
object DeviceClassifierKey {

  private val CLASSIFIER_KEY_PATTERN = Regex("[a-z0-9_]+(?:-[a-z0-9_]+)*")
  private val ROOT_CLASSIFIERS = setOf("android", "desktop", "ios", "web")
  private val FORM_FACTOR_CLASSIFIERS = setOf("browser", "compose", "ipad", "iphone", "phone", "tablet")
  private val COMPATIBLE_FORM_FACTORS = mapOf(
    "android" to setOf("phone", "tablet"),
    "desktop" to setOf("compose"),
    "ios" to setOf("ipad", "iphone"),
    "web" to setOf("browser"),
  )

  /** Parses a CI/catalog device key into the classifiers used by unified trail recordings. */
  fun parse(key: String): List<TrailblazeDeviceClassifier> {
    val normalized = key.trim()
    require(CLASSIFIER_KEY_PATTERN.matches(normalized)) {
      "device classifier must be a lowercase, hyphen-separated key (got '$key')"
    }
    return normalized.split("-").map(::TrailblazeDeviceClassifier)
  }

  /**
   * Applies a caller-selected classifier only when it refines the connected device's physical
   * classifier. For example, `ios-iphone-es` may refine `[ios, iphone]`, while `ios-ipad-es`
   * cannot. This keeps locale/variant suffixes useful without allowing a catalog label to disguise
   * the wrong platform or form factor.
   */
  fun refine(
    detected: List<TrailblazeDeviceClassifier>,
    requested: List<TrailblazeDeviceClassifier>,
  ): List<TrailblazeDeviceClassifier> {
    if (requested.isEmpty()) return detected
    val requestedValues = requested.map { it.classifier }
    val detectedValues = detected.map { it.classifier }
    val roots = requestedValues.filter { it in ROOT_CLASSIFIERS }
    require(roots.size <= 1) {
      "requested device classifier '${requestedValues.joinToString("-")}' contains " +
        "contradictory platform classifiers"
    }
    val formFactors = requestedValues.filter { it in FORM_FACTOR_CLASSIFIERS }
    require(formFactors.size <= 1) {
      "requested device classifier '${requestedValues.joinToString("-")}' contains " +
        "contradictory form-factor classifiers"
    }
    roots.singleOrNull()?.let { root ->
      val compatible = COMPATIBLE_FORM_FACTORS[root]
      require(formFactors.isEmpty() || compatible?.contains(formFactors.single()) == true) {
        "requested device classifier '${requestedValues.joinToString("-")}' contains a " +
          "form factor that is incompatible with '$root'"
      }
    }
    if (detected.isEmpty()) return requested
    require(requested.take(detected.size) == detected) {
      "requested device classifier '${requestedValues.joinToString("-")}' does not " +
        "match connected device '${detectedValues.joinToString("-")}'"
    }
    return requested
  }
}
