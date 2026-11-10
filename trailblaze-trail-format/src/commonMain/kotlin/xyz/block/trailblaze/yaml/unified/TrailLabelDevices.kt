package xyz.block.trailblaze.yaml.unified

import xyz.block.trailblaze.devices.TrailblazeClassifierLineage
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier

/**
 * Which of a trail's devices a [TrailLabel] holds on, from its `when` and `except` lists.
 *
 * A trail's devices are the ones it declares: each single-device `config.devices` key, each member
 * of a multi-device configuration (named `<configuration>.<member>`, e.g. `lab-pair.buyer`), and
 * every other classifier keying a recording, except one that only backs a declared device
 * ([devicesOf]). A label without `when` or `except` holds on all of them.
 *
 * A value picks a device when the value's words appear in order, side by side, as whole words, in
 * the device's key. Words are the parts between hyphens. For a configuration member, the key is
 * both the configuration's name and the member's `classifier:`. So:
 * - `android` picks `android`, `android-phone` and `android-tablet`, and not `ios-ipad`.
 * - `lab-a` picks `lab-a` and `lab-a-rack`; add `except: [rack]` to leave the rack out.
 * - `rack` picks every device whose key has the word `rack`; `es` every key with the word `es`.
 * - `a` does not pick `lab-ab`: a word never matches part of another word.
 * - A configuration's name picks all its members; `<configuration>.<member>` picks that member
 *   only.
 *
 * `when` picks the devices; without it, every device is picked. `except` then takes devices out;
 * each `except` value must take out at least one device `when` picked.
 *
 * Values are compared to the keys as written, so they pick nothing a key does not spell: `tablet`
 * picks `android-tablet` and not `ios-ipad`. A tool that does not know `when` and `except` reads
 * the label as holding on the whole trail.
 */
object TrailLabelDevices {

  /**
   * One device a label can be scoped to. [id] is how it is named: its key, or
   * `<configuration>.<member>`. [keys] are what a value is matched against.
   */
  data class Device(val id: String, val keys: List<String>)

  /**
   * A label's devices: [devices] it holds on, by [Device.id] in the trail's order, and [problems]
   * with its `when` and `except`, one line each. When there are problems, [devices] is not
   * meaningful.
   */
  data class Resolution(val devices: List<String>, val problems: List<String>)

  /**
   * The devices [trail] declares, in the order it declares them: `config.devices` first, then
   * classifiers keying recordings that `config.devices` does not name. Configuration names never
   * appear on their own; their members do. The catch-all `all` classifier, as a `config.devices`
   * entry or a recording key, is not a device, and neither is a recording key on the
   * [TrailblazeClassifierLineage] of a declared classifier (`android` beside a declared
   * `android-phone`): that recording is the declared device's fallback, not another device. A
   * configuration's name is not a classifier, so it backs nothing.
   */
  fun devicesOf(trail: UnifiedTrail): List<Device> {
    val configurations = trail.config.multiDeviceConfigurationNames
    val devices = LinkedHashMap<String, Device>()
    // An `all:` entry holds defaults for every device; it is not a device itself.
    val declared = trail.config.devices.orEmpty() - ALL_CLASSIFIER
    declared.forEach { (key, definition) ->
      val members = definition.devices
      if (members == null) {
        devices.getOrPut(key) { Device(key, listOf(key)) }
      } else {
        members.forEach { (member, memberDefinition) ->
          val id = "$key.$member"
          devices[id] = Device(id, listOfNotNull(key, memberDefinition.classifier))
        }
      }
    }
    val recorded =
      (listOfNotNull(trail.trailhead?.recordings?.keys) + trail.trail.map { it.recordings.keys })
        .flatten()
    // A declared device's fallbacks, by classifier lineage (registered parent overrides included).
    // Only real classifiers count: a single-device key or a member's classifier, never a
    // configuration's name.
    val fallbacks =
      declared
        .flatMap { (key, definition) ->
          definition.devices?.values?.mapNotNull { it.classifier } ?: listOf(key)
        }
        .flatMap { classifier ->
          TrailblazeClassifierLineage.chainFor(TrailblazeDeviceClassifier(classifier)).drop(1).map {
            it.classifier
          }
        }
        .toSet()
    recorded
      .filter { it !in configurations && it != ALL_CLASSIFIER && it !in fallbacks }
      .forEach { key -> devices.getOrPut(key) { Device(key, listOf(key)) } }
    return devices.values.toList()
  }

  /**
   * [label]'s devices among [devices] ([devicesOf] the trail). With neither `when` nor `except`,
   * every device and no problems.
   */
  fun resolve(label: TrailLabel, devices: List<Device>): Resolution {
    val whenValues = label.whenDevices
    val exceptValues = label.exceptDevices
    if (whenValues == null && exceptValues == null) {
      return Resolution(devices.map { it.id }, emptyList())
    }
    val problems = buildList {
      addAll(valueProblems("when", whenValues))
      addAll(valueProblems("except", exceptValues))
    }
    if (problems.isNotEmpty()) return Resolution(emptyList(), problems)
    if (devices.isEmpty()) {
      return Resolution(
        emptyList(),
        listOf(
          "`when`/`except` name devices, but this trail declares none (no `config.devices` and no " +
            "per-device recordings); remove them, since the label holds on the whole trail"
        ),
      )
    }
    val unmatched =
      (whenValues.orEmpty().map { "when" to it } + exceptValues.orEmpty().map { "except" to it })
        .filter { (_, value) -> devices.none { matches(value, it) } }
        .map { (field, value) ->
          "`$field` value `$value` picks none of this trail's devices (${devices.joinToString { it.id }})"
        }
    if (unmatched.isNotEmpty()) return Resolution(emptyList(), unmatched)
    val whenPicked =
      devices.filter { device -> whenValues == null || whenValues.any { matches(it, device) } }
    // An `except` that takes out nothing `when` picked does nothing, so it is likely a mistake.
    val idle =
      exceptValues
        .orEmpty()
        .filter { value -> whenPicked.none { matches(value, it) } }
        .map { value ->
          "`except` value `$value` takes out none of the devices `when` picks " +
            "(${whenPicked.joinToString { it.id }})"
        }
    if (idle.isNotEmpty()) return Resolution(emptyList(), idle)
    val picked =
      whenPicked
        .filterNot { device -> exceptValues.orEmpty().any { matches(it, device) } }
        .map { it.id }
    if (picked.isEmpty()) {
      return Resolution(
        emptyList(),
        listOf(
          "`when`/`except` leave none of this trail's devices (${devices.joinToString { it.id }})"
        ),
      )
    }
    return Resolution(picked, emptyList())
  }

  /** Whether [value] picks [device]; see the class kdoc. [value] is assumed well formed. */
  fun matches(value: String, device: Device): Boolean {
    if ('.' in value) return value == device.id
    val wanted = value.split('-')
    return device.keys.any { key -> containsRun(key.split('-'), wanted) }
  }

  private fun containsRun(words: List<String>, run: List<String>): Boolean =
    (0..words.size - run.size).any { start -> run.indices.all { words[start + it] == run[it] } }

  /**
   * Problems with one list's shape alone, before any device is looked at: an empty list, and a
   * value that is not hyphen-joined words with at most one `.<member>`.
   */
  fun valueProblems(field: String, values: List<String>?): List<String> = buildList {
    if (values == null) return@buildList
    if (values.isEmpty()) add("`$field` is empty; list at least one device, or remove it")
    values
      .filterNot { VALUE.matches(it) }
      .forEach {
        add(
          "`$field` value `$it` is not a device: use hyphen-joined words, or " +
            "`<configuration>.<member>` for one member of a multi-device configuration"
        )
      }
    values
      .groupingBy { it }
      .eachCount()
      .filterValues { it > 1 }
      .keys
      .forEach { add("`$field` lists `$it` more than once") }
  }

  private const val ALL_CLASSIFIER = "all"

  private val VALUE = Regex("^[A-Za-z0-9_]+(?:-[A-Za-z0-9_]+)*(?:\\.[A-Za-z0-9_-]+)?$")
}
