package xyz.block.trailblaze.cli.inprocess

import picocli.CommandLine.Command
import picocli.CommandLine.Option
import xyz.block.trailblaze.cli.TrailblazeExitCode
import xyz.block.trailblaze.host.WorkspaceTypeScriptSetup
import xyz.block.trailblaze.inprocess.InProcessShellPackager
import xyz.block.trailblaze.scripting.LazyYamlScriptedToolRegistration
import xyz.block.trailblaze.ui.TrailblazeDesktopUtil
import xyz.block.trailblaze.util.Console
import java.io.File
import java.io.IOException
import java.util.concurrent.Callable

/**
 * Retarget a prebuilt in-process shell APK at one app: stamp its `android:targetPackage`, inject the
 * trails / target config / tool bundles it should carry, and re-sign with the app's key.
 *
 * Pipeline order is fixed — stamp, inject, then sign — and signing is last so that a team holding
 * the app's key can run this command themselves: everything before the signature is content they can
 * inspect, and the signature covers all of it.
 */
@Command(
  name = "make-test-apk",
  mixinStandardHelpOptions = true,
  description = [
    "Retarget a prebuilt in-process shell APK at one app and sign it with that app's key. " +
      "Stamps the instrumentation's target package, injects trails / target config / scripted-tool " +
      "bundles, then signs. Writes a build record beside the output APK.",
  ],
)
class MakeTestApkCommand : Callable<Int> {

  @Option(
    names = ["--shell"],
    required = true,
    paramLabel = "<apk>",
    description = [
      "Prebuilt Trailblaze in-process shell test APK. Required, and no release publishes one yet: " +
        "build it from a framework checkout with scripts/build-inprocess-shell.sh and hand the " +
        "APK to whoever runs this command.",
    ],
  )
  lateinit var shellApk: File

  @Option(
    names = ["--target-package"],
    required = true,
    paramLabel = "<applicationId>",
    description = [
      "The app's applicationId — the package the instrumentation is stamped to attach to. Must " +
        "match the target evidence, so a wrong value is refused rather than signed.",
    ],
  )
  lateinit var targetPackage: String

  @Option(
    names = ["--out", "-o"],
    paramLabel = "<apk>",
    description = ["Output APK. Defaults to <target-package>-trailblaze-test.apk in the CWD."],
  )
  var outputApk: File? = null

  @Option(
    names = ["--keystore"],
    required = true,
    paramLabel = "<file>",
    description = ["Keystore holding the app's signing key (JKS or PKCS12)."],
  )
  lateinit var keystore: File

  @Option(
    names = ["--alias"],
    required = true,
    paramLabel = "<alias>",
    description = ["Key alias inside --keystore."],
  )
  lateinit var keyAlias: String

  @Option(
    names = ["--app-apk"],
    paramLabel = "<apk>",
    description = [
      "The app's APK, read for its certificate digest and debuggable flag. One of --app-apk or " +
        "--fingerprint is required — signing blind is refused.",
    ],
  )
  var appApk: File? = null

  @Option(
    names = ["--fingerprint"],
    paramLabel = "<yaml>",
    description = [
      "A `package:`/`certificate_sha256:`/`debuggable:` description of the app, for a host the " +
        "app's APK never reaches. Carries the same two guard inputs --app-apk would be read for.",
    ],
  )
  var fingerprintFile: File? = null

  @Option(
    names = ["--release"],
    description = [
      "Allow a non-debuggable target. Off by default: an instrumentation cannot attach to a " +
        "non-debuggable app on an ordinary device, and the usual cause of one is the wrong APK.",
    ],
  )
  var release: Boolean = false

  @Option(
    names = ["--allow-runtime-tool-source"],
    description = [
      "Bake `allow_runtime_tool_source: true` into the injected --target-config, letting this APK " +
        "load scripted-tool bundles pushed to /data/local/tmp at run time instead of the ones " +
        "packaged in it. Off by default, and written before signing either way, so the signature " +
        "records the choice and a key-ceremony APK cannot be turned into one by omission.",
    ],
  )
  var allowRuntimeToolSource: Boolean = false

  @Option(
    names = ["--target-config"],
    paramLabel = "<yaml>",
    description = [
      "Target config to inject (`id:`, `display_name:`, `platforms:`, `tools:`). Without one the " +
        "APK runs framework primitives only — no scripted tools.",
    ],
  )
  var targetConfig: File? = null

  @Option(
    names = ["--trailmap"],
    paramLabel = "<dir>",
    description = [
      "Trailmap directory whose name is the trailmap id and which holds a tools/ subtree. Repeat " +
        "for several. A tools/ of pre-built .bundle.js files needs no tooling; TypeScript sources " +
        "are bundled here, which needs esbuild.",
    ],
  )
  var trailmapDirs: MutableList<File> = mutableListOf()

  @Option(
    names = ["--trail"],
    paramLabel = "<file>",
    description = [
      "A *.trail.yaml to inject. Repeat for several. The shell discovers them at run time and " +
        "reports one test per trail.",
    ],
  )
  var trails: MutableList<File> = mutableListOf()

  @Option(
    names = ["--esbuild"],
    paramLabel = "<path>",
    description = ["esbuild binary, when --trailmap carries TypeScript and esbuild is not on PATH."],
  )
  var esbuild: File? = null

  override fun call(): Int {
    // Checked before the password prompt: a mistyped --shell is the common mistake here, and
    // learning about it after typing a keystore password is a worse way to learn it.
    val missing = buildList {
      add("--shell" to shellApk)
      add("--keystore" to keystore)
      appApk?.let { add("--app-apk" to it) }
      fingerprintFile?.let { add("--fingerprint" to it) }
      targetConfig?.let { add("--target-config" to it) }
      esbuild?.let { add("--esbuild" to it) }
      trails.forEach { add("--trail" to it) }
    }.firstOrNull { (_, file) -> !file.isFile }
    if (missing != null) {
      Console.error("${missing.first} is not a readable file: ${missing.second}")
      return TrailblazeExitCode.MISUSE.code
    }

    val storePassword = readPassword(
      envVar = KEYSTORE_PASSWORD_ENV,
      prompt = "Keystore password for ${keystore.name}: ",
    ) ?: return TrailblazeExitCode.MISUSE.code
    // A keystore whose key password equals the store password is the common case (every Android
    // debug keystore), so an absent key password falls back rather than prompting twice.
    val keyPassword = readPassword(
      envVar = KEY_PASSWORD_ENV,
      prompt = null,
    ) ?: storePassword

    val out = outputApk ?: File("$targetPackage-trailblaze-test.apk")
    val request = InProcessShellPackager.Request(
      shellApk = shellApk,
      outputApk = out.absoluteFile,
      targetPackage = targetPackage,
      keystore = keystore,
      keyAlias = keyAlias,
      storePassword = storePassword,
      keyPassword = keyPassword,
      appApk = appApk,
      fingerprintFile = fingerprintFile,
      release = release,
      allowRuntimeToolSource = allowRuntimeToolSource,
      targetConfig = targetConfig,
      trailmapDirs = trailmapDirs.toList(),
      trails = trails.toList(),
      // Resolved here, not inside the packager, so a host with no esbuild still works for the
      // pre-built-bundle inputs and only fails when something actually needs bundling.
      esbuildBinary = esbuild ?: LazyYamlScriptedToolRegistration.resolveEsbuildBinary(),
      inProcessSdkEntry = resolveInProcessSdkEntry(),
    )

    val result = try {
      InProcessShellPackager.make(request)
    } catch (e: IllegalArgumentException) {
      Console.error(e.message ?: "make-test-apk refused this request.")
      return TrailblazeExitCode.MISUSE.code
    } catch (e: IllegalStateException) {
      Console.error(e.message ?: "make-test-apk refused this request.")
      return TrailblazeExitCode.MISUSE.code
    } catch (e: IOException) {
      // Every file this command reads came from an argument, so an unreadable one is a bad
      // argument — not the infrastructure failure that exit code would report. A malformed APK
      // arrives as ZipException, a subclass, and is the same kind of mistake.
      Console.error("make-test-apk could not read an input: ${describe(e)}")
      return TrailblazeExitCode.MISUSE.code
    } catch (e: Exception) {
      Console.error("make-test-apk failed: ${describe(e)}")
      return TrailblazeExitCode.INFRA_FAILED.code
    } finally {
      // The passwords are gone from this process the moment they are no longer needed. A CharArray
      // that stays live is readable in a heap dump, and a signing key's password is exactly what a
      // heap dump should not hand over.
      storePassword.fill('\u0000')
      keyPassword.fill('\u0000')
    }

    result.warnings.forEach { Console.error("Warning: $it") }
    Console.info("Wrote ${result.outputApk}")
    Console.info("Build record ${result.buildRecordFile}")
    with(result.buildRecord) {
      Console.info("  target package         $targetPackage")
      Console.info("  target evidence        $targetEvidence")
      Console.info("  shell version          ${shellVersion ?: "(none recorded in the shell)"}")
      Console.info("  injected trails        ${injectedTrails.size}")
      Console.info("  injected tool bundles  ${injectedToolBundles.size}")
      // Read off the record, which read it off the injected bytes — printing the flag here would
      // make this line agree with the command even when the APK does not.
      Console.info("  runtime tool source    ${if (allowRuntimeToolSource) "ALLOWED" else "off"}")
    }
    return TrailblazeExitCode.SUCCESS.code
  }

  /**
   * Renders an exception as its message plus its cause chain.
   *
   * The interesting sentence is usually several `cause`s down — `apksig` and the zip reader both
   * wrap, and the outermost `message` is often null or says only what layer failed.
   */
  private fun describe(e: Throwable): String = generateSequence(e) { it.cause }
    .map { it.message?.takeIf(String::isNotBlank) ?: it::class.java.simpleName }
    .distinct()
    .joinToString(": ")

  /**
   * Reads a password from the environment, falling back to an interactive prompt.
   *
   * Never from argv: a command line is visible to every process on the host via `ps`, ends up in
   * shell history, and is echoed by CI logs. A null [prompt] means the caller has a fallback and no
   * prompt should be shown.
   */
  private fun readPassword(envVar: String, prompt: String?): CharArray? {
    System.getenv(envVar)?.takeIf { it.isNotEmpty() }?.let { return it.toCharArray() }
    if (prompt == null) return null
    val console = System.console()
    if (console == null) {
      Console.error(
        "No $envVar in the environment and no terminal to prompt on. Set $envVar (and " +
          "$KEY_PASSWORD_ENV when the key password differs) before running. Passwords are " +
          "deliberately not accepted as arguments — a command line is visible to other processes " +
          "and lands in shell history and CI logs.",
      )
      return null
    }
    val entered = console.readPassword(prompt)
    if (entered == null || entered.isEmpty()) {
      Console.error("No password entered.")
      return null
    }
    return entered
  }

  companion object {
    const val KEYSTORE_PASSWORD_ENV: String = "TRAILBLAZE_INPROCESS_KEYSTORE_PASSWORD"
    const val KEY_PASSWORD_ENV: String = "TRAILBLAZE_INPROCESS_KEY_PASSWORD"

    /**
     * What `@trailblaze/scripting` resolves to when a `--trailmap` carries TypeScript.
     *
     * The source-tree resolver answers from `TRAILBLAZE_SDK_DIR` or a checkout above the CWD, and
     * an installed CLI has neither — the installer lays down a JAR and a launcher. Without the
     * second tier, the one input `make-test-apk` is meant to accept from a team that has no
     * framework checkout is the one it cannot bundle. The SDK is pinned by the framework, so this
     * JAR's copy is the right thing to resolve against; it is extracted once and reused.
     */
    internal fun resolveInProcessSdkEntry(
      sourceTreeEntry: File? = LazyYamlScriptedToolRegistration.resolveInProcessSdkEntry(),
      cacheRoot: File = File(
        TrailblazeDesktopUtil.getDefaultAppDataDirectory(),
        TrailblazeDesktopUtil.SDK_CACHE_SUBDIR,
      ),
    ): File? = sourceTreeEntry ?: WorkspaceTypeScriptSetup.frameworkSdkRuntimeEntry(cacheRoot)
  }
}
