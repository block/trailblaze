package xyz.block.trailblaze.inprocess

import kotlinx.coroutines.runBlocking
import xyz.block.trailblaze.config.AppTargetYamlConfig
import xyz.block.trailblaze.config.TrailblazeConfigYaml
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.scripting.DaemonScriptedToolBundler
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
    val trailAssets = collectTrails(request.trails)

    val additions = LinkedHashMap<String, ByteArray>()
    injectedTargetConfig?.let { additions["$TARGETS_ASSET_DIR/${it.id}.yaml"] = it.bytes }
    additions += toolBundles
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
        trailmapRevision = toolBundles.takeIf { it.isNotEmpty() }?.let { contentDigest(it) },
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
      "${fingerprint.packageName} is not debuggable, so an instrumentation cannot attach to it on " +
        "an ordinary device. Pass --release if you know this target is instrumentable anyway (a " +
        "release build on a userdebug image, for instance) — it has to be explicit, because the " +
        "usual cause is pointing at the wrong APK."
    }
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
        "If it was built without gradle/inprocess-compile-floor.init.gradle.kts, a version " +
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
    val subprocessRuntimeYaml = Regex("(?m)^\\s*runtime:\\s*subprocess\\s*$")
    val toolExport = Regex("""trailblaze\s*\.\s*tool\s*[<(]""")
    return toolsDir.walkTopDown()
      .filter { f ->
        f.isFile &&
          f.name.endsWith(".ts") &&
          !f.name.endsWith(".test.ts") &&
          !f.name.endsWith(".d.ts") &&
          File(f.parentFile, f.name.removeSuffix(".ts") + ".yaml").let { yaml ->
            if (yaml.isFile) {
              !subprocessRuntimeYaml.containsMatchIn(yaml.readText())
            } else {
              toolExport.containsMatchIn(f.readText())
            }
          }
      }
      .sortedBy { it.relativeTo(toolsDir).invariantSeparatorsPath }
      .toList()
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
