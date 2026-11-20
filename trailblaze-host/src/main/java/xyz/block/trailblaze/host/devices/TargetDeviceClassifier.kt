package xyz.block.trailblaze.host.devices

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import xyz.block.trailblaze.config.TargetDeviceClassifierConfig
import xyz.block.trailblaze.devices.DeviceClassifierKey
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.quickjs.tools.QuickJsToolHost
import xyz.block.trailblaze.scripting.DaemonScriptedToolBundler
import xyz.block.trailblaze.scripting.HostScriptedToolLauncher
import xyz.block.trailblaze.scripting.WorkspaceSurveyBundleSource
import xyz.block.trailblaze.util.AndroidHostAdbUtils
import xyz.block.trailblaze.util.Console

/**
 * Runs a target's device classifier (`target.device_classifier:`, a `trailblaze.deviceClassifier(...)`
 * export) to get the device key, such as `android-tablet` → `acme-k2` for a line of dedicated hardware.
 *
 * The classifier is pure: it gets the platform, the framework's key and the device's maker and
 * model, and returns the whole key. It runs in QuickJS with no device session.
 */
object TargetDeviceClassifier {

  /** What the device says about itself, beyond the framework's key. */
  data class DeviceFacts(val manufacturer: String? = null, val model: String? = null)

  /** Calls a classifier with its input and returns what it returned. Tests inject a stub. */
  fun interface Runner {
    suspend fun run(config: TargetDeviceClassifierConfig, input: JsonObject): JsonObject
  }

  /**
   * The key for a device on a target: [detected] (the framework's key for the device) refined by
   * [requested] (the key the run asked for, often empty), or the target's classifier's key for the
   * device refined by [requested] when the target has one.
   *
   * The classifier's key either starts with [detected] and adds no platform or form factor, or names
   * a device family the framework doesn't know and has no platform or form factor at all (such as a
   * line of dedicated hardware). A [requested] key starts with [detected] or with the classifier's
   * key, and the rest of it, such as a locale, goes on the end of the classifier's key.
   *
   * @param facts read only when there is a classifier, since reading them talks to the device.
   * @throws IllegalArgumentException when the requested key doesn't fit the device, the classifier
   *   fails, or it returns a key that breaks the rules above.
   */
  suspend fun keyFor(
    config: TargetDeviceClassifierConfig?,
    platform: TrailblazeDevicePlatform,
    detected: List<TrailblazeDeviceClassifier>,
    requested: List<TrailblazeDeviceClassifier>,
    facts: suspend () -> DeviceFacts = { DeviceFacts() },
    runner: Runner = defaultRunner,
  ): List<TrailblazeDeviceClassifier> {
    if (config == null) return DeviceClassifierKey.refine(detected, requested)
    val known = facts()
    val input = buildJsonObject {
      put("platform", platform.name.lowercase())
      putJsonArray("key") { detected.forEach { add(JsonPrimitive(it.classifier)) } }
      known.manufacturer?.takeIf { it.isNotBlank() }?.let { put("manufacturer", it) }
      known.model?.takeIf { it.isNotBlank() }?.let { put("model", it) }
    }
    val result = try {
      runner.run(config, input)
    } catch (e: CancellationException) {
      throw e
    } catch (e: IllegalArgumentException) {
      throw e
    } catch (e: Exception) {
      throw IllegalArgumentException("Device classifier ${config.name} failed: ${e.message ?: e::class.simpleName}", e)
    }
    val computed = keyOf(config, result).also { checkKey(config, detected, it) }
    val rest = when {
      requested.isEmpty() -> return computed
      // The run names the device by the classifier's own key, such as a hardware family. Checked
      // first: a key that extends the framework's also starts with it, and its values must not repeat.
      // Refined the same way, so it refuses what a run without a classifier refuses (`-android-iphone`).
      requested.take(computed.size) == computed -> DeviceClassifierKey.refine(computed, requested).drop(computed.size)
      requested.take(detected.size) == detected -> DeviceClassifierKey.refine(detected, requested).drop(detected.size)
      else -> throw IllegalArgumentException(
        "requested device classifier '${requested.joined()}' does not match connected device " +
          "'${computed.joined()}' (from ${config.name}; the framework's key is ${detected.joined()})",
      )
    }
    return computed + rest
  }

  /** A classifier's key keeps the framework's, or names a family with no platform or form factor. */
  private fun checkKey(
    config: TargetDeviceClassifierConfig,
    detected: List<TrailblazeDeviceClassifier>,
    computed: List<TrailblazeDeviceClassifier>,
  ) {
    val extendsFramework = detected.isNotEmpty() && computed.take(detected.size) == detected
    val added = if (extendsFramework) computed.drop(detected.size) else computed
    require(computed.isNotEmpty() && added.none { DeviceClassifierKey.isPlatformOrFormFactor(it.classifier) }) {
      "Device classifier ${config.name} returned ${computed.joined()}. A key must start with the " +
        "framework's key ${detected.joined()} and add no platform or form factor, or name a device " +
        "family with no platform or form factor at all; only the framework decides those."
    }
  }

  /**
   * The key a run uses, and what it tells the user: [keyFor] when the device's form factor was
   * measured ([definitive]), else the requested key alone, since a classifier builds on the
   * framework's key and a guessed one would put recordings under the wrong key.
   */
  data class RunKey(
    val key: List<TrailblazeDeviceClassifier>,
    /** The key to send as the run's requested key, or null when the classifier changed nothing. */
    val override: List<TrailblazeDeviceClassifier>?,
    val message: String?,
  )

  /** [RunKey] for a run on a target whose classifier is [config]; see [keyFor] for the rest. */
  suspend fun forRun(
    config: TargetDeviceClassifierConfig?,
    definitive: Boolean,
    platform: TrailblazeDevicePlatform,
    detected: List<TrailblazeDeviceClassifier>,
    requested: List<TrailblazeDeviceClassifier>,
    facts: suspend () -> DeviceFacts,
    runner: Runner = defaultRunner,
  ): RunKey {
    if (config == null) return RunKey(DeviceClassifierKey.refine(detected, requested), override = null, message = null)
    if (!definitive) {
      val requestedKey = DeviceClassifierKey.refine(detected, requested)
      return RunKey(
        key = requestedKey,
        override = null,
        message = "Device classifier ${config.name} skipped: this device's form factor couldn't be " +
          "measured, so the run uses ${requestedKey.joined()}.",
      )
    }
    val key = keyFor(config, platform, detected, requested, facts, runner)
    // A request in the classifier's own terms, such as a device family's key, has no plain key.
    val withoutClassifier = runCatching { DeviceClassifierKey.refine(detected, requested) }.getOrNull()
    if (key == withoutClassifier) return RunKey(key, override = null, message = null)
    return RunKey(key, override = key, message = "Device key from ${config.name}: ${key.joined()}")
  }

  /** The maker and model of an Android device, from `getprop`; nothing for other platforms. */
  suspend fun factsFor(deviceId: TrailblazeDeviceId): DeviceFacts {
    if (deviceId.trailblazeDevicePlatform != TrailblazeDevicePlatform.ANDROID) return DeviceFacts()
    fun prop(name: String): String? = try {
      AndroidHostAdbUtils.execAdbShellCommandWithTimeout(deviceId = deviceId, args = listOf("getprop", name), timeoutMs = 3_000)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
      Console.log("[TargetDeviceClassifier] getprop $name failed for ${deviceId.instanceId}: ${e.message}")
      null
    }
    return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
      DeviceFacts(manufacturer = prop("ro.product.manufacturer"), model = prop("ro.product.model"))
    }
  }

  private fun keyOf(config: TargetDeviceClassifierConfig, result: JsonObject): List<TrailblazeDeviceClassifier> {
    if ((result["isError"] as? JsonPrimitive)?.booleanOrNull == true) {
      val message = (result["content"] as? JsonArray)?.firstOrNull()
        ?.let { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }
      throw IllegalArgumentException("Device classifier ${config.name} failed: ${message ?: result}")
    }
    val body = result["structuredContent"] as? JsonObject ?: result
    val key = body["key"] as? JsonArray
      ?: throw IllegalArgumentException(
        "Device classifier ${config.name} returned no key. Declare it with trailblaze.deviceClassifier(...).",
      )
    return key.map { element ->
      val value = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
      require(value != null && SEGMENT.matches(value)) {
        "Device classifier ${config.name} returned $element; a value must be lowercase letters, digits and _."
      }
      TrailblazeDeviceClassifier(value)
    }
  }

  private val SEGMENT = Regex("[a-z0-9_]+")

  private fun List<TrailblazeDeviceClassifier>.joined() =
    if (isEmpty()) "(nothing)" else joinToString("-") { it.classifier }

  /** The export of the generated entry the framework calls; it wraps the target's classifier. */
  internal const val ENTRY_EXPORT = "__trailblaze_deviceClassifier"

  /**
   * The entry that loads [config]'s classifier and exports [ENTRY_EXPORT], which calls it and
   * returns `{ key }`. [scriptFor] turns the config's `script` into a file on disk.
   */
  internal fun entrySource(config: TargetDeviceClassifierConfig, scriptFor: (String) -> File): String = buildString {
    appendLine("// Generated by Trailblaze: a target's device classifier.")
    val path = scriptFor(config.script).absoluteFile.invariantSeparatorsPath
    appendLine("import { ${config.name} as __classifier } from ${jsString(path)};")
    appendLine()
    appendLine("export const $ENTRY_EXPORT = (device: unknown) => ({ key: __classifier(device as never) });")
  }

  private fun jsString(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

  /**
   * Answers per bundle and input. A classifier is pure and its bundle is content-addressed, so an
   * answer can't go stale: an edited classifier bundles to a new file.
   */
  private val answers = ConcurrentHashMap<Pair<String, JsonObject>, JsonObject>()

  private val defaultRunner by lazy { quickJsRunner() }

  /**
   * Bundles the classifier with [bundler] (the toolchain this process resolved, by default; looked
   * up once it is found) and calls it in a fresh QuickJS engine with no device session.
   */
  fun quickJsRunner(
    bundler: () -> DaemonScriptedToolBundler? = { WorkspaceSurveyBundleSource.resolvedToolchainBundler() },
    scriptFor: (String) -> File = { HostScriptedToolLauncher.resolveScriptFile(it) },
    entryDir: File = File(System.getProperty("java.io.tmpdir"), "trailblaze-device-classifiers"),
  ): Runner {
    var resolved: DaemonScriptedToolBundler? = null
    return Runner { config, input ->
      val activeBundler = resolved ?: bundler()?.also { resolved = it }
        ?: throw IllegalArgumentException(
          "Device classifier ${config.name} can't run: esbuild or the Trailblaze TypeScript SDK is not " +
            "available to this process.",
        )
      val bundle = activeBundler.bundleOne(writeEntry(entrySource(config, scriptFor), entryDir), ENTRY_EXPORT)
      answers[bundle.path to input]?.let { return@Runner it }
      val host = QuickJsToolHost.connect(bundleJs = bundle.readText(), bundleFilename = "${config.name}.ts")
      val answer = try {
        host.callTool(ENTRY_EXPORT, input)
      } finally {
        host.shutdown()
      }
      answers[bundle.path to input] = answer
      answer
    }
  }

  /** Writes [source] under [dir], named by its content, so the same entry is written once. */
  private fun writeEntry(source: String, dir: File): File {
    val digest = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
    val file = File(dir, "classifier-${digest.joinToString("") { "%02x".format(it) }.take(16)}.ts")
    if (file.isFile && file.readText() == source) return file
    dir.mkdirs()
    val tmp = File.createTempFile(file.name, ".tmp", dir)
    tmp.writeText(source)
    if (!tmp.renameTo(file)) {
      tmp.delete()
      if (!file.isFile) throw IllegalStateException("Could not write ${file.absolutePath}")
    }
    return file
  }
}
