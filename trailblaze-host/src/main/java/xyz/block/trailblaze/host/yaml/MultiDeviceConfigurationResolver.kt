package xyz.block.trailblaze.host.yaml

import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
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

  /** Env var naming which declared configuration a run binds. */
  const val DEVICE_CONFIGURATION_ENV_VAR: String = "TRAILBLAZE_DEVICE_CONFIGURATION"

  /**
   * How a message refers to the per-request bindings field, so a rejection tells the caller to fix
   * the channel they actually used instead of pointing at the env var they never set.
   */
  const val DEVICE_BINDINGS_REQUEST_FIELD: String = "RunYamlRequest.deviceBindings"

  /** Same, for the per-request configuration-selection field. */
  const val DEVICE_CONFIGURATION_REQUEST_FIELD: String = "RunYamlRequest.deviceConfiguration"

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
    /** Human-readable role descriptions, in declaration order and including the start device. */
    val memberDescriptions: Map<String, String?>,
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
   * [selectedConfigurationName] is the run's effective selection (see [resolve]'s precedence) and
   * is required to read the right start device on a trail declaring more than one configuration.
   * Without it every multi-configuration trail would force-stop the session default app, which is
   * the exact defect this function exists to prevent, one level up.
   *
   * Deliberately total: a decode failure, an unmatched selection, or an unselected multi-
   * configuration trail all come back as null rather than throwing. [resolve] runs moments later on
   * the same YAML and reports each properly; raising them here would move a trail-shape error into
   * an app-lifecycle step whose message says nothing about trail shape.
   */
  fun startDeviceTargetId(yaml: String, selectedConfigurationName: String? = null): String? = try {
    if (!TrailRecordings.isUnifiedTrailContent(yaml)) {
      null
    } else {
      val configurations = decodeConfigDevices(yaml).filterValues { it.isConfiguration }
      val selected = when (val name = selectedConfigurationName?.takeIf { it.isNotBlank() }) {
        null -> configurations.values.singleOrNull()
        else -> configurations[name]
      }
      selected?.devices.orEmpty()
        .values.firstOrNull()
        ?.target
    }
  } catch (_: Exception) {
    null
  }

  /**
   * Resolves the declared configuration to concrete devices, or null when the trail declares none.
   *
   * **Which configuration**: [requestConfigurationName] (the per-run field) wins over
   * [environmentConfigurationName] (`TRAILBLAZE_DEVICE_CONFIGURATION`, a daemon-wide default), and
   * a trail declaring exactly one configuration needs neither. A trail declaring more than one and
   * selecting neither is rejected — picking the first declared one would run a different device set
   * than the author of the second one expected.
   *
   * **Which devices**: the FIRST declared name is the start device and binds to the launch device
   * ([primaryDeviceId]). Each remaining name → device binding comes from [requestDeviceBindings]
   * when that map is non-empty, else from [rawDeviceBindings]
   * (`name=instanceId[,name=instanceId…]`, e.g. `buyer=emulator-5562`). The request field REPLACES
   * the env value rather than merging with it: a merge would mix a stale daemon-wide binding into a
   * caller's fresh device set, and the resulting run would name devices no caller asked for. The
   * declared classifier stays the trail's portable contract, and classifier-based auto-binding
   * against connected devices is future work.
   *
   * Per-device `target:` overrides come back as declared ids in
   * [ResolvedMultiDeviceConfiguration.memberTargetIds] — resolving them against loaded targets
   * needs a registry this pure object deliberately doesn't have.
   *
   * [sessionDriverType] is the driver this run resolved for the launch device — the one every
   * device in the cast is actually driven by (a WEB companion excepted, see
   * [effectiveMemberDriver]). It exists so a per-device `driver:` that the session cannot honor is
   * rejected rather than silently ignored. Required rather than defaulted, so a new runner path has
   * to state the driver it resolved instead of inheriting a stance by omission; pass null from a
   * path that hasn't resolved one, which rejects every DRIVER-DEPENDENT pin (a WEB companion's
   * `PLAYWRIGHT_NATIVE` doesn't depend on the session driver, so it is still accepted).
   *
   * Every rejection throws [TrailblazeException] with the offending names, because each one is a
   * misconfiguration that would otherwise run the trail against the wrong device set.
   */
  fun resolve(
    yaml: String,
    primaryDeviceId: TrailblazeDeviceId,
    rawDeviceBindings: String?,
    requestDeviceBindings: Map<String, String> = emptyMap(),
    requestConfigurationName: String? = null,
    environmentConfigurationName: String? = null,
    sessionDriverType: TrailblazeDriverType?,
  ): ResolvedMultiDeviceConfiguration? {
    // A non-unified (v1) trail declares no configuration rather than short-circuiting before
    // selection: returning null here would drop a per-request selection or binding silently, which
    // is the single-device run those fields exist to prevent.
    val configurations = if (TrailRecordings.isUnifiedTrailContent(yaml)) {
      decodeConfigDevices(yaml).filterValues { it.isConfiguration }
    } else {
      emptyMap()
    }
    val configurationName = selectConfigurationName(
      declaredNames = configurations.keys,
      requestConfigurationName = requestConfigurationName,
      environmentConfigurationName = environmentConfigurationName,
      requestDeviceBindingNames = requestDeviceBindings.keys,
    ) ?: return null
    val configuration = configurations.getValue(configurationName)
    val members = configuration.devices.orEmpty()
    // The first declared name is the start device: it binds to the LAUNCH device, so its driver is
    // the session's own and it never gets a companion connection. Computed before the validation
    // walk because `rejectUnhonorableDriver` has to tell the two apart.
    val startDeviceNameForValidation = members.keys.firstOrNull()
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
      rejectUnhonorableDriver(
        name = name,
        configurationName = configurationName,
        member = member,
        isStartDevice = name == startDeviceNameForValidation,
        primaryDeviceId = primaryDeviceId,
        sessionDriverType = sessionDriverType,
      )
    }
    val startDeviceName = members.keys.first()
    val bindingsSource = if (requestDeviceBindings.isNotEmpty()) {
      DEVICE_BINDINGS_REQUEST_FIELD
    } else {
      DEVICE_BINDINGS_ENV_VAR
    }
    val bindings = if (requestDeviceBindings.isNotEmpty()) {
      validateRequestDeviceBindings(requestDeviceBindings)
    } else {
      parseDeviceBindings(rawDeviceBindings)
    }

    val undeclaredNames = bindings.keys - members.keys
    if (undeclaredNames.isNotEmpty()) {
      throw TrailblazeException(
        "$bindingsSource binds ${undeclaredNames.sorted()} but configuration " +
          "'$configurationName' only declares ${members.keys}. A misspelled name would " +
          "otherwise be silently ignored and the intended device left unbound.",
      )
    }
    // The start device is the one the run was launched against (`-d`), never a binding: accepting
    // one here and then dropping it would silently ignore an operator's explicit intent.
    if (startDeviceName in bindings) {
      throw TrailblazeException(
        "$bindingsSource binds '$startDeviceName', which is configuration " +
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
        "$bindingsSource binds one device to multiple names: " +
          duplicateInstanceIds.entries.joinToString { (id, names) -> "$id -> ${names.sorted()}" } +
          ". Bind each name to a distinct device.",
      )
    }
    val companionDeviceIds = (members - startDeviceName).mapValues { (name, member) ->
      val instanceId = bindings[name]
        ?: throw TrailblazeException(
          "Configuration '$configurationName' declares device '$name' " +
            "(classifier '${member.classifier}') but no connected device is bound to it. Bind it " +
            "on $DEVICE_BINDINGS_REQUEST_FIELD (`\"$name\" to \"<deviceInstanceId>\"`), or set " +
            "$DEVICE_BINDINGS_ENV_VAR=\"$name=<deviceInstanceId>\" (comma-separated for " +
            "multiple devices) before the daemon starts — classifier-based auto-binding is not " +
            "implemented yet.",
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
      memberDescriptions = members.mapValues { (_, member) -> member.description },
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
   * The configuration name this run selects, before it is checked against what the trail declares:
   * the per-request field when it carries a non-blank one, else `TRAILBLAZE_DEVICE_CONFIGURATION`.
   *
   * The one place the ladder lives, so a third source or a change to what counts as "carries one"
   * cannot land in one copy and not another. Callers that need the run's selection want
   * [selectConfigurationName] instead — it returns this name only after checking it against what the
   * trail declares, which is what any caller acting on a device needs.
   */
  internal fun effectiveConfigurationName(
    requestConfigurationName: String?,
    environmentConfigurationName: String?,
  ): String? = requestConfigurationName?.takeIf { it.isNotBlank() }
    ?: environmentConfigurationName?.takeIf { it.isNotBlank() }

  /**
   * The configuration this run binds, or null when it binds none (a single-device trail).
   *
   * Precedence: [requestConfigurationName] (per-run, trail-specific) over
   * [environmentConfigurationName] (`TRAILBLAZE_DEVICE_CONFIGURATION`, snapshotted from the
   * daemon's process environment and therefore the same for every trail that daemon serves).
   *
   * The two sources are treated differently on a trail that declares NO configuration: a request
   * field naming one is a hard error, because that caller named it for this trail and got a
   * single-device run instead. The env var is ignored there — a daemon serving one multi-device lane
   * also serves ordinary single-device trails, and failing those would make the variable unusable.
   * [requestDeviceBindingNames] follows the request field for the same reason: a caller that bound
   * companions for this trail and got a single-device run against unbound devices needs to be told.
   * Once the trail DOES declare configurations, an unmatched name from either source is an error:
   * silently binding a configuration nobody asked for is the wrong-device-set failure every rule
   * here exists to prevent.
   */
  internal fun selectConfigurationName(
    declaredNames: Set<String>,
    requestConfigurationName: String?,
    environmentConfigurationName: String?,
    requestDeviceBindingNames: Set<String> = emptySet(),
  ): String? {
    val requested = requestConfigurationName?.takeIf { it.isNotBlank() }
    if (declaredNames.isEmpty()) {
      if (requested != null) {
        throw TrailblazeException(
          "$DEVICE_CONFIGURATION_REQUEST_FIELD selects device configuration '$requested', but " +
            "this trail declares no device configuration — a configuration is a `config.devices:` " +
            "entry with an inner `devices:` map of named devices. Drop the selection, or run a " +
            "trail that declares '$requested'.",
        )
      }
      if (requestDeviceBindingNames.isNotEmpty()) {
        throw TrailblazeException(
          "$DEVICE_BINDINGS_REQUEST_FIELD binds ${requestDeviceBindingNames.sorted()}, but this " +
            "trail declares no device configuration to bind them to — a configuration is a " +
            "`config.devices:` entry with an inner `devices:` map of named devices. Drop the " +
            "bindings, or run a trail that declares those devices.",
        )
      }
      return null
    }
    val selected = effectiveConfigurationName(
      requestConfigurationName = requestConfigurationName,
      environmentConfigurationName = environmentConfigurationName,
    )
    if (selected == null) {
      if (declaredNames.size > 1) {
        throw TrailblazeException(
          "This trail declares ${declaredNames.size} device configurations ($declaredNames) and " +
            "this run selects none. Name one on $DEVICE_CONFIGURATION_REQUEST_FIELD, or set " +
            "$DEVICE_CONFIGURATION_ENV_VAR=<name> before the daemon starts. Binding the first " +
            "declared configuration would run a different device set than whoever declared the " +
            "others expected.",
        )
      }
      return declaredNames.single()
    }
    if (selected !in declaredNames) {
      val source = if (requested != null) {
        DEVICE_CONFIGURATION_REQUEST_FIELD
      } else {
        DEVICE_CONFIGURATION_ENV_VAR
      }
      throw TrailblazeException(
        "$source selects device configuration '$selected', but this trail declares " +
          "$declaredNames. Fix the selection, or fix the trail's `config.devices:` names.",
      )
    }
    return selected
  }

  /**
   * Rejects a blank name or a blank instance id in a per-request bindings map. The env-var path gets
   * this for free — [parseDeviceBindings] drops unparseable entries — but a map literal can carry
   * `"buyer" to ""`, which would otherwise reach a connect call as an empty serial.
   */
  private fun validateRequestDeviceBindings(bindings: Map<String, String>): Map<String, String> {
    val blank = bindings.filter { (name, instanceId) -> name.isBlank() || instanceId.isBlank() }
    if (blank.isNotEmpty()) {
      throw TrailblazeException(
        "$DEVICE_BINDINGS_REQUEST_FIELD carries ${blank.size} binding(s) with a blank name or " +
          "device id: " + blank.entries.joinToString { (name, id) -> "'$name' -> '$id'" } +
          ". Bind each name to a connected device's instance id, or omit the entry.",
      )
    }
    return bindings
  }

  /**
   * The driver a named device is ACTUALLY driven by. A session resolves ONE driver for its whole
   * cast — the launch device's, from `--driver` / the trail's classifier-keyed pin / the app
   * setting — and every device in the configuration is dispatched over that driver. The one
   * exception is a WEB **companion**: `runHostAgentWithOnDeviceRpc` builds a host-owned Playwright
   * browser for it, so it runs [TrailblazeDriverType.PLAYWRIGHT_NATIVE] whatever the session
   * resolved.
   *
   * [isStartDevice] is what keeps that exception honest. The start device is bound to the LAUNCH
   * device and never gets a browser connection, so a start member declared `classifier: web` in a
   * cast launched on Android still runs the session's Android driver — exempting it would let the
   * one pin this object exists to catch through.
   *
   * An IOS member gets no exception, deliberately: nothing today builds a host-owned iOS driver for
   * a companion the way it builds a browser, so an iOS companion in an Android-launched cast is
   * dispatched over the session's Android driver and an `IOS_HOST` pin genuinely cannot be honored.
   * Add a branch here when — and only when — a companion iOS driver exists to honor it; adding one
   * sooner would accept a pin nothing carries out, which is the exact defect this check closes.
   *
   * Null when the caller resolved no session driver, i.e. this path can honor no driver-dependent
   * pin. A WEB companion's Playwright pin is not driver-dependent, so it is still accepted.
   */
  private fun effectiveMemberDriver(
    memberPlatform: TrailblazeDevicePlatform,
    isStartDevice: Boolean,
    sessionDriverType: TrailblazeDriverType?,
  ): TrailblazeDriverType? = if (memberPlatform == TrailblazeDevicePlatform.WEB && !isStartDevice) {
    TrailblazeDriverType.PLAYWRIGHT_NATIVE
  } else {
    sessionDriverType
  }

  /**
   * Rejects a configuration member whose declared `driver:` the session will not run it on.
   *
   * A member's `driver:` parses, and its platform is honored (it decides the device's platform when
   * the classifier carries none) — but nothing selects a driver PER device: the session picks one
   * driver and dispatches every Android device in the cast over it. A pin that names a different
   * driver is therefore not a preference the run downgrades from, it is an instruction the run
   * cannot carry out — and a trail that replays on `androidMaestro` selectors while its author
   * pinned `ANDROID_ONDEVICE_ACCESSIBILITY` stays green for the wrong reason. Fail at session
   * start, naming the device and both drivers, instead.
   *
   * A pin that RESTATES what the device already runs is accepted: nothing is dropped.
   */
  private fun rejectUnhonorableDriver(
    name: String,
    configurationName: String,
    member: TrailblazeDeviceDefinition,
    isStartDevice: Boolean,
    primaryDeviceId: TrailblazeDeviceId,
    sessionDriverType: TrailblazeDriverType?,
  ) {
    val declaredDriver = member.driver ?: return
    val effectiveDriver = effectiveMemberDriver(
      memberPlatform = memberPlatform(member, primaryDeviceId),
      isStartDevice = isStartDevice,
      sessionDriverType = sessionDriverType,
    )
    if (declaredDriver == effectiveDriver) return
    val preamble = "Device '$name' in configuration '$configurationName' declares " +
      "driver '${declaredDriver.name}', but "
    throw TrailblazeException(
      if (effectiveDriver == null) {
        preamble +
          "this execution path resolved no driver to check it against, so the pin could only be " +
          "silently ignored. Remove the per-device `driver:`."
      } else {
        preamble +
          "this run drives it with '${effectiveDriver.name}' — a configuration picks ONE driver " +
          "for its whole cast, so the pin would be silently ignored and the trail would replay on " +
          "the wrong driver's selectors. Remove the per-device `driver:`, or run the trail on " +
          "'${declaredDriver.name}' (e.g. `--driver ${declaredDriver.name}`)."
      },
    )
  }

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
