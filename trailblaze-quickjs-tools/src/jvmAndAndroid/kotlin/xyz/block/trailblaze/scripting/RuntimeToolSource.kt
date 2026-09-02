package xyz.block.trailblaze.scripting

/**
 * Where an on-device session reads its pre-compiled scripted-tool bundles from: the APK's own
 * assets, or a directory a host pushed onto the device just before the run.
 *
 * Bundles are normally baked into the test APK at build time, which pins the tool vocabulary to
 * whenever that APK was built. A host that drives the run itself (`adb push` + `am instrument`) can
 * deliver bundles matching the trails it is about to replay instead — but only through two gates,
 * because [DEVICE_DIRECTORY] holds unsigned code that will execute inside the app's process:
 *
 *  1. **Under instrumentation.** A production install of an app that ships these classes must never
 *     read tools off disk. Only a test run does.
 *  2. **The target config opted in.** `allow_runtime_tool_source: true` is written into the target
 *     config BEFORE the APK is signed, so the opt-in is covered by the signature: what the signing
 *     team signed is what runs. A shell signed at someone else's key ceremony defaults to OFF and
 *     replays from its frozen assets even when a host drives it, because the shell cannot tell one
 *     host holding a device from another.
 *
 * Both gates are pure inputs so the policy is decidable off-device — see `RuntimeToolSourceTest`.
 * The device half (does the file exist, can it be read) is the caller's.
 */
object RuntimeToolSource {

  /**
   * Directory a host pushes bundles into, mirroring each bundle's asset path underneath
   * (`<dir>/trails/config/trailmaps/<id>/tools/<stem>.bundle.js`).
   *
   * `/data/local/tmp` is `drwxrwx--x shell shell`: an app process may traverse it but cannot write
   * to it or list it, so only a host holding adb can plant a bundle here, and world-readable files
   * inside it are reachable by exact path. Verified on API 34 and API 36 — an instrumented app
   * process reads a `0644` file at this path (`RuntimeToolSourceOnDeviceTest`); SELinux labels such
   * a file `shell_data_file`, which `appdomain` may read.
   */
  const val DEVICE_DIRECTORY: String = "/data/local/tmp/trailblaze/tool-bundles"

  /** Why a session is reading its bundles from assets rather than [DEVICE_DIRECTORY]. */
  enum class IgnoredReason(val explanation: String) {
    NOT_UNDER_INSTRUMENTATION(
      "not running under instrumentation — the runtime tool source is a test-run affordance and a " +
        "production install must never load tool code off disk",
    ),
    TARGET_DID_NOT_OPT_IN(
      "the signed target config does not set `allow_runtime_tool_source: true` — honoring a pushed " +
        "bundle without that opt-in would make this APK a bearer capability for running unsigned " +
        "code inside the app's process from any host holding the device",
    ),
  }

  sealed interface Decision {
    /** Bundles present under [directory] win over the same bundle in assets. */
    data class Honored(val directory: String) : Decision

    /** Assets are the only source; [reason] says which gate closed. */
    data class Ignored(val reason: IgnoredReason) : Decision
  }

  /**
   * Applies both gates. [underInstrumentation] is checked first so the message a production install
   * would log names the property that actually protects it, rather than the target's opt-in.
   */
  fun resolve(
    underInstrumentation: Boolean,
    targetOptedIn: Boolean,
    directory: String = DEVICE_DIRECTORY,
  ): Decision = when {
    !underInstrumentation -> Decision.Ignored(IgnoredReason.NOT_UNDER_INSTRUMENTATION)
    !targetOptedIn -> Decision.Ignored(IgnoredReason.TARGET_DID_NOT_OPT_IN)
    else -> Decision.Honored(directory)
  }

  /**
   * Absolute path a bundle would be pushed to, or null when [bundlePath] is not addressable under
   * [directory].
   *
   * Bundle paths come from target YAML (`tools:` → `script:`), which is consumer-controlled, so a
   * `..` segment is rejected here rather than left to the filesystem — the same defense
   * `AndroidAssetBundleSource` applies to the asset route.
   */
  fun filePathFor(directory: String, bundlePath: String): String? {
    val normalized = bundlePath.removePrefix("./").trimStart('/')
    if (normalized.isEmpty()) return null
    if (normalized.split('/').any { it == ".." }) return null
    return "${directory.trimEnd('/')}/$normalized"
  }
}
