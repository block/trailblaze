package xyz.block.trailblaze.inprocess

import kotlinx.coroutines.runBlocking
import xyz.block.trailblaze.config.AppTargetYamlConfig
import xyz.block.trailblaze.inprocess.apk.ApkReadException
import xyz.block.trailblaze.inprocess.apk.AppManifestProviders
import xyz.block.trailblaze.config.TrailblazeConfigYaml
import xyz.block.trailblaze.config.project.TrailblazeProjectConfigLoader
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.scripting.DaemonScriptedToolBundler
import xyz.block.trailblaze.surveys.SurveyBundles
import java.io.File
import java.security.MessageDigest

/**
 * Turns a prebuilt generic in-process shell APK into one targeting a specific app: stamp, inject,
 * sign — in that order, always.
 *
 * **Sign is last, and nothing may run after it.** That ordering is what lets a key-custody team run
 * this command themselves: everything before signing is content assembly they can inspect, and the
 * signature covers all of it. A post-sign mutation would invalidate the signature or, worse, ship
 * content the signature does not cover.
 *
 * Nothing here needs the Android SDK, `aapt2`, `zipalign` or Gradle — see [AndroidBinaryXml],
 * [ApkZipRewriter] and [ApkSigning] for how each step avoids them. It does need `esbuild` for the
 * one input that is TypeScript source rather than a pre-built bundle.
 */
object InProcessShellPackager {

  const val MANIFEST_ENTRY: String = "AndroidManifest.xml"
  const val SHELL_VERSION_ENTRY: String = "assets/trailblaze/inprocess-shell.properties"
  /**
   * Asset directory injected target configs land in. Must match
   * `InjectedTrailAssets.TARGETS_ASSET_DIR` in `:trailblaze-android-test` — which names the same
   * directory without the `assets/` prefix, because it reads through the AssetManager while this
   * writes zip entries. SISTER-IMPL-TAG: in-process-targets-asset-dir.
   */
  const val TARGETS_ASSET_DIR: String = "assets/trails/config/targets"

  /**
   * Asset directory injected trails land in. Must match
   * `InjectedTrailAssets.INJECTED_TRAILS_CLASS_DIR` in `:trailblaze-android-test` — the shell finds
   * its trails by that name at runtime, and a rename on one side silently produces an APK that runs
   * zero trails.
   */
  const val INJECTED_TRAILS_ASSET_DIR: String = "assets/trails/InjectedTrailsLongTest"

  /**
   * Entry names that look like a key store. Covers every format [ApkSigning] can load plus the
   * conventional PKCS12 suffixes, because "the CLI accepts PKCS12" and "the leak guard only knows
   * about JKS" would be a guard that stops exactly the file types nobody uses.
   */
  private val KEYSTORE_ENTRY = Regex("(?i)\\.(keystore|jks|p12|pfx|bks)$")

  /** `0xFEEDFEED`, the JKS/JCEKS/BKS magic — an unambiguous key store whatever it was named. */
  private val JKS_MAGIC = byteArrayOf(0xFE.toByte(), 0xED.toByte(), 0xFE.toByte(), 0xED.toByte())

  /**
   * The target config's runtime-tool-source opt-in, matching
   * `AppTargetYamlConfig.allowRuntimeToolSource`'s `@SerialName`. SISTER-IMPL-TAG:
   * allow-runtime-tool-source-key.
   */
  private const val ALLOW_RUNTIME_TOOL_SOURCE_KEY = "allow_runtime_tool_source"

  /**
   * Shell-properties key attesting the shell packages the runtime-tool-source loader. Written by
   * `inprocess-shell/build.gradle.kts`. SISTER-IMPL-TAG: runtime-tool-source-shell-capability.
   */
  private const val RUNTIME_TOOL_SOURCE_CAPABILITY_KEY = "runtimeToolSource"

  /**
   * Shell-properties key attesting the shell installs the packaged-survey source on the device.
   * Written by `inprocess-shell/build.gradle.kts`. SISTER-IMPL-TAG: packaged-surveys-shell-capability.
   */
  private const val PACKAGED_SURVEYS_CAPABILITY_KEY = "packagedSurveys"

  /** The key as a top-level line, for telling "the caller wrote it" from "we appended it". */
  private val ALLOW_RUNTIME_TOOL_SOURCE_KEY_LINE =
    Regex("(?m)^$ALLOW_RUNTIME_TOOL_SOURCE_KEY\\s*:")

  data class Request(
    val shellApk: File,
    val outputApk: File,
    val targetPackage: String,
    val keystore: File,
    val keyAlias: String,
    val storePassword: CharArray,
    val keyPassword: CharArray,
    /** Target evidence, the bytes. At least one of this and [fingerprintFile] is required. */
    val appApk: File? = null,
    /** Target evidence, a description of the app, for hosts the app APK never reaches. */
    val fingerprintFile: File? = null,
    val release: Boolean = false,
    /**
     * Bakes `allow_runtime_tool_source: true` into [targetConfig] before signing. Off by default:
     * a signing run that never says the words produces an APK that only ever runs the tool code it
     * was built with.
     */
    val allowRuntimeToolSource: Boolean = false,
    val targetConfig: File? = null,
    /** Trailmap directories, each containing a `tools/` subtree of `.bundle.js` and/or `.ts`. */
    val trailmapDirs: List<File> = emptyList(),
    val trails: List<File> = emptyList(),
    /** Resolved by the caller; only needed when a trailmap carries TypeScript source. */
    val esbuildBinary: File? = null,
    val inProcessSdkEntry: File? = null,
  )

  data class Result(
    val outputApk: File,
    val buildRecordFile: File,
    val buildRecord: InProcessBuildRecord,
    /** Things the caller should see but that are not grounds to refuse. Printed by the CLI. */
    val warnings: List<String> = emptyList(),
  )

  fun make(request: Request): Result {
    val shellManifest = ApkZipRewriter.readEntry(request.shellApk, MANIFEST_ENTRY)
      ?: error(
        "${request.shellApk} has no $MANIFEST_ENTRY entry, so it is not an APK. --shell takes a " +
          "prebuilt Trailblaze in-process shell test APK.",
      )

    refuseAliasedPaths(request)
    val shellProperties = readShellProperties(request.shellApk)
    val warnings = mutableListOf<String>()
    checkShellCompileFloor(shellProperties, warnings)
    val evidence = resolveTargetEvidence(request)
    runGuards(request, evidence)
    warnAndroidxStartupTarget(request, warnings)

    val stampedManifest =
      AndroidBinaryXml.stampInstrumentationTargetPackage(shellManifest, request.targetPackage)

    // The opt-in lives in the target config, so there has to be one. Silently doing nothing would
    // hand back an APK that ignores every pushed bundle while the command line said otherwise.
    require(!request.allowRuntimeToolSource || request.targetConfig != null) {
      "--allow-runtime-tool-source needs a --target-config to write it into. The opt-in is a field " +
        "on the injected target config, which is how the signature covers it; with no config there " +
        "is nothing signed that says the APK may read pushed tool bundles."
    }
    // The opt-in is a promise about the shell's code, so the shell must attest it packages the
    // loader. A shell built before the runtime tool source existed would otherwise sign
    // `allow_runtime_tool_source: true` and then ignore every pushed bundle — a mismatch nothing
    // on the device reports. SISTER-IMPL-TAG: runtime-tool-source-shell-capability.
    require(!request.allowRuntimeToolSource || shellProperties[RUNTIME_TOOL_SOURCE_CAPABILITY_KEY] == "true") {
      "--allow-runtime-tool-source needs a shell that packages the runtime tool source loader, and " +
        "${request.shellApk} does not record that capability " +
        "($RUNTIME_TOOL_SOURCE_CAPABILITY_KEY in $SHELL_VERSION_ENTRY). Rebuild it with " +
        "scripts/build-inprocess-shell.sh from a checkout that has the runtime tool source."
    }
    val injectedTargetConfig =
      request.targetConfig?.let { readTargetConfig(it, request.allowRuntimeToolSource) }
    val toolBundles = collectToolBundles(request)
    // A shell built before packaged surveys would carry them and still fail every ctx.surveys call,
    // so they are left out and the reason named. Not a failure: a trailmap's surveys are packaged
    // implicitly, and an APK whose trails never ask for one is still a good APK.
    val shellLoadsSurveys = shellProperties[PACKAGED_SURVEYS_CAPABILITY_KEY] == "true"
    if (!shellLoadsSurveys && request.trailmapDirs.any { surveyFiles(File(it, "surveys")).isNotEmpty() }) {
      warnings += "Surveys not packaged: ${request.shellApk} does not record that it loads them " +
        "($PACKAGED_SURVEYS_CAPABILITY_KEY in $SHELL_VERSION_ENTRY), so ctx.surveys fails on the device. " +
        "Rebuild the shell with scripts/build-inprocess-shell.sh."
    }
    val surveys = if (shellLoadsSurveys) collectSurveys(request, warnings) else emptyMap()
    val trailAssets = collectTrails(request.trails)

    val additions = LinkedHashMap<String, ByteArray>()
    injectedTargetConfig?.let { additions["$TARGETS_ASSET_DIR/${it.id}.yaml"] = it.bytes }
    additions += toolBundles
    additions += surveys
    additions += trailAssets

    val staged = stagedFileFor(request.outputApk)
    val recordFile = buildRecordFileFor(request.outputApk)
    // One scope covering the signature AND the record: an APK is only an output of this command
    // once the record beside it says what it is.
    try {
      ApkZipRewriter.rewrite(
        input = request.shellApk,
        output = staged,
        replacements = mapOf(MANIFEST_ENTRY to stampedManifest),
        additions = additions,
      )
      // Before signing, so a run that would have leaked a key never produces a signed artifact.
      refuseKeystoreInApk(staged)
      ApkSigning.sign(
        input = staged,
        output = request.outputApk,
        keystore = request.keystore,
        storePassword = request.storePassword,
        keyAlias = request.keyAlias,
        keyPassword = request.keyPassword,
      )
      val record = InProcessBuildRecord(
        apk = request.outputApk.name,
        shellVersion = shellProperties["shellVersion"]?.takeIf { it.isNotEmpty() },
        shellSha256 = sha256Hex(request.shellApk),
        targetPackage = request.targetPackage,
        targetEvidence = evidence.description,
        signingCertificateSha256 = evidence.signingCertificateSha256,
        releaseMode = request.release,
        targetConfigId = injectedTargetConfig?.id,
        trailmapRevision = (toolBundles + surveys).takeIf { it.isNotEmpty() }?.let { contentDigest(it) },
        injectedTrails = trailAssets.keys.toList(),
        injectedToolBundles = toolBundles.keys.toList(),
        // Read back out of the bytes that were injected, never from the flag a second time.
        allowRuntimeToolSource = injectedTargetConfig?.allowRuntimeToolSource ?: false,
      )
      recordFile.writeText(
        TrailblazeConfigYaml.instance.encodeToString(InProcessBuildRecord.serializer(), record) + "\n",
      )
      return Result(request.outputApk, recordFile, record, warnings)
    } catch (e: Throwable) {
      // apksig opens its output read/write and truncates it before writing, so a failure part way
      // through leaves a partial APK at --out. A failure AFTER it leaves a whole signed one, with
      // no record saying what it targets or what it carries. Either way someone will install that
      // file, so a run that did not finish leaves nothing claiming to be its output.
      //
      // Only files: neither path is deleted if something else already occupies it as a directory.
      request.outputApk.takeIf { it.isFile }?.delete()
      recordFile.takeIf { it.isFile }?.delete()
      throw e
    } finally {
      staged.delete()
    }
  }

  // --- guards --------------------------------------------------------------------------------

  private class TargetEvidence(
    val fingerprint: AppFingerprint,
    val description: String,
    val signingCertificateSha256: String,
  )

  private fun resolveTargetEvidence(request: Request): TargetEvidence {
    val signingDigest = ApkSigning.keystoreCertificateSha256Digest(
      keystore = request.keystore,
      storePassword = request.storePassword,
      keyAlias = request.keyAlias,
    )
    val fromApk = request.appApk?.let { AppFingerprint.ofApk(it) }
    val fromFile = request.fingerprintFile?.let { AppFingerprint.load(it) }
    if (fromApk != null && fromFile != null) {
      // A stale fingerprint next to the real bytes is worth refusing: the whole point of the
      // fingerprint path is that it stands in for the APK, and one that disagrees with it does not.
      require(fromFile.packageName == fromApk.packageName &&
        fromFile.certificateSha256 == fromApk.certificateSha256 &&
        fromFile.debuggable == fromApk.debuggable
      ) {
        "--fingerprint ${request.fingerprintFile} disagrees with --app-apk ${request.appApk}: " +
          "fingerprint says package=${fromFile.packageName} debuggable=${fromFile.debuggable}, " +
          "the APK says package=${fromApk.packageName} debuggable=${fromApk.debuggable}. " +
          "Regenerate the fingerprint from these bytes, or drop --fingerprint."
      }
    }
    val fingerprint = fromApk ?: fromFile ?: error(
      "No target evidence. Pass --app-apk <apk> (the app's bytes) or --fingerprint <yaml> (a " +
        "`package:`/`certificate_sha256:`/`debuggable:` description of the app, for hosts the APK " +
        "never reaches). Without one of them this command would " +
        "sign an APK for a target it knows nothing about, and the failure would surface as a " +
        "refused install or a silent non-attach on a device.",
    )
    val description = when {
      fromApk != null -> "app-apk:${request.appApk!!.name}"
      else -> "fingerprint:${request.fingerprintFile!!.name}"
    }
    return TargetEvidence(fingerprint, description, signingDigest)
  }

  private fun runGuards(request: Request, evidence: TargetEvidence) {
    val fingerprint = evidence.fingerprint
    require(request.targetPackage == fingerprint.packageName) {
      "--target-package ${request.targetPackage} is not the package the target evidence " +
        "describes (${fingerprint.packageName}). Stamping a package the evidence does not cover " +
        "means the guards below checked a different app than the one this APK would attach to."
    }
    // Android's checkSignatures compares the FULL signer set of both packages, and this command
    // produces exactly one signer. A target with several current signers can therefore never match
    // what we emit, however well one of its certificates lines up with the keystore.
    require(fingerprint.certificateSha256.size == 1) {
      "${fingerprint.packageName} has ${fingerprint.certificateSha256.size} signing certificates " +
        "(${fingerprint.certificateSha256.joinToString()}). This command signs with a single key, " +
        "and Android requires an instrumentation's whole signer set to match its target's, so no " +
        "single-signer APK can attach to a multi-signer app. Sign the shell with the same signer " +
        "set through your own signing pipeline instead."
    }
    require(evidence.signingCertificateSha256 == fingerprint.certificateSha256.single()) {
      "Signing key mismatch. The keystore alias '${request.keyAlias}' signs with certificate " +
        "${evidence.signingCertificateSha256}, and ${fingerprint.packageName} is signed with " +
        "${fingerprint.certificateSha256.single()}. Android only lets an instrumentation " +
        "attach to a target app when both APKs carry the SAME signature, so this APK could be " +
        "installed and would then fail to attach."
    }
    require(fingerprint.debuggable || request.release) {
      "${fingerprint.packageName} is not debuggable. That is not itself a bar to attaching — the " +
        "signature check above is the real gate, and this command signs with the target's own key " +
        "— but it has to be explicit, because the usual cause is pointing at the wrong APK. Pass " +
        "--release when a non-debuggable target is what you meant."
    }
  }

  /**
   * Warns when the target declares `androidx.startup`'s `InitializationProvider`.
   *
   * A generic shell cannot attach to such an app today: `AppInitializer` is a process-wide
   * singleton, and with no `targetProjectPath` for AGP to dedupe against, the shell packages its
   * own `startup-runtime`, whose copy wins class loading and latches the "discovered" flag first.
   * The app's own initializers then never run and the process dies in `AppUnderTestLauncher`,
   * before any Trailblaze session starts
   * (`docs/internal/devlog/2026-09-01-inprocess-runtime-tool-source.md`).
   *
   * Only the `--app-apk` path can see this. A fingerprint carries the signing facts a key-custody
   * team can state about an app whose bytes never travel; it says nothing about the manifest's
   * components. So this is a warning rather than a refusal — refusing would fail closed on every
   * fingerprint-only caller, whose silence is "not recorded", not "no androidx.startup".
   */
  private fun warnAndroidxStartupTarget(request: Request, warnings: MutableList<String>) {
    val appApk = request.appApk ?: return
    val startupProviders = try {
      AppManifestProviders.of(appApk).filter { it.androidxStartup }
    } catch (e: ApkReadException) {
      warnings += "Could not read $appApk's providers, so this APK is unchecked for the " +
        "androidx.startup limitation described in --help: ${e.message}"
      return
    }
    if (startupProviders.isEmpty()) return
    warnings += "${request.targetPackage} declares androidx.startup " +
      "(${startupProviders.joinToString { it.className }}), which a generic shell cannot attach " +
      "to yet: the shell's duplicate AppInitializer latches first, the app's own initializers " +
      "never run, and the process crashes before the first session starts. This APK is produced " +
      "anyway — it is the packaging that is fine, not the run. Use the Gradle in-process module " +
      "for this app, which names it as targetProjectPath so AGP dedupes startup-runtime out of " +
      "the test APK."
  }

  /**
   * Refuses to emit if a key store landed inside the APK. An in-process shell is content-assembled
   * from directories a caller names, so a `keys/` folder one level too high, or a trailmap dir with
   * a signing key beside its tools, ends with a private key packaged into an artifact that gets
   * handed around. Checked on the FULL entry list, since the shell could carry one too.
   *
   * Matched by name for every format the signer accepts, and by content for the JKS family, whose
   * magic number is unambiguous. PKCS12 has no such tell — its DER header is shared with ordinary
   * certificate files, which a shell may legitimately carry — so a `.p12` renamed to something else
   * gets past this. That is the accepted limit: the failure this guard exists for is a key swept in
   * by a too-broad input path, and a swept-in file keeps its name.
   */
  /**
   * Refuses a shell built without the in-process compile floor.
   *
   * In-process, the APP's copies of shared libraries win in the one shared classloader, so a shell
   * whose trailblaze classes compiled against this repo's coroutines/Ktor rather than the floor era
   * links fine here and dies on the adopter's device with a `NoSuchMethodError` naming no version.
   * That is the single most expensive way to find out, and the shell records which way it was built
   * precisely so this host can find out instead.
   *
   * An ABSENT marker only warns: shells built before the marker existed are still usable, and
   * refusing them would make this guard a breaking change rather than a check.
   */
  private fun checkShellCompileFloor(shellProperties: Map<String, String>, warnings: MutableList<String>) {
    when (shellProperties["floored"]) {
      "true" -> Unit
      null -> warnings += "This shell records no compile-floor marker, so it predates the check. " +
        "If it was built without the compile-floor init script, a version " +
        "mismatch with the app will surface on device as a NoSuchMethodError. " +
        "scripts/build-inprocess-shell.sh builds a marked one."
      else -> error(
        "This shell was built WITHOUT the in-process compile floor, so the trailblaze classes it " +
          "packages link against versions the app under test may not ship. In-process the app's " +
          "copies win, and the mismatch surfaces on the device as a NoSuchMethodError that names " +
          "no version. Rebuild with scripts/build-inprocess-shell.sh.",
      )
    }
  }

  private fun refuseKeystoreInApk(apk: File) {
    val offenders = ApkZipRewriter.entryNamesMatching(apk, JKS_MAGIC.size) { name, prefix ->
      KEYSTORE_ENTRY.containsMatchIn(name) || prefix.contentEquals(JKS_MAGIC)
    }
    require(offenders.isEmpty()) {
      "Refusing to sign: these entries look like signing keys — ${offenders.joinToString()}. A " +
        "private key inside a test APK is readable by anyone the APK is handed to. Remove it from " +
        "the shell or from the injected inputs and re-run."
    }
  }

  /**
   * Refuses an output path that is also an input path.
   *
   * `apksig` opens its output read/write and truncates it to zero length before writing, so
   * `--out app.p12` does not fail — it destroys the signing key first and reports a signing error
   * afterwards. The staged and build-record paths are derived from the output, so they can collide
   * the same way. Canonical paths, so a symlink or a `./` prefix cannot route around it.
   */
  private fun refuseAliasedPaths(request: Request) {
    val outputs = linkedMapOf(
      "--out" to request.outputApk,
      "the staging APK (--out + \".unsigned\")" to stagedFileFor(request.outputApk),
      "the build record (--out with a .build-record.yaml suffix)" to buildRecordFileFor(request.outputApk),
    )
    val inputs = buildList {
      add("--shell" to request.shellApk)
      add("--keystore" to request.keystore)
      request.appApk?.let { add("--app-apk" to it) }
      request.fingerprintFile?.let { add("--fingerprint" to it) }
      request.targetConfig?.let { add("--target-config" to it) }
      request.trailmapDirs.forEach { add("--trailmap" to it) }
      request.trails.forEach { add("--trail" to it) }
    }
    for ((outputLabel, output) in outputs) {
      val canonicalOutput = output.canonicalFile
      for ((inputLabel, input) in inputs) {
        require(input.canonicalFile != canonicalOutput) {
          "$outputLabel would be written over $inputLabel ($input). Signing truncates its output " +
            "before writing, so this would destroy that file rather than fail. Pick an --out path " +
            "that is not one of the inputs."
        }
      }
    }
  }

  private fun stagedFileFor(outputApk: File): File =
    File(outputApk.absoluteFile.parentFile, "${outputApk.name}.unsigned")

  private fun buildRecordFileFor(outputApk: File): File =
    File(outputApk.absoluteFile.parentFile, "${outputApk.nameWithoutExtension}.build-record.yaml")

  // --- content assembly ----------------------------------------------------------------------

  private class InjectedTargetConfig(
    val id: String,
    val bytes: ByteArray,
    val allowRuntimeToolSource: Boolean,
  )

  /**
   * Validates the caller's target config, applies the runtime-tool-source opt-in, and injects the
   * result.
   *
   * Decoding is what supplies the `id` the asset is named after, and it fails here rather than on a
   * device if the file is not a target config at all. Otherwise the bytes that ship are the
   * caller's own: a decode-and-re-encode round trip would silently drop any field this build of the
   * decoder does not know, and the signature would then cover a config the caller never wrote. So
   * the opt-in is APPENDED as one line rather than re-encoded, and the emitted bytes are then
   * decoded again — if they do not read back as the caller asked for, nothing is signed.
   *
   * The flag is the ONLY way to turn the opt-in on. A config that declares it while the flag is
   * absent is refused rather than honored, so a key-ceremony run cannot enable the runtime tool
   * source by handing over a config nobody re-read.
   */
  private fun readTargetConfig(configFile: File, allowRuntimeToolSource: Boolean): InjectedTargetConfig {
    require(configFile.isFile) { "--target-config file not found: $configFile" }
    val text = configFile.readText()
    val decoded = decodeTargetConfig(configFile, text)
    // `id` becomes both the asset's file name and, on device, a TrailblazeHostAppTarget id — whose
    // constructor rejects anything outside this pattern. Unchecked, `id: my.app` signs cleanly and
    // then throws during test initialization, which is the most expensive place to find out.
    require(TrailblazeHostAppTarget.isValidId(decoded.id)) {
      "Target config $configFile has id '${decoded.id}', which is not a usable target id " +
        "(letters, digits, hyphens and underscores only). It names the injected asset and becomes " +
        "the target id on device, where it would fail during test initialization rather than here."
    }
    val declaresOptIn = ALLOW_RUNTIME_TOOL_SOURCE_KEY_LINE.containsMatchIn(text)
    require(allowRuntimeToolSource || !decoded.allowRuntimeToolSource) {
      "$configFile sets `$ALLOW_RUNTIME_TOOL_SOURCE_KEY: true`, but --allow-runtime-tool-source " +
        "was not passed. That opt-in lets the signed APK run scripted-tool code pushed to the " +
        "device, so it has to be asked for on the command line — a config file alone cannot turn " +
        "it on. Pass the flag if you mean it, or remove the line."
    }
    require(!allowRuntimeToolSource || !declaresOptIn || decoded.allowRuntimeToolSource) {
      "--allow-runtime-tool-source was passed, but $configFile already sets " +
        "`$ALLOW_RUNTIME_TOOL_SOURCE_KEY:` to something else. Remove that line and let the flag " +
        "write it, or drop the flag."
    }
    val bytes = when {
      !allowRuntimeToolSource || decoded.allowRuntimeToolSource -> text.toByteArray()
      // One appended top-level key, so every other byte the caller wrote survives verbatim.
      else -> (text.trimEnd('\n') + "\n$ALLOW_RUNTIME_TOOL_SOURCE_KEY: true\n").toByteArray()
    }
    // Read the emitted bytes back. This is what makes the build record's value a property of the
    // APK rather than a second copy of the flag, and it is also what catches an append that landed
    // somewhere a YAML parser reads differently than a human would (a multi-document file, say).
    val reread = decodeTargetConfig(configFile, bytes.decodeToString())
    check(reread.allowRuntimeToolSource == allowRuntimeToolSource) {
      "Refusing to sign: the target config that would be injected reads back with " +
        "`$ALLOW_RUNTIME_TOOL_SOURCE_KEY: ${reread.allowRuntimeToolSource}`, but " +
        "--allow-runtime-tool-source asked for $allowRuntimeToolSource. The signed APK would not " +
        "do what the command was told to do."
    }
    return InjectedTargetConfig(
      id = decoded.id,
      bytes = bytes,
      allowRuntimeToolSource = reread.allowRuntimeToolSource,
    )
  }

  private fun decodeTargetConfig(configFile: File, text: String): AppTargetYamlConfig = try {
    TrailblazeConfigYaml.instance.decodeFromString(AppTargetYamlConfig.serializer(), text)
  } catch (e: Exception) {
    throw IllegalArgumentException(
      "$configFile is not a Trailblaze target config (`id:` and `display_name:`, plus optional " +
        "`platforms:` / `tools:`). Cause: ${e.message}",
      e,
    )
  }

  private fun collectTrails(trails: List<File>): Map<String, ByteArray> {
    val byAsset = LinkedHashMap<String, ByteArray>()
    for (trail in trails) {
      require(trail.isFile) { "--trail file not found: $trail" }
      require(trail.name.endsWith(".trail.yaml")) {
        "--trail takes a `*.trail.yaml` file; got ${trail.name}. The shell finds its trails by " +
          "that suffix at runtime, so a differently named file would be injected and never run."
      }
      val assetPath = "$INJECTED_TRAILS_ASSET_DIR/${trail.name}"
      require(byAsset.put(assetPath, trail.readBytes()) == null) {
        "Two --trail inputs are both named ${trail.name}. Injected trails are flattened into one " +
          "asset directory, so their file names have to be unique."
      }
    }
    return byAsset
  }

  /**
   * Bundles or copies each trailmap's in-process scripted tools to the asset paths the on-device
   * resolver reads (`assets/trails/config/trailmaps/<id>/tools/<stem>.bundle.js`).
   *
   * A `.bundle.js` is taken as-is — that is the input that keeps the SDK-free claim honest, since it
   * needs no tooling at all. A `.ts` has to be bundled, which needs `esbuild` on the host.
   */
  private fun collectToolBundles(request: Request): Map<String, ByteArray> {
    val out = LinkedHashMap<String, ByteArray>()
    for (trailmapDir in request.trailmapDirs) {
      require(trailmapDir.isDirectory) { "--trailmap directory not found: $trailmapDir" }
      // The directory name goes straight into a zip entry path, and `File("x/..").name` is "..",
      // so an unvalidated id puts a traversal segment inside a SIGNED artifact. Same rule the
      // target config's id gets, for the same reason: it is an id, and it names an asset.
      val id = trailmapDir.canonicalFile.name
      require(TrailblazeHostAppTarget.isValidId(id)) {
        "--trailmap $trailmapDir resolves to the trailmap id '$id', which is not a usable id " +
          "(letters, digits, hyphens and underscores only). The directory name IS the trailmap id " +
          "and it names the injected asset path. Pass the trailmap directory itself, by a path " +
          "whose last segment is the id."
      }
      val toolsDir = File(trailmapDir, "tools")
      require(toolsDir.isDirectory) {
        "--trailmap $trailmapDir has no tools/ subdirectory. Pass the trailmap directory itself " +
          "(the one whose name is the trailmap id), not its tools/ folder."
      }

      val prebuilt = toolsDir.walkTopDown()
        .filter { it.isFile && it.name.endsWith(".bundle.js") }
        .sortedBy { it.relativeTo(toolsDir).invariantSeparatorsPath }
      for (bundle in prebuilt) {
        val stem = bundle.relativeTo(toolsDir).invariantSeparatorsPath.removeSuffix(".bundle.js")
        putBundle(out, id, stem, bundle.readBytes(), bundle)
      }

      val sources = inProcessToolSources(toolsDir)
        .filter { source ->
          // A pre-built bundle beside the source wins: it is what an adopter shipped, and
          // re-bundling could produce different bytes from a different esbuild.
          val stem = source.relativeTo(toolsDir).invariantSeparatorsPath.removeSuffix(".ts")
          assetPathFor(id, stem) !in out
        }
      if (sources.isEmpty()) continue

      val esbuild = request.esbuildBinary ?: error(
        "$toolsDir has TypeScript scripted tools (${sources.joinToString { it.name }}) and no " +
          "pre-built .bundle.js beside them, so they have to be bundled — which needs esbuild on " +
          "this host. Either install esbuild (`brew install esbuild`, or an `npm i esbuild` whose " +
          "node_modules/.bin is reachable) and pass --esbuild <path> if it is not on PATH, or pass " +
          "a trailmap directory whose tools/ already holds .bundle.js files, which needs no tooling.",
      )
      val bundler = DaemonScriptedToolBundler(
        esbuildBinary = esbuild,
        inProcessSdkEntryOverride = request.inProcessSdkEntry,
        // The request's entry is already resolved two tiers deep (gated source tree, else the SDK
        // extracted from this JAR), so a null is a verdict. A walk-up from esbuild would re-select
        // the source tree that verdict rejected, since esbuild is normally its own devDependency.
        allowLegacyEsbuildWalkup = false,
      )
      for (source in sources) {
        val relStem = source.relativeTo(toolsDir).invariantSeparatorsPath.removeSuffix(".ts")
        // Every export, not one named after the file: a tool source may declare several
        // `export const <name> = trailblaze.tool(...)` in one file, and nothing names them at
        // packaging time. This is also the form the Gradle in-process bundler emits, and both feed
        // the same on-device resolver. SISTER-IMPL-TAG: in-process-multi-export-registration.
        val bundled = runBlocking { bundler.bundleEveryExport(source) }
        putBundle(out, id, relStem, bundled.readBytes(), source)
      }
    }
    return out
  }

  /**
   * Packages each trailmap's `surveys/` for `ctx.surveys` on the device, laid out as
   * [SurveyBundles.PACKAGED_DIR] describes: every survey file, and beside it its bundle or why it
   * could not be bundled. SISTER-IMPL-TAG: packaged-survey-layout — the Android Gradle plugin's
   * `registerTrailmapSurveyBundles` writes the same layout.
   *
   * Never fails the run: a survey that runs only after the session may import what the sandbox
   * lacks, and is skipped here; any other that cannot be bundled is reported to a trail that asks
   * for it, and named in [warnings].
   */
  private fun collectSurveys(request: Request, warnings: MutableList<String>): Map<String, ByteArray> {
    val out = LinkedHashMap<String, ByteArray>()
    val bundler by lazy {
      request.esbuildBinary?.let {
        DaemonScriptedToolBundler(it, inProcessSdkEntryOverride = request.inProcessSdkEntry, allowLegacyEsbuildWalkup = false)
      }
    }
    for (trailmapDir in request.trailmapDirs) {
      val id = trailmapDir.canonicalFile.name
      val surveysDir = File(trailmapDir, "surveys")
      for (survey in surveyFiles(surveysDir)) {
        val rel = survey.relativeTo(surveysDir).invariantSeparatorsPath
        require(rel.split('/').none { it == ".." || it == "." }) {
          "Survey file '$rel' in trailmap '$id' escapes surveys/. An entry path with a traversal segment inside a signed APK is not something this command emits."
        }
        val asset = "assets/${SurveyBundles.PACKAGED_DIR}/trailmaps/$id/surveys/$rel"
        require(asset !in out) {
          "Two inputs both map to $asset (the second is $survey). Trailmap ids come from the " +
            "directory name, so passing the same trailmap twice does this."
        }
        val text = survey.readText()
        out[asset] = text.toByteArray()
        val exported = SurveyBundles.exportedSurveys(text)
        // The device answers for a survey that runs only after the session without a bundle.
        if (exported.isNotEmpty() && exported.all { it.requiresFinishedSession }) continue
        val failure = when (val b = bundler) {
          null -> "esbuild was not available when this test APK was made, so the survey file was not bundled. Pass --esbuild to make-test-apk."
          else -> try {
            out[asset + SurveyBundles.PACKAGED_BUNDLE_SUFFIX] = runBlocking { b.bundleSurvey(survey) }.readBytes()
            null
          } catch (e: Exception) {
            e.message.orEmpty()
          }
        } ?: continue
        out[asset + SurveyBundles.PACKAGED_ERROR_SUFFIX] = failure.toByteArray()
        warnings += "Survey file $id/surveys/$rel cannot run mid-trail on the device: ${failure.lineSequence().first()}"
      }
    }
    return out
  }

  /** Every survey file under [surveysDir], in path order. Mirrors the Android Gradle plugin's `surveyFiles`. */
  private fun surveyFiles(surveysDir: File): List<File> {
    if (!surveysDir.isDirectory) return emptyList()
    return surveysDir.walkTopDown()
      .onEnter { it == surveysDir || !(it.name.startsWith(".") || it.name == "node_modules") }
      .filter { it.isFile && SurveyBundles.isSurveyFile(it.name) }
      .sortedBy { it.relativeTo(surveysDir).invariantSeparatorsPath }
      .toList()
  }

  private fun putBundle(
    out: MutableMap<String, ByteArray>,
    trailmapId: String,
    stem: String,
    bytes: ByteArray,
    origin: File,
  ) {
    val assetPath = assetPathFor(trailmapId, stem)
    require(out.put(assetPath, bytes) == null) {
      "Two inputs both map to $assetPath (the second is $origin). Trailmap ids come from the " +
        "directory name, so passing the same trailmap twice does this."
    }
  }

  private fun assetPathFor(trailmapId: String, toolsRelativeStem: String): String {
    // A symlink under tools/ can point outside it, and walkTopDown follows one — so the stem is
    // caller-influenced even after the id is validated.
    require(toolsRelativeStem.split('/').none { it == ".." || it == "." }) {
      "Tool source path '$toolsRelativeStem' in trailmap '$trailmapId' escapes tools/. An entry " +
        "path with a traversal segment inside a signed APK is not something this command emits."
    }
    return "assets/trails/config/trailmaps/$trailmapId/tools/$toolsRelativeStem.bundle.js"
  }

  /**
   * The `.ts` files under [toolsDir] that are in-process scripted tools.
   *
   * SISTER-IMPL-TAG: trailmap-scripted-tool-discovery. Mirrors `inProcessToolSources` in the
   * `xyz.block.trailblaze.android-gradle` plugin, which decides what a Gradle-built in-process APK
   * carries. The plugin is a composite build with no Trailblaze dependencies on purpose, so the rule
   * cannot be shared as code — but the two must agree, because they produce the same asset paths for
   * the same runtime resolver.
   */
  private fun inProcessToolSources(toolsDir: File): List<File> {
    val toolExport = Regex("""trailblaze\s*\.\s*(?:tool|trailhead)\s*[<(]""")
    // Per `.ts`, whether every descriptor YAML for it (the one whose `script:` names it, or its
    // same-basename sibling when that names none) runs it as a subprocess.
    val allOwnersSubprocess = mutableMapOf<File, Boolean>()
    toolsDir.walkTopDown()
      .filter { f -> f.isFile && f.name.endsWith(".yaml") && OPERATIONAL_TOOL_YAML_SUFFIXES.none { f.name.endsWith(it) } }
      .forEach { yaml ->
        val keys = topLevelYamlValues(yaml.readText())
        // A YAML the loader can't decode as a descriptor describes nothing, so it claims no script.
        if (!isToolDescriptor(keys)) return@forEach
        val script = keys["script"]?.let(::yamlScalar) ?: (yaml.name.removeSuffix(".yaml") + ".ts")
        val ts = (File(script).takeIf { it.isAbsolute } ?: File(yaml.parentFile, script)).canonicalFile
        val subprocess = keys["runtime"]?.let(::yamlScalar) == "subprocess"
        allOwnersSubprocess[ts] = (allOwnersSubprocess[ts] ?: true) && subprocess
      }
    return toolsDir.walkTopDown()
      .filter { f ->
        f.isFile &&
          f.name.endsWith(".ts") &&
          !f.name.endsWith(".test.ts") &&
          !f.name.endsWith(".d.ts") &&
          (allOwnersSubprocess[f.canonicalFile]?.not() ?: toolExport.containsMatchIn(f.readText()))
      }
      .sortedBy { it.relativeTo(toolsDir).invariantSeparatorsPath }
      .toList()
  }

  /** Tool-directory YAMLs that are not tool descriptors. */
  private val OPERATIONAL_TOOL_YAML_SUFFIXES = TrailblazeProjectConfigLoader.OPERATIONAL_TOOL_YAML_SUFFIXES

  private val TOP_LEVEL_YAML_KEY = Regex("""^([A-Za-z_][\w-]*):(?:\s+(.*?))?\s*$""")
  private val DOUBLE_QUOTED_YAML = Regex("""^"((?:[^"\\]|\\.)*)"""")
  private val SINGLE_QUOTED_YAML = Regex("""^'((?:[^']|'')*)'""")

  private val BLOCK_SCALAR_HEADER = Regex("""^([|>])([1-9]?)([-+]?)([1-9]?)(?:\s+#.*)?$""")

  /**
   * Each top-level key of a YAML [text], mapped to its raw inline value with any trailing comment
   * dropped — `""` when the value is a nested block. A `|` or `>` block scalar maps to its decoded
   * content, re-quoted so [yamlScalar] reads it like any quoted value.
   */
  private fun topLevelYamlValues(text: String): Map<String, String> {
    val lines = text.lines()
    val values = mutableMapOf<String, String>()
    lines.forEachIndexed { index, line ->
      val match = TOP_LEVEL_YAML_KEY.matchEntire(line) ?: return@forEachIndexed
      val raw = match.groupValues[2]
      val quoted = raw.startsWith('"') || raw.startsWith('\'')
      val block = BLOCK_SCALAR_HEADER.matchEntire(raw)
      values[match.groupValues[1]] = when {
        quoted -> raw
        block != null -> "'" + blockScalar(block, lines.drop(index + 1)).replace("'", "''") + "'"
        else -> raw.substringBefore(" #").takeUnless { it.startsWith("#") }.orEmpty().trim()
      }
    }
    return values
  }

  /**
   * The content of the top-level block scalar whose [header] (`|`, `>-`, `|2+`, ...) precedes [following],
   * decoded per the YAML spec: `|` keeps line breaks, `>` folds them into spaces, and the chomping
   * indicator strips (`-`), keeps (`+`), or clips to one (default) the trailing breaks.
   */
  private fun blockScalar(header: MatchResult, following: List<String>): String {
    val (style, indentBefore, chomping, indentAfter) = header.destructured
    val explicitIndent = (indentBefore + indentAfter).toIntOrNull()
    val indent = explicitIndent
      ?: following.firstOrNull { it.isNotBlank() }?.let { it.length - it.trimStart(' ').length }?.takeIf { it > 0 }
      ?: return ""
    val body = following
      .takeWhile { it.isBlank() || it.length - it.trimStart(' ').length >= indent }
      .map { if (it.isBlank() && it.length <= indent) "" else it.substring(indent) }
    val content = body.dropLastWhile { it.isEmpty() }
    if (content.isEmpty()) return if (chomping == "+") "\n".repeat(body.size) else ""
    val text = if (style == "|") content.joinToString("\n") else foldBlockLines(content)
    return text + when (chomping) {
      "-" -> ""
      "+" -> "\n".repeat(body.size - content.size + 1)
      else -> "\n"
    }
  }

  /** Folded (`>`) block [lines] joined: a break between two plain lines is a space, a blank line a break. */
  private fun foldBlockLines(lines: List<String>): String {
    val folded = StringBuilder()
    var previous: String? = null
    var blankRun = 0
    fun moreIndented(line: String) = line.startsWith(' ') || line.startsWith('\t')
    for (line in lines) {
      if (line.isEmpty()) {
        blankRun++
        continue
      }
      folded.append(
        when {
          previous == null -> "\n".repeat(blankRun)
          moreIndented(previous) || moreIndented(line) -> "\n".repeat(blankRun + 1)
          blankRun == 0 -> " "
          else -> "\n".repeat(blankRun)
        },
      )
      folded.append(line)
      previous = line
      blankRun = 0
    }
    return folded.toString()
  }

  /** [raw] as a YAML string scalar, unquoted and without its comment; `null` for a list, map, block, or null. */
  private fun yamlScalar(raw: String): String? {
    DOUBLE_QUOTED_YAML.find(raw)?.let { return unescapeDoubleQuotedYaml(it.groupValues[1]) }
    SINGLE_QUOTED_YAML.find(raw)?.let { return it.groupValues[1].replace("''", "'") }
    val plain = raw.substringBefore(" #").trim()
    return plain.takeUnless { it.isEmpty() || it == "~" || it == "null" || it.first() in "[{|>&*!\"'" }
  }

  private val DOUBLE_QUOTED_YAML_ESCAPE = Regex("""\\(x[0-9A-Fa-f]{2}|u[0-9A-Fa-f]{4}|U[0-9A-Fa-f]{8}|.)""")
  private val SIMPLE_YAML_ESCAPES = mapOf(
    '0' to "\u0000", 'a' to "\u0007", 'b' to "\b", 't' to "\t", 'n' to "\n", 'v' to "\u000B", 'f' to "\u000C",
    'r' to "\r", 'e' to "\u001B", ' ' to " ", '"' to "\"", '/' to "/", '\\' to "\\", 'N' to "\u0085",
    '_' to "\u00A0", 'L' to "\u2028", 'P' to "\u2029",
  )

  /** A double-quoted YAML scalar's [body] with its escapes (`\\`, `\"`, `\t`, `\u0020`, ...) decoded. */
  private fun unescapeDoubleQuotedYaml(body: String): String =
    DOUBLE_QUOTED_YAML_ESCAPE.replace(body) { match ->
      val escape = match.groupValues[1]
      when (escape[0]) {
        'x', 'u', 'U' -> String(Character.toChars(escape.substring(1).toInt(16)))
        else -> SIMPLE_YAML_ESCAPES[escape[0]] ?: match.value
      }
    }
  private val YAML_NULLS = setOf("~", "null", "Null", "NULL")

  /**
   * Whether top-level [keys] decode as a scripted-tool descriptor: `script:` and `name:`, when set, are non-empty
   * string scalars, and `tools:`, when set, is a non-empty list. A YAML like `name: []` describes no tool. An
   * optional field set to null (`tools: null`, `name: ~`) is absent, as in the loader; `script:` is required.
   */
  private fun isToolDescriptor(keys: Map<String, String>): Boolean {
    val present = keys.filter { (key, value) -> key == "script" || value !in YAML_NULLS }
    return listOf("script", "name").all { key -> present[key]?.let { !yamlScalar(it).isNullOrEmpty() } ?: true } &&
      (present["tools"]?.let { it.isEmpty() || (it.startsWith("[") && it != "[]") } ?: true)
  }

  /**
   * Whether any of [trailmapDirs] carries an in-process TypeScript tool source — that is, whether
   * this request can need esbuild and the SDK entry at all. A request of only `.bundle.js` tools (or
   * of no trailmaps) is packaged without bundling, so a caller can skip resolving them.
   *
   * Answers from the packaging loop's own discovery ([inProcessToolSources]) so a caller can't drift
   * from what the loop will actually try to bundle. It deliberately does NOT apply the loop's
   * "a `.bundle.js` beside the source wins" filter, which needs the accumulated asset map: erring
   * toward "yes" costs a caller a resolution it didn't need, while erring toward "no" would drop the
   * slim SDK alias from a bundle that does get built.
   */
  internal fun carriesTypeScriptToolSource(trailmapDirs: List<File>): Boolean =
    trailmapDirs.any { dir ->
      File(dir, "tools").let { it.isDirectory && inProcessToolSources(it).isNotEmpty() } ||
        surveyFiles(File(dir, "surveys")).isNotEmpty()
    }

  // --- record ---------------------------------------------------------------------------------

  private fun readShellProperties(shellApk: File): Map<String, String> {
    val bytes = ApkZipRewriter.readEntry(shellApk, SHELL_VERSION_ENTRY) ?: return emptyMap()
    return java.util.Properties()
      .apply { load(bytes.inputStream()) }
      .entries
      .associate { (k, v) -> k.toString() to v.toString().trim() }
  }

  /** Digest over path + bytes of every entry, so the record identifies the content that shipped. */
  private fun contentDigest(entries: Map<String, ByteArray>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    entries.toSortedMap().forEach { (path, bytes) ->
      digest.update(path.toByteArray())
      digest.update(0)
      digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  /** Streamed, because a shell APK is tens to hundreds of megabytes and need not fit in one array. */
  private fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
      val buffer = ByteArray(64 * 1024)
      while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }
}
