package xyz.block.trailblaze.yaml.unified

import com.charleskorn.kaml.YamlInput
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.util.Console

/**
 * The device model: one entry of a trail's `config.devices:` map. This is the shared device
 * shape from the multi-device trails design — every device a trail declares, whether a
 * standalone single-device entry (the map KEY is the device classifier), a named multi-device
 * CONFIGURATION (an entry carrying an inner [devices] map), or a named device inside such a
 * configuration, is this same class. Add new per-device capabilities HERE so they work at
 * every level.
 *
 * ```yaml
 * config:
 *   devices:
 *     android-tablet:
 *       driver: ANDROID_ONDEVICE_ACCESSIBILITY   # single-device entry: the KEY is the classifier
 *     ios: {}                                    # declare the classifier, pin nothing
 *     web:                                       # the same, written as an empty value
 *     pos-pair:                                  # multi-device configuration (inner devices:)
 *       description: Dual-display pair
 *       devices:                                 # named devices; FIRST entry = start device
 *         seller:
 *           classifier: lab-a
 *           description: merchant-facing display
 *         buyer:
 *           classifier: lab-b
 *     # android: ANDROID_ONDEVICE_ACCESSIBILITY  # deprecated bare-string form, decode-only
 * ```
 *
 * Which fields are legal depends on the level; [TrailblazeDeviceDefinitionMapSerializer]
 * fails loud at decode on a contradiction:
 * - A **single-device entry**'s key IS its classifier — a [classifier] field that contradicts
 *   the key is a validation error.
 * - A **configuration** entry ([devices] non-null) declares no device identity of its own:
 *   [driver], [classifier], and [target] on it are validation errors ([description] is fine).
 *   Its inner map must be non-empty, and configurations don't nest.
 * - A **named device** inside a configuration says what it is via [classifier]; steps address
 *   it by its NAME (`switchDevice`), never by classifier or serial.
 */
@Serializable
data class TrailblazeDeviceDefinition(
  /**
   * The driver to run this device on. Optional — omit it to declare the classifier without
   * pinning a driver (the driver then resolves at run time: `--driver` flag > app setting >
   * device default).
   */
  @Serializable(with = TrailblazeDriverTypeLenientSerializer::class)
  val driver: TrailblazeDriverType? = null,
  /**
   * The device classifier this entry describes (e.g. `lab-a`). Only meaningful on a named
   * device inside a configuration, where the map key is a NAME rather than a classifier. On a
   * top-level single-device entry the key is the classifier, so this field may only restate it.
   */
  val classifier: String? = null,
  /**
   * Per-device app target — the id of the target this device runs, overriding the trail's
   * session-level `config.target:` for this device only. Omit it to inherit that session target.
   *
   * Only meaningful on a named device inside a configuration (a paired-display trail whose two
   * devices run different apps declares the override on the member that differs). Everything a
   * target carries follows the device it is declared on: the app ids `ctx.target.resolveAppId()`
   * resolves against THAT device's installed packages, the target's custom tools, and its
   * scripted-tool runtime. A target id that this installation doesn't carry is a hard error at
   * session start, never a silent fallback to the session target.
   */
  val target: String? = null,
  /** Human-readable note about this device shown to authors and the agent. */
  val description: String? = null,
  /**
   * Present only on a multi-device CONFIGURATION entry: the cast of named devices the session
   * binds, in declaration order — the FIRST entry is where the trail starts. Keys are the
   * names `switchDevice` addresses; values describe each device (typically via [classifier]).
   * Insertion order is preserved by the YAML decoder (kaml decodes maps to LinkedHashMap).
   */
  val devices: Map<String, TrailblazeDeviceDefinition>? = null,
) {
  /** True when this entry is a multi-device configuration (it carries an inner [devices] map). */
  val isConfiguration: Boolean get() = devices != null
}

/**
 * Decodes a driver name via [TrailblazeDriverType.fromString] (case-insensitive, matching how
 * every other driver-string surface parses) instead of the default exact-name enum decode, and
 * throws [UnknownDriverException] on a name that matches nothing — typed so a fail-loud caller
 * can tell a typo'd pin apart from a generally unparseable trail. Encodes the exact enum name.
 */
object TrailblazeDriverTypeLenientSerializer : KSerializer<TrailblazeDriverType> {
  override val descriptor: SerialDescriptor =
    PrimitiveSerialDescriptor("xyz.block.trailblaze.devices.TrailblazeDriverType", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: TrailblazeDriverType) = encoder.encodeString(value.name)

  override fun deserialize(decoder: Decoder): TrailblazeDriverType {
    val driverName = decoder.decodeString()
    return TrailblazeDriverType.fromString(driverName)
      ?: throw UnknownDriverException(
        driverName = driverName,
        message = "unknown driver '$driverName' — " +
          "valid driver types: ${TrailblazeDriverType.entries.joinToString { it.name }}.",
      )
  }
}

/**
 * A `config.devices:` entry (or a serialized recording's config) names a driver that is not a
 * [TrailblazeDriverType]. Typed so callers that must convert this to their own fail-loud surface
 * (`DesktopYamlRunner.trailPinnedDriverResolution` → `CliRunDriverResolution.Unrecognized`) can
 * pick it out of a wrapped parse-failure cause chain — kaml wraps serializer exceptions, and a
 * generic catch would silently degrade a typo'd pin to "no pin", running the default driver.
 *
 * When thrown by [TrailblazeDeviceDefinitionMapSerializer], the exception describes the WHOLE
 * `devices:` map, not just the first bad entry: [decodedDevices] holds every entry that decoded
 * cleanly and [unknownDrivers] every entry with an unknown driver name. A per-device caller needs
 * both to make the right call — a valid pin for the running device must survive another
 * platform's typo, and which entry wins is a closest-match decision over ALL keys.
 */
class UnknownDriverException(
  val driverName: String,
  message: String,
  /**
   * The `config.devices:` map key the bad name sits under, when known. `null` when the failure
   * surfaced without entry context (e.g. a bare driver string outside a devices map).
   */
  val classifier: String? = null,
  /** Every `devices:` entry that decoded cleanly (classifier → definition). */
  val decodedDevices: Map<String, TrailblazeDeviceDefinition> = emptyMap(),
  /** Every bad `devices:` entry (classifier → the unknown driver name it declares). */
  val unknownDrivers: Map<String, String> =
    if (classifier != null) mapOf(classifier to driverName) else emptyMap(),
  cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

/**
 * Map serializer for a trail's `config.devices:` block. Needed because, in YAML, an entry's value
 * may be a bare driver-name scalar (`android: ANDROID_ONDEVICE_ACCESSIBILITY`, the DEPRECATED
 * legacy form) OR a map (`android: { driver: ANDROID_ONDEVICE_ACCESSIBILITY }`, the canonical
 * form), and kaml refuses to hand a scalar node to a class-kind value serializer — so the
 * scalar-or-object union must be handled at the map level (same pattern as
 * [xyz.block.trailblaze.yaml.TrailArgMapSerializer]).
 *
 * DECODE accepts both forms; ENCODE always emits the object form. The bare-string branch is
 * decode-only compatibility for trails written before the object form became canonical — it is
 * slated for removal once existing trails are migrated; never emit it and do not add new
 * capabilities to it.
 *
 * The union has a third member: a map value carrying an inner `devices:` key is a named
 * multi-device CONFIGURATION (a cast of named devices), not a single-device entry. It decodes
 * through the same object branch — [TrailblazeDeviceDefinition.devices] is the discriminator —
 * and [validateEntry] enforces the level rules (a configuration carries only its cast +
 * description; configurations don't nest).
 */
object TrailblazeDeviceDefinitionMapSerializer : KSerializer<Map<String, TrailblazeDeviceDefinition>> {
  private val delegate = MapSerializer(String.serializer(), TrailblazeDeviceDefinition.serializer())
  override val descriptor: SerialDescriptor = delegate.descriptor

  override fun serialize(encoder: Encoder, value: Map<String, TrailblazeDeviceDefinition>) {
    // Always the object form — the scalar legacy form is decode-only.
    encoder.encodeSerializableValue(delegate, value)
  }

  override fun deserialize(decoder: Decoder): Map<String, TrailblazeDeviceDefinition> = when (decoder) {
    is YamlInput -> deserializeFromYaml(decoder)
    is JsonDecoder -> deserializeFromJson(decoder)
    else -> decoder.decodeSerializableValue(delegate)
  }

  private fun deserializeFromYaml(decoder: YamlInput): Map<String, TrailblazeDeviceDefinition> {
    val node = decoder.node
    require(node is YamlMap) {
      "config.devices must be a map of device classifier to device definition, got ${node::class.simpleName}."
    }
    val decoded = LinkedHashMap<String, TrailblazeDeviceDefinition>()
    val unknownDrivers = LinkedHashMap<String, String>()
    val legacyStringKeys = mutableListOf<String>()
    for ((keyNode, valueNode) in node.entries) {
      val classifier = keyNode.content
      // Decode EVERY entry before failing on any of them: a per-device caller judging an
      // unknown-driver failure needs the full picture (see [UnknownDriverException]) — aborting
      // at the first bad entry would hide a later valid pin from the device it belongs to.
      try {
        decoded[classifier] = decodeYamlEntry(decoder, classifier, valueNode)
      } catch (e: Exception) {
        val unknownDriver = unknownDriverIn(e) ?: throw e
        unknownDrivers[classifier] = unknownDriver.driverName
      }
      if (valueNode is YamlScalar) legacyStringKeys += classifier
    }
    if (legacyStringKeys.isNotEmpty()) {
      Console.log(
        "[deprecation] config.devices uses the bare-string driver form for $legacyStringKeys — " +
          "write the object form instead (e.g. `driver: <name>` nested under the classifier). " +
          "The string form is decode-only and will be removed.",
      )
    }
    throwIfUnknownDrivers(unknownDrivers, decoded)
    decoded.forEach { (key, definition) -> validateEntry(key, definition) }
    return decoded
  }

  /**
   * Level rules from the multi-device design (see [TrailblazeDeviceDefinition]'s kdoc): a
   * configuration entry carries only its cast + description, a single-device entry's key IS its
   * classifier, and configurations don't nest. Fail loud at decode so a contradictory trail
   * never runs against the wrong device.
   */
  private fun validateEntry(key: String, definition: TrailblazeDeviceDefinition) {
    val inner = definition.devices
    if (inner != null) {
      require(definition.driver == null && definition.classifier == null && definition.target == null) {
        "config.devices entry '$key' is a multi-device configuration (it has an inner `devices:` " +
          "map) and cannot also declare `driver:`/`classifier:`/`target:` — those belong on its " +
          "named devices."
      }
      require(inner.isNotEmpty()) {
        "config.devices configuration '$key' declares an empty `devices:` map — " +
          "name at least one device (the first entry is where the trail starts)."
      }
      inner.forEach { (name, member) ->
        require(member.devices == null) {
          "config.devices configuration '$key' nests another configuration under '$name' — " +
            "configurations don't nest."
        }
      }
    } else {
      require(definition.classifier == null || definition.classifier == key) {
        "config.devices entry '$key' declares classifier '${definition.classifier}', but a " +
          "single-device entry's map key IS its classifier — remove the `classifier:` field " +
          "or rename the key."
      }
    }
  }

  private fun decodeYamlEntry(
    decoder: YamlInput,
    classifier: String,
    valueNode: YamlNode,
  ): TrailblazeDeviceDefinition = when (valueNode) {
    // An entry with no value (`web:`) declares the classifier and pins nothing — the same
    // driverless definition `{}` produces, since that is the shape a writer reaches for first.
    is YamlNull -> TrailblazeDeviceDefinition()
    // DEPRECATED legacy form: the scalar IS the driver name. Decode-only.
    is YamlScalar -> TrailblazeDeviceDefinition(driver = parseDriver(classifier, valueNode.content))
    // Canonical object form — including a multi-device configuration, which decodes through the
    // same serializer ([TrailblazeDeviceDefinition.devices] non-null is the discriminator).
    is YamlMap -> withEntryContext(classifier) {
      decoder.yaml.decodeFromYamlNode(TrailblazeDeviceDefinition.serializer(), valueNode)
    }
    else ->
      error(
        "config.devices entry '$classifier' must be a device definition map " +
          "(e.g. `driver: ANDROID_ONDEVICE_ACCESSIBILITY` nested under the classifier), " +
          "got ${valueNode::class.simpleName}.",
      )
  }

  /**
   * JSON mirror of the YAML branch so a serialized config decodes identically from either
   * transport: a string value is the deprecated legacy form, an object is the canonical form.
   */
  private fun deserializeFromJson(decoder: JsonDecoder): Map<String, TrailblazeDeviceDefinition> {
    val element = decoder.decodeJsonElement()
    require(element is JsonObject) {
      "config.devices (JSON) must be an object of device classifier to device definition, " +
        "got ${element::class.simpleName}."
    }
    val decoded = LinkedHashMap<String, TrailblazeDeviceDefinition>()
    val unknownDrivers = LinkedHashMap<String, String>()
    for ((classifier, value) in element) {
      try {
        decoded[classifier] = when (value) {
          // Before the JsonPrimitive branch: JsonNull IS a JsonPrimitive, and a null value is a
          // driverless entry, not a driver named "null".
          JsonNull -> TrailblazeDeviceDefinition()
          is JsonPrimitive -> TrailblazeDeviceDefinition(driver = parseDriver(classifier, value.content))
          else -> withEntryContext(classifier) {
            decoder.json.decodeFromJsonElement(TrailblazeDeviceDefinition.serializer(), value)
          }
        }
      } catch (e: Exception) {
        val unknownDriver = unknownDriverIn(e) ?: throw e
        unknownDrivers[classifier] = unknownDriver.driverName
      }
    }
    throwIfUnknownDrivers(unknownDrivers, decoded)
    decoded.forEach { (key, definition) -> validateEntry(key, definition) }
    return decoded
  }

  /** Finds an [UnknownDriverException] in [e]'s cause chain (kaml/kotlinx may wrap it). */
  private fun unknownDriverIn(e: Throwable): UnknownDriverException? =
    generateSequence(e) { it.cause }.filterIsInstance<UnknownDriverException>().firstOrNull()

  private fun throwIfUnknownDrivers(
    unknownDrivers: Map<String, String>,
    decoded: Map<String, TrailblazeDeviceDefinition>,
  ) {
    if (unknownDrivers.isEmpty()) return
    val (classifier, driverName) = unknownDrivers.entries.first()
    val badEntries = unknownDrivers.entries.joinToString { (key, value) -> "'$key' names unknown driver '$value'" }
    throw UnknownDriverException(
      driverName = driverName,
      message = "config.devices ${if (unknownDrivers.size > 1) "entries" else "entry"} $badEntries — " +
        "valid driver types: ${TrailblazeDriverType.entries.joinToString { it.name }}.",
      classifier = classifier,
      decodedDevices = decoded,
      unknownDrivers = unknownDrivers,
    )
  }

  /**
   * Runs [decode] and, if it fails because of an [UnknownDriverException] that has no entry
   * context yet (thrown by [TrailblazeDriverTypeLenientSerializer], which cannot see the map key),
   * rethrows it stamped with [classifier] so per-device callers can judge reachability. Any other
   * failure propagates untouched.
   */
  private inline fun withEntryContext(classifier: String, decode: () -> TrailblazeDeviceDefinition): TrailblazeDeviceDefinition =
    try {
      decode()
    } catch (e: Exception) {
      val unknownDriver = unknownDriverIn(e)
        ?.takeIf { it.classifier == null }
        ?: throw e
      throw UnknownDriverException(
        driverName = unknownDriver.driverName,
        message = "config.devices entry '$classifier': ${unknownDriver.message}",
        classifier = classifier,
        cause = e,
      )
    }

  /** Fail loud on an unknown driver name — a typo'd pin must never silently run the default driver. */
  private fun parseDriver(classifier: String, driverName: String): TrailblazeDriverType =
    TrailblazeDriverType.fromString(driverName)
      ?: throw UnknownDriverException(
        driverName = driverName,
        message = "config.devices entry '$classifier' names unknown driver '$driverName' — " +
          "valid driver types: ${TrailblazeDriverType.entries.joinToString { it.name }}.",
        classifier = classifier,
      )
}
