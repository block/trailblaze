package xyz.block.trailblaze.host

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar

/**
 * `trailblaze check` gate on how a trail spells its **device pins** — the `config.devices:` block.
 * Three rules; the two that describe a mis-indented pin are fatal, the deprecation is advisory.
 *
 *  1. [SessionDriverBesideDevices] (FATAL) — a `config:` that declares BOTH `driver:` and
 *     `devices:`. A device pin indented TWO levels too shallow: `driver:` lands as a session-level
 *     field and the device it was meant for is silently left unpinned, so the trail reads as
 *     pinning a driver while running whatever the runtime resolves.
 *  2. [DriverKeyedDeviceEntry] (FATAL) — a `config.devices:` entry whose key is literally `driver`.
 *     The same pin indented ONE level too shallow: `driver:` becomes a sibling of the classifier
 *     rather than its child, so it declares a device classifier named `driver` — which no device
 *     ever reports — while the classifier above it goes unpinned. This is the silent one. The
 *     two-level shape at least fails a STRICT decode (`driver` is not a field of
 *     [xyz.block.trailblaze.yaml.unified.UnifiedTrailConfig]), so a repo running the repo-wide
 *     strict-parse test sees a generic "Unknown property 'driver'". The one-level shape decodes
 *     cleanly at every strictness — a valid classifier key holding a valid driver name — and is
 *     caught by nothing else at all.
 *  3. [LegacyDriverForm] (advisory) — the deprecated bare-string spelling
 *     (`android: ANDROID_ONDEVICE_ACCESSIBILITY` instead of `android:` / `driver: …`). It still
 *     decodes, so this reports rather than fails; see "Advisory, for now".
 *
 * ## Why this reads raw YAML instead of the decoded trail
 *
 * Neither rule is visible on a decoded [xyz.block.trailblaze.yaml.unified.UnifiedTrail], which is
 * what the sibling [SelectorDialectLint] receives:
 *
 *  - `driver` is **not** a field of [xyz.block.trailblaze.yaml.unified.UnifiedTrailConfig] (it is a
 *    field of the v1 `TrailConfig`, which is why it reads as legitimate to an author). Trail
 *    decoding runs `strictMode = false` so an older binary can still load newer trails, and lenient
 *    kaml drops an unknown key on the floor. By the time the trail is an object, `config.driver:`
 *    is simply gone — there is nothing left to lint. (Strict decoding *would* reject it, but
 *    turning that on for every trail rejects every forward-compatible key too, which is the
 *    leniency the runtime depends on; and it says only "Unknown property 'driver'", which sends an
 *    author hunting a typo rather than an indent.)
 *  - Both device forms decode to the identical
 *    [xyz.block.trailblaze.yaml.unified.TrailblazeDeviceDefinition], so the decoded object cannot
 *    say which one was written.
 *
 * The alternative for rule 3 was to make
 * [xyz.block.trailblaze.yaml.unified.TrailblazeDeviceDefinitionMapSerializer]'s existing
 * deprecation log observable — collect the warning instead of only printing it. That was rejected:
 * the serializer is `commonMain` (no `ThreadLocal`), so an observable channel means ambient global
 * state that every decode mutates, which makes decoding order-dependent and puts the tests that
 * deliberately exercise the bare-string branch one collector leak away from each other. It also
 * only solves one rule of three, since rules 1 and 2 need the raw text regardless. Reading the
 * source is
 * a pure function of the file, adds no `:trailblaze-models` API, and is exactly the structural
 * question being asked: is `config.devices.<key>` written as a scalar?
 *
 * ## Advisory, for now
 *
 * Rule 3 stays a warning because the bare-string branch is still live: `TrailblazeDeviceDefinition`
 * decodes it on purpose, and repos that consume this CLI without having migrated their own trails
 * would otherwise have `trailblaze check` fail the moment they pick up a new build. Flip it to
 * fatal in [xyz.block.trailblaze.cli.CheckCommand]'s trail-lint phase when that decode-only branch
 * is deleted.
 *
 * ## Scope
 *
 * Only a trail's own top-level `config.devices:`. A `devices:` map anywhere else — a multi-device
 * configuration's inner cast, or another schema's top-level block such as `TrailblazeCiConfig`'s
 * `deviceDriverTypes:` — is a different shape with different rules and is untouched. A `null` entry
 * (`web:` with no value) is not a legacy pin either: it declares a classifier and pins nothing,
 * which the object form has no shorter spelling for.
 */
object DevicePinLint {

  /** Env kill-switch: `1`/`true` (case-insensitive) skips the gate entirely. */
  const val DISABLE_ENV_VAR: String = "TRAILBLAZE_DISABLE_DEVICE_PIN_GATE"

  private const val MAX_EXAMPLES = 5

  /**
   * The map key a mis-indented pin lands under. Never a real device classifier — classifiers name
   * platform or hardware families (`android`, `ios-ipad`, `lab-a`), and no device reports `driver`.
   */
  private const val DRIVER_KEY = "driver"

  /** `config:` declares both `driver:` and `devices:` — a pin two levels too shallow. */
  data class SessionDriverBesideDevices(
    /** 1-based line of the offending `driver:` key. */
    val line: Int,
    /** The classifiers `config.devices:` does declare, so the message can name where it belongs. */
    val declaredClassifiers: List<String>,
  )

  /** A `config.devices:` entry keyed `driver` — a pin one level too shallow. */
  data class DriverKeyedDeviceEntry(
    /** 1-based line of the offending `driver:` key. */
    val line: Int,
    /** The other classifiers declared beside it — one of them is the device meant to be pinned. */
    val siblingClassifiers: List<String>,
  )

  /** One `config.devices:` entry written in the deprecated bare-string form. */
  data class LegacyDriverForm(
    val classifier: String,
    /** The scalar as written — the driver name it is standing in for. */
    val driverName: String,
    /** 1-based line of the entry key. */
    val line: Int,
  )

  /** One finding per offending trail. */
  data class Finding(
    val trailRelPath: String,
    val sessionDriverBesideDevices: SessionDriverBesideDevices? = null,
    val driverKeyedDeviceEntry: DriverKeyedDeviceEntry? = null,
    val legacyDriverForms: List<LegacyDriverForm> = emptyList(),
  ) {
    /** True when this finding must fail the build rather than warn. */
    val isFatal: Boolean get() = sessionDriverBesideDevices != null || driverKeyedDeviceEntry != null
  }

  /**
   * PURE. Lint one trail's raw source against all three rules. Returns null when none matches —
   * including when the text isn't parseable YAML, isn't a mapping, or declares no `config.devices:`
   * map at all. An unparseable trail is the parse-level validators' error to report;
   * double-reporting one broken file as two failures only makes triage worse.
   */
  fun lint(trailRelPath: String, yamlText: String): Finding? {
    val config = childMap(rootMap(yamlText), "config") ?: return null
    // Every rule is about a `devices:` MAP. `devices:` written as a scalar or left null declares no
    // pins, so none of them has anything to say about it.
    val devices = childMap(config, "devices") ?: return null

    val sessionDriver = config.entries.entries
      .firstOrNull { (key, _) -> key.content == DRIVER_KEY }
      ?.let { (key, _) ->
        SessionDriverBesideDevices(
          line = key.location.line,
          declaredClassifiers = devices.entries.keys.map { it.content },
        )
      }

    val driverKeyed = devices.entries.entries
      // A CONFIGURATION named `driver` is legal, if odd: an entry whose value carries an inner
      // `devices:` cast is a named multi-device configuration
      // ([xyz.block.trailblaze.yaml.unified.TrailblazeDeviceDefinition.isConfiguration]), and
      // configuration names are unrestricted. Excluding it cannot hide a mis-indent, because the
      // shape a mis-indent produces is `driver: <scalar>` or `driver:` with nothing — never a map
      // that declares its own cast.
      .firstOrNull { (key, value) -> key.content == DRIVER_KEY && !isConfiguration(value) }
      ?.let { (key, _) ->
        DriverKeyedDeviceEntry(
          line = key.location.line,
          siblingClassifiers = devices.entries.keys.map { it.content } - DRIVER_KEY,
        )
      }

    val legacy = devices.entries
      // A `driver`-keyed entry is reported as the mis-indent it is, not as one more deprecated
      // pin — otherwise the same line shows up twice under two different fixes.
      .filterKeys { it.content != DRIVER_KEY }
      .mapNotNull { (key, value) ->
        // A YamlScalar value IS the driver name (the deprecated form). YamlNull is a distinct node
        // type, so `web:` with no value never lands here.
        (value as? YamlScalar)?.let { LegacyDriverForm(key.content, it.content, key.location.line) }
      }

    return if (sessionDriver == null && driverKeyed == null && legacy.isEmpty()) {
      null
    } else {
      Finding(trailRelPath, sessionDriver, driverKeyed, legacy)
    }
  }

  /** Render the fatal half — a device pin indented too shallow, at either depth. */
  fun renderFatalFailures(findings: List<Finding>): String = buildString {
    val fatal = findings.filter { it.isFatal }.sortedBy { it.trailRelPath }
    appendLine("── device-pin gate (FATAL) ─────────────────────────────────────")
    appendLine(
      "${fatal.size} trail(s) declare a device driver outside the classifier it belongs to, which " +
        "leaves that device UNPINNED while the trail reads as if it pinned one. Fix: nest " +
        "`driver:` under the classifier.",
    )
    appendLine("")
    appendLine("  config:")
    appendLine("    devices:")
    appendLine("      <classifier>:")
    appendLine("        driver: <DRIVER>")
    appendLine("")
    fatal.forEach { f ->
      f.sessionDriverBesideDevices?.let {
        appendLine(
          "  FAIL ${f.trailRelPath}:${it.line}: `driver:` is a sibling of `devices:`, so it is a " +
            "session-level `config.driver:` — declared classifier(s): " +
            it.declaredClassifiers.joinToString(),
        )
      }
      f.driverKeyedDeviceEntry?.let {
        appendLine(
          "  FAIL ${f.trailRelPath}:${it.line}: `driver:` is an ENTRY of `devices:`, so it declares " +
            "a device classifier named 'driver' that no device reports — classifier(s) it should " +
            "be nested under: ${it.siblingClassifiers.joinToString().ifEmpty { "(none declared)" }}",
        )
      }
    }
  }

  /**
   * Render the advisory half — deprecated bare-string pins. Separate from [renderFatalFailures]
   * because these do not fail the build; a reader must not have to infer severity from position in
   * one blob.
   */
  fun renderDeprecationWarnings(findings: List<Finding>): String = buildString {
    val entries = findings
      .sortedBy { it.trailRelPath }
      .flatMap { f -> f.legacyDriverForms.map { f.trailRelPath to it } }
    appendLine("── device-pin gate (deprecation) ───────────────────────────────")
    appendLine(
      "${entries.size} `config.devices:` entr${if (entries.size == 1) "y uses" else "ies use"} the " +
        "deprecated bare-string driver form in ${findings.count { it.legacyDriverForms.isNotEmpty() }} " +
        "trail(s). It still decodes, but it cannot carry any per-device capability the object form " +
        "has (`classifier:`, `target:`, `description:`, an inner `devices:` cast), it is never " +
        "written back — encoding always emits the object form, so the next re-record churns the " +
        "diff — and the branch that reads it is slated for deletion. Write instead:",
    )
    appendLine("")
    appendLine("  config:")
    appendLine("    devices:")
    appendLine("      <classifier>:")
    appendLine("        driver: <DRIVER>")
    appendLine("")
    entries.take(MAX_EXAMPLES).forEach { (rel, entry) ->
      appendLine("  WARN $rel:${entry.line}: ${entry.classifier}: ${entry.driverName}")
    }
    if (entries.size > MAX_EXAMPLES) {
      appendLine("  … and ${entries.size - MAX_EXAMPLES} more")
    }
  }

  private fun rootMap(yamlText: String): YamlMap? = try {
    Yaml.default.parseToYamlNode(yamlText) as? YamlMap
  } catch (_: Throwable) {
    null
  }

  private fun childMap(parent: YamlMap?, key: String): YamlMap? = parent?.entries?.entries
    ?.firstOrNull { it.key.content == key }
    ?.value as? YamlMap

  /** A named multi-device configuration: an entry whose value declares its own inner cast. */
  private fun isConfiguration(value: YamlNode): Boolean = childMap(value as? YamlMap, "devices") != null
}
