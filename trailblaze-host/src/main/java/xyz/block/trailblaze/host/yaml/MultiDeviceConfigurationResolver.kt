package xyz.block.trailblaze.host.yaml

import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.recordings.TrailRecordings
import xyz.block.trailblaze.yaml.TrailblazeYaml
import xyz.block.trailblaze.yaml.unified.TrailblazeDeviceDefinition
import xyz.block.trailblaze.yaml.unified.UnifiedTrailTargets

/**
 * Resolves a unified trail's `config.devices:` CONFIGURATION entry (an entry with an inner
 * `devices:` map of named devices, e.g. a paired-display setup) to concrete devices.
 *
 * Pure: the YAML and the raw binding string come in as arguments, so every rule below — which
 * trails declare a configuration, which bindings are accepted, which are rejected — is
 * unit-testable without a daemon, a device, or a process environment.
 */
object MultiDeviceConfigurationResolver {

  /** Env var binding declared device names to connected device instance ids. */
  const val DEVICE_BINDINGS_ENV_VAR: String = "TRAILBLAZE_DEVICE_BINDINGS"

  /**
   * A resolved multi-device configuration, before connect/warm-up: the selected entry's name,
   * the start device name (first declared — bound to the launch device), and each remaining
   * name's concrete device id in declaration order.
   */
  data class ResolvedMultiDeviceConfiguration(
    val configurationName: String,
    val startDeviceName: String,
    val companionDeviceIds: Map<String, TrailblazeDeviceId>,
    /**
     * Each declared device name → the target id it declares, in declaration order and including
     * [startDeviceName]. A null value means the device declares no override and inherits the
     * trail's session-level `config.target:`.
     *
     * Ids, not resolved targets: this object stays pure so the rules above are testable without a
     * target registry. The caller maps each id to a loaded target and fails loud on an unknown one.
     */
    val memberTargetIds: Map<String, String?>,
  )

  /**
   * Names of the CONFIGURATION entries this trail declares — empty for single-device trails
   * (including every legacy v1 trail, since only the unified shape can declare configurations).
   *
   * Decode failures throw rather than returning empty: an undecodable `config:` on a trail that
   * IS unified would otherwise silently downgrade a multi-device trail to a single-device run.
   */
  fun declaredConfigurationNames(yaml: String): Set<String> {
    if (!TrailRecordings.isUnifiedTrailContent(yaml)) return emptySet()
    return decodeConfigDevices(yaml).filterValues { it.isConfiguration }.keys
  }

  /**
   * The `target:` id the declared configuration's START device overrides with, or null when the
   * trail declares no configuration, the start device declares no override, or the trail isn't
   * unified.
   *
   * Exists for the app-lifecycle work a run does BEFORE it resolves the configuration properly —
   * force-stopping the app under test on the launch device. That work needs the app ids of the
   * target the start device will actually run, and reads them from the session target otherwise,
   * which is the wrong app whenever the start device overrides.
   *
   * Deliberately total: a decode failure, or more configurations than this runner supports, comes
   * back as null rather than throwing. [resolve] runs moments later on the same YAML and reports
   * both properly; raising them here would move a trail-shape error into an app-lifecycle step
   * whose message says nothing about trail shape.
   */
  fun startDeviceTargetId(yaml: String): String? = try {
    if (!TrailRecordings.isUnifiedTrailContent(yaml)) {
      null
    } else {
      decodeConfigDevices(yaml).values
        .singleOrNull { it.isConfiguration }
        ?.devices.orEmpty()
        .values.firstOrNull()
        ?.target
    }
  } catch (_: Exception) {
    null
  }

  /**
   * Resolves the declared configuration to concrete devices, or null when the trail declares none.
   *
   * The FIRST declared name is the start device and binds to the launch device ([primaryDeviceId]).
   * Each remaining name → device binding comes from [rawDeviceBindings]
   * (`name=instanceId[,name=instanceId…]`, e.g. `buyer=emulator-5562`); the declared classifier
   * stays the trail's portable contract, and classifier-based auto-binding against connected
   * devices is future work.
   *
   * Per-device `target:` overrides come back as declared ids in
   * [ResolvedMultiDeviceConfiguration.memberTargetIds] — resolving them against loaded targets
   * needs a registry this pure object deliberately doesn't have.
   *
   * Every rejection throws [TrailblazeException] with the offending names, because each one is a
   * misconfiguration that would otherwise run the trail against the wrong device set.
   */
  fun resolve(
    yaml: String,
    primaryDeviceId: TrailblazeDeviceId,
    rawDeviceBindings: String?,
  ): ResolvedMultiDeviceConfiguration? {
    if (!TrailRecordings.isUnifiedTrailContent(yaml)) return null
    val configurations = decodeConfigDevices(yaml).filterValues { it.isConfiguration }
    if (configurations.isEmpty()) return null
    if (configurations.size > 1) {
      throw TrailblazeException(
        "This trail declares ${configurations.size} device configurations " +
          "(${configurations.keys}) but explicit configuration selection is not implemented yet — " +
          "declare a single configuration.",
      )
    }
    val (configurationName, configuration) = configurations.entries.single()
    val members = configuration.devices.orEmpty()
    members.forEach { (name, member) ->
      val driverPlatform = member.driver?.platform
      val classifierPlatform = member.classifier?.let { UnifiedTrailTargets.platformFor(it) }
      if (driverPlatform != null && classifierPlatform != null && driverPlatform != classifierPlatform) {
        throw TrailblazeException(
          "Device '$name' in configuration '$configurationName' declares driver " +
            "'${member.driver}' ($driverPlatform) but its classifier '${member.classifier}' is a " +
            "$classifierPlatform device — exactly one of those intentions would be silently " +
            "dropped. Fix whichever one is wrong.",
        )
      }
    }
    val startDeviceName = members.keys.first()
    val bindings = parseDeviceBindings(rawDeviceBindings)

    val undeclaredNames = bindings.keys - members.keys
    if (undeclaredNames.isNotEmpty()) {
      throw TrailblazeException(
        "$DEVICE_BINDINGS_ENV_VAR binds ${undeclaredNames.sorted()} but configuration " +
          "'$configurationName' only declares ${members.keys}. A misspelled name would " +
          "otherwise be silently ignored and the intended device left unbound.",
      )
    }
    // The start device is the one the run was launched against (`-d`), never a binding: accepting
    // one here and then dropping it would silently ignore an operator's explicit intent.
    if (startDeviceName in bindings) {
      throw TrailblazeException(
        "$DEVICE_BINDINGS_ENV_VAR binds '$startDeviceName', which is configuration " +
          "'$configurationName''s START device — it is always the device this run was launched " +
          "against (${primaryDeviceId.instanceId}). Launch the run against " +
          "'${bindings.getValue(startDeviceName)}' instead, and bind only the other devices.",
      )
    }
    val duplicateInstanceIds = bindings.entries
      .groupBy({ it.value }, { it.key })
      .filterValues { it.size > 1 }
    if (duplicateInstanceIds.isNotEmpty()) {
      throw TrailblazeException(
        "$DEVICE_BINDINGS_ENV_VAR binds one device to multiple names: " +
          duplicateInstanceIds.entries.joinToString { (id, names) -> "$id -> ${names.sorted()}" } +
          ". Bind each name to a distinct device.",
      )
    }
    val companionDeviceIds = (members - startDeviceName).mapValues { (name, member) ->
      val instanceId = bindings[name]
        ?: throw TrailblazeException(
          "Configuration '$configurationName' declares device '$name' " +
            "(classifier '${member.classifier}') but no connected device is bound to it. Set " +
            "$DEVICE_BINDINGS_ENV_VAR=\"$name=<deviceInstanceId>\" (comma-separated for " +
            "multiple devices) to bind it — classifier-based auto-binding is not implemented yet.",
        )
      if (instanceId == primaryDeviceId.instanceId) {
        throw TrailblazeException(
          "Device '$name' is bound to ${primaryDeviceId.instanceId}, which is already the " +
            "session's start device ('$startDeviceName'). Bind each name to a distinct device.",
        )
      }
      TrailblazeDeviceId(
        instanceId = instanceId,
        trailblazeDevicePlatform = memberPlatform(member, primaryDeviceId),
      )
    }
    return ResolvedMultiDeviceConfiguration(
      configurationName = configurationName,
      startDeviceName = startDeviceName,
      companionDeviceIds = companionDeviceIds,
      memberTargetIds = members.mapValues { (_, member) -> member.target },
    )
  }

  /**
   * Maps each declared device name to the target it actually runs: its own `target:` override when
   * the configuration declares one, else [sessionTarget] (the trail's `config.target:` / the
   * caller's resolved default). Declaration order is preserved, and the start device is included —
   * an override on it wins for that device just like on any other.
   *
   * An override that names no loaded target is a hard error, and so is an override with no
   * [findTargetById] to resolve it (an execution path that can't reach the target registry).
   * Neither degrades to [sessionTarget]: a per-device target is always deliberate, so honoring it
   * partially would launch the wrong app on that device while the session still reported success.
   * This mirrors the run path's `config.target` rule, not the daemon's warn-and-fall-back one —
   * that leniency exists so a trail written elsewhere still runs, which does not apply to a
   * device-specific override.
   */
  fun resolveMemberTargets(
    configurationName: String,
    memberTargetIds: Map<String, String?>,
    sessionTarget: TrailblazeHostAppTarget?,
    findTargetById: ((String) -> TrailblazeHostAppTarget?)?,
  ): Map<String, TrailblazeHostAppTarget?> = memberTargetIds.mapValues { (name, declaredId) ->
    if (declaredId == null) {
      sessionTarget
    } else {
      val lookup = findTargetById
        ?: throw TrailblazeException(
          "Device '$name' in configuration '$configurationName' declares target '$declaredId', " +
            "but this execution path cannot resolve per-device targets. Run the trail through " +
            "the daemon (`trailblaze run`), or remove the per-device `target:`.",
        )
      lookup(declaredId)
        ?: throw TrailblazeException(
          "Device '$name' in configuration '$configurationName' declares target '$declaredId', " +
            "which is not registered in this Trailblaze installation. Fix the device's `target:`, " +
            "create the target, or restart the daemon to pick up edits.",
        )
    }
  }

  /**
   * The platform a named device runs on: its declared `driver:`'s platform when one is pinned,
   * else the platform its `classifier:` folds up to (`web` → WEB, `android-phone` → ANDROID,
   * `ios-iphone` → IOS), else the launch device's platform — an internal device-family classifier
   * (e.g. `lab-a`) carries no platform prefix, and those casts are same-platform pairs.
   *
   * Before this fold existed every companion inherited the launch device's platform, which
   * mis-stamped any cross-platform cast (a web browser or an iOS simulator companion of an
   * Android launch device) as the launch platform and then drove it over the wrong transport.
   */
  private fun memberPlatform(
    member: TrailblazeDeviceDefinition,
    primaryDeviceId: TrailblazeDeviceId,
  ): TrailblazeDevicePlatform = member.driver?.platform
    ?: member.classifier?.let { UnifiedTrailTargets.platformFor(it) }
    ?: primaryDeviceId.trailblazeDevicePlatform

  /**
   * Parses `name=instanceId[,name=instanceId…]`. A name repeated in one string is rejected instead
   * of last-wins: the operator asked for two devices under one name and exactly one of those
   * intentions would be dropped silently.
   */
  internal fun parseDeviceBindings(raw: String?): Map<String, String> {
    val entries = raw
      ?.split(',')
      ?.mapNotNull { entry ->
        val name = entry.substringBefore('=').trim()
        val instanceId = entry.substringAfter('=', "").trim()
        if (name.isEmpty() || instanceId.isEmpty()) null else name to instanceId
      }
      .orEmpty()
    val duplicateNames = entries.groupBy({ it.first }, { it.second }).filterValues { it.size > 1 }
    if (duplicateNames.isNotEmpty()) {
      throw TrailblazeException(
        "$DEVICE_BINDINGS_ENV_VAR binds the same name more than once: " +
          duplicateNames.entries.joinToString { (name, ids) -> "$name -> ${ids.sorted()}" } +
          ". Bind each name exactly once.",
      )
    }
    return entries.toMap()
  }

  /**
   * Config-only decode: recorded custom tools in the steps would throw on this default
   * (custom-tool-free) YAML instance, but `config:` never contains tool recordings. Decode
   * failures are re-thrown with context rather than swallowed — the caller already established
   * this IS a unified trail, so an undecodable config here would otherwise silently downgrade a
   * multi-device trail to a single-device session.
   */
  private fun decodeConfigDevices(yaml: String) = try {
    TrailblazeYaml.Default.decodeUnifiedTrailConfig(yaml).devices.orEmpty()
  } catch (e: Exception) {
    throw TrailblazeException(
      "Failed to decode this unified trail's `config:` block while resolving its device " +
        "configuration: ${e.message}",
    )
  }
}
