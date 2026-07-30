import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.tools.ToolProvider
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

/**
 * Nested-block spec for `trailblazeAndroid { inProcessIdle { ... } }` — one instance per
 * `inProcessIdle { }` call, consumed immediately by [registerInProcessIdleApk]. Call it more than once
 * to build idle detectors for more than one target app; each output lands in the shared
 * [TrailblazeAndroidGradleExtension.inProcessIdleStagingRoot].
 *
 * The in-process idle is a bare-`Instrumentation` APK (source ships inside this plugin's jar) that
 * attaches in-process to the app under test and answers "is the app idle?" over localhost — the
 * fast arm of Trailblaze's Android settle race (sysprop `debug.trailblaze.settle.inProcessIdle`). An
 * instrumentation can only attach to an app signed with the SAME certificate, which is why signing
 * is the whole configuration surface here: the idle detector must be signed with the target app's own key.
 *
 * Signing resolves in precedence order:
 * 1. **Explicit keystore** — set [keystoreFile], [keystorePassword], [keyAlias] (and optionally
 *    [keyPassword], defaulting to the store password). For a target app signed with a key this
 *    module doesn't own.
 * 2. **A named signing config** — set [signingConfigName] to a name in this module's AGP
 *    `android.signingConfigs` container; the idle detector signs with that config's own
 *    storeFile/passwords/alias, so the key never has to be spelled out twice ("zero key
 *    distribution"). Read by reflection, same AGP-version-agnostic posture as the rest of this
 *    plugin.
 * 3. **Neither** — the standard Android debug keystore (`~/.android/debug.keystore`,
 *    `androiddebugkey`), generated with the canonical debug parameters if it doesn't exist yet so
 *    AGP subsequently signs the app under test with the very same key regardless of task order.
 *    The right default whenever the target app is a plain debug build.
 */
abstract class InProcessIdleApkSpec {
  /**
   * The applicationId of the installed app under test — stamped into the idle detector manifest's
   * `android:targetPackage`. The idle detector's own package name is suffixed with this id's last dotted
   * label (`xyz.block.trailblaze.inprocessidle.<lastLabel>`), the same convention Trailblaze's launchApp
   * re-attach looks for on-device, and the staged asset is named
   * `inprocess-idle-apks/trailblaze-inprocess-idle-<lastLabel>.apk`.
   */
  abstract val targetApplicationId: Property<String>

  /**
   * Name of an entry in this module's `android.signingConfigs` container to sign the idle detector with.
   * Mutually exclusive with the explicit keystore properties below.
   */
  abstract val signingConfigName: Property<String>

  /** Explicit keystore to sign with. Requires [keystorePassword] and [keyAlias]. */
  abstract val keystoreFile: Property<File>

  abstract val keystorePassword: Property<String>

  abstract val keyAlias: Property<String>

  /** Key password when it differs from [keystorePassword] (which it defaults to). */
  abstract val keyPassword: Property<String>

  /** `--min-api` for dexing and `--min-sdk-version` for linking. Default 28. */
  abstract val minSdkVersion: Property<Int>

  /** `--target-sdk-version` for linking. Default 35. */
  abstract val targetSdkVersion: Property<Int>
}

/** Plugin-jar resource paths + naming conventions for the embedded inprocess-idle source. */
internal object InProcessIdleConventions {
  const val SOURCE_RESOURCE = "inprocess-idle/InProcessIdleInstrumentation.java"
  const val MANIFEST_RESOURCE = "inprocess-idle/AndroidManifest.xml"

  /** Must match the manifest template's `package=` and the instrumentation class's package. */
  const val IN_PROCESS_IDLE_BASE_PACKAGE = "xyz.block.trailblaze.inprocessidle"

  const val DEFAULT_MIN_SDK = 28
  const val DEFAULT_TARGET_SDK = 35

  /**
   * The idle detector package suffix for a target app — its applicationId's last dotted label. Shared
   * convention with the on-device launchApp re-attach (`InProcessIdleLaunchReattacher` in
   * `trailblaze-android`), which probes `xyz.block.trailblaze.inprocessidle.<suffix>` by name.
   */
  fun packageSuffix(targetApplicationId: String): String =
    targetApplicationId.substringAfterLast('.')

  fun apkAssetPath(targetApplicationId: String): String =
    "inprocess-idle-apks/trailblaze-inprocess-idle-${packageSuffix(targetApplicationId)}.apk"

  fun taskName(targetApplicationId: String): String {
    val capSuffix = packageSuffix(targetApplicationId).replaceFirstChar { it.uppercase() }
    return "buildTrailblazeInProcessIdle${capSuffix}Apk"
  }

  /**
   * Stamps the manifest template for one target: the idle detector's own `package` gets the per-target
   * suffix (side-by-side installs), and `android:targetPackage` becomes the app under test.
   * Throws when the template doesn't carry the expected anchors — a template/code drift guard,
   * since a silently un-stamped manifest would build an idle detector that attaches to nothing.
   */
  fun stampManifest(template: String, targetApplicationId: String): String {
    val packageAnchor = "package=\"$IN_PROCESS_IDLE_BASE_PACKAGE\""
    if (!template.contains(packageAnchor)) {
      throw GradleException(
        "inprocess-idle manifest template is missing `$packageAnchor` — the bundled template and " +
          "the stamping code have drifted."
      )
    }
    val stamped =
      template
        .replace(
          packageAnchor,
          "package=\"$IN_PROCESS_IDLE_BASE_PACKAGE.${packageSuffix(targetApplicationId)}\"",
        )
        .replace(
          Regex("android:targetPackage=\"[^\"]*\""),
          "android:targetPackage=\"$targetApplicationId\"",
        )
    if (!stamped.contains("android:targetPackage=\"$targetApplicationId\"")) {
      throw GradleException(
        "inprocess-idle manifest template has no `android:targetPackage` attribute to stamp — the " +
          "bundled template and the stamping code have drifted."
      )
    }
    return stamped
  }
}

/**
 * How one `inProcessIdle { }` block signs its APK, resolved from the spec by
 * [resolveInProcessIdleSigningMode] — pure so the precedence/validation rules are unit-testable.
 */
internal sealed class InProcessIdleSigningMode {
  data class Explicit(
    val storeFile: File,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
  ) : InProcessIdleSigningMode()

  data class FromSigningConfig(val name: String) : InProcessIdleSigningMode()

  object DefaultDebugKeystore : InProcessIdleSigningMode()
}

internal fun resolveInProcessIdleSigningMode(
  keystoreFile: File?,
  keystorePassword: String?,
  keyAlias: String?,
  keyPassword: String?,
  signingConfigName: String?,
): InProcessIdleSigningMode {
  val anyExplicit =
    keystoreFile != null || keystorePassword != null || keyAlias != null || keyPassword != null
  if (anyExplicit && signingConfigName != null) {
    throw GradleException(
      "trailblazeAndroid.inProcessIdle { }: set EITHER the explicit keystore properties " +
        "(keystoreFile/keystorePassword/keyAlias) OR `signingConfigName`, not both."
    )
  }
  if (anyExplicit) {
    if (keystoreFile == null || keystorePassword == null || keyAlias == null) {
      throw GradleException(
        "trailblazeAndroid.inProcessIdle { }: explicit signing requires `keystoreFile`, " +
          "`keystorePassword` and `keyAlias` (`keyPassword` defaults to the store password)."
      )
    }
    return InProcessIdleSigningMode.Explicit(
      storeFile = keystoreFile,
      storePassword = keystorePassword,
      keyAlias = keyAlias,
      keyPassword = keyPassword ?: keystorePassword,
    )
  }
  if (signingConfigName != null) return InProcessIdleSigningMode.FromSigningConfig(signingConfigName)
  return InProcessIdleSigningMode.DefaultDebugKeystore
}

/**
 * Registers one [BuildTrailblazeInProcessIdleApkTask] for a `trailblazeAndroid { inProcessIdle { } }`
 * call, staging its APK into [TrailblazeAndroidGradleExtension.inProcessIdleStagingRoot] at the
 * conventional asset path — `apply()`'s AGP wiring adds the staging root to the `androidTest`
 * assets source set and hooks the asset-merge/lint task dependencies, so consumers write no
 * source-set or `dependsOn` wiring by hand (same contract as the `trailmap { }` staging).
 */
internal fun registerInProcessIdleApk(
  project: Project,
  extension: TrailblazeAndroidGradleExtension,
  spec: InProcessIdleApkSpec,
) {
  val appId =
    spec.targetApplicationId.orNull?.takeIf { it.isNotBlank() }
      ?: throw GradleException(
        "trailblazeAndroid.inProcessIdle { }: `targetApplicationId` must be set."
      )
  val suffix = InProcessIdleConventions.packageSuffix(appId)
  val priorAppId = extension.inProcessIdleApkTargetsBySuffix.putIfAbsent(suffix, appId)
  if (priorAppId != null) {
    throw GradleException(
      "trailblazeAndroid.inProcessIdle { }: targets `$priorAppId` and `$appId` share the package " +
        "suffix `$suffix`, so their idle detector packages, tasks and staged APKs would collide. The " +
        "idle detector package convention is `${InProcessIdleConventions.IN_PROCESS_IDLE_BASE_PACKAGE}.<last dotted " +
        "label of the applicationId>` — targets built side-by-side need distinct last labels."
    )
  }
  val mode =
    resolveInProcessIdleSigningMode(
      keystoreFile = spec.keystoreFile.orNull,
      keystorePassword = spec.keystorePassword.orNull,
      keyAlias = spec.keyAlias.orNull,
      keyPassword = spec.keyPassword.orNull,
      signingConfigName = spec.signingConfigName.orNull,
    )

  val taskName = InProcessIdleConventions.taskName(appId)
  val outputApk =
    extension.inProcessIdleStagingRoot.map { it.file(InProcessIdleConventions.apkAssetPath(appId)) }
  val workDir = project.layout.buildDirectory.dir("tmp/$taskName")
  // Resolved eagerly at configuration time (local.properties + env), matching when AGP itself
  // locates the SDK. May legitimately be absent — the task action raises the directed error so
  // that merely configuring the project (e.g. `./gradlew tasks`) never requires an SDK.
  val sdkDir = resolveAndroidSdkDir(project.rootProject.projectDir)
  val minSdk = spec.minSdkVersion.orNull ?: InProcessIdleConventions.DEFAULT_MIN_SDK
  val targetSdk = spec.targetSdkVersion.orNull ?: InProcessIdleConventions.DEFAULT_TARGET_SDK

  val task =
    project.tasks.register(taskName, BuildTrailblazeInProcessIdleApkTask::class.java) { t ->
      t.group = "trailblaze"
      t.description =
        "Builds + signs the Trailblaze inprocess-idle instrumentation APK targeting $appId and " +
          "stages it as an androidTest asset."
      t.targetApplicationId.set(appId)
      t.minSdkVersion.set(minSdk)
      t.targetSdkVersion.set(targetSdk)
      if (sdkDir != null) t.sdkDir.set(sdkDir)
      t.workDir.set(workDir)
      t.outputApk.set(outputApk)
      when (mode) {
        is InProcessIdleSigningMode.Explicit -> {
          t.keystoreFile.set(mode.storeFile)
          t.keystorePassword.set(mode.storePassword)
          t.keyAlias.set(mode.keyAlias)
          t.keyPassword.set(mode.keyPassword)
          t.generateDebugKeystoreIfMissing.set(false)
        }
        InProcessIdleSigningMode.DefaultDebugKeystore -> {
          t.keystoreFile.set(File(System.getProperty("user.home"), ".android/debug.keystore"))
          t.keystorePassword.set("android")
          t.keyAlias.set("androiddebugkey")
          t.keyPassword.set("android")
          t.generateDebugKeystoreIfMissing.set(true)
        }
        is InProcessIdleSigningMode.FromSigningConfig -> {
          // Quad filled from AGP's signingConfigs at afterEvaluate, below.
          t.generateDebugKeystoreIfMissing.set(false)
        }
      }
    }

  if (mode is InProcessIdleSigningMode.FromSigningConfig) {
    project.afterEvaluate {
      // Missing `android` is reported by apply()'s own afterEvaluate fail-fast; don't
      // double-report.
      val android = project.extensions.findByName("android") ?: return@afterEvaluate
      @Suppress("UNCHECKED_CAST")
      val signingConfigs =
        reflectAgpCall(android, "getSigningConfigs") as? NamedDomainObjectContainer<Any>
          ?: throw agpReflectionError(
            android,
            "getSigningConfigs",
            ClassCastException("not a NamedDomainObjectContainer"),
          )
      val signingConfig =
        signingConfigs.findByName(mode.name)
          ?: throw GradleException(
            "trailblazeAndroid.inProcessIdle { } for `$appId`: no signing config named " +
              "`${mode.name}` in android.signingConfigs (available: " +
              "${signingConfigs.names}). Set `signingConfigName` to an existing config, or use " +
              "the explicit keystore properties."
          )
      val storeFile = reflectAgpCallOrNull(signingConfig, "getStoreFile") as File?
      val storePassword = reflectAgpCallOrNull(signingConfig, "getStorePassword") as String?
      val keyAlias = reflectAgpCallOrNull(signingConfig, "getKeyAlias") as String?
      val keyPassword = reflectAgpCallOrNull(signingConfig, "getKeyPassword") as String?
      if (storeFile == null || storePassword == null || keyAlias == null) {
        throw GradleException(
          "trailblazeAndroid.inProcessIdle { } for `$appId`: signing config `${mode.name}` is " +
            "missing storeFile/storePassword/keyAlias — the idle detector can't be signed with it. " +
            "Complete the signing config, or use the explicit keystore properties."
        )
      }
      task.configure { t ->
        t.keystoreFile.set(storeFile)
        t.keystorePassword.set(storePassword)
        t.keyAlias.set(keyAlias)
        t.keyPassword.set(keyPassword ?: storePassword)
      }
    }
  }

  extension.inProcessIdleApkTasks += task
}

/**
 * The Android SDK root, resolved the way an Android build usually finds it: `local.properties`
 * `sdk.dir` at the root project, then `ANDROID_HOME` / `ANDROID_SDK_ROOT`, then the default
 * install locations (macOS `~/Library/Android/sdk`, Linux `~/Android/Sdk`).
 */
internal fun resolveAndroidSdkDir(rootProjectDir: File): File? {
  val localProps = File(rootProjectDir, "local.properties")
  if (localProps.isFile) {
    val props = java.util.Properties()
    localProps.inputStream().use(props::load)
    props.getProperty("sdk.dir")?.let { path ->
      File(path).takeIf { it.isDirectory }?.let {
        return it
      }
    }
  }
  sequenceOf(System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT"))
    .filterNotNull()
    .map(::File)
    .firstOrNull { it.isDirectory }
    ?.let {
      return it
    }
  val home = File(System.getProperty("user.home"))
  return sequenceOf(File(home, "Library/Android/sdk"), File(home, "Android/Sdk")).firstOrNull {
    it.isDirectory
  }
}

/**
 * Highest of the dotted-numeric directory names (`36.0.0`, `35.0.0-rc4`, …) — any modern
 * build-tools works (the idle detector bytecode is desugared to `--min-api` anyway), so picking the
 * highest installed keeps machines with different pins building without configuration. A stable
 * version outranks a suffixed (`-rc`) one at the same number. Pure for unit-testability.
 */
internal fun pickHighestVersionedName(names: List<String>): String? {
  data class Parsed(val name: String, val nums: List<Int>, val stable: Boolean)
  val parsed =
    names.mapNotNull { name ->
      val prefix =
        Regex("^(\\d+(?:\\.\\d+)*)").find(name)?.groupValues?.get(1) ?: return@mapNotNull null
      Parsed(name, prefix.split('.').map(String::toInt), stable = name == prefix)
    }
  val byVersion =
    Comparator<Parsed> { a, b ->
      val n = maxOf(a.nums.size, b.nums.size)
      for (i in 0 until n) {
        val cmp = a.nums.getOrElse(i) { 0 }.compareTo(b.nums.getOrElse(i) { 0 })
        if (cmp != 0) return@Comparator cmp
      }
      a.stable.compareTo(b.stable)
    }
  return parsed.maxWithOrNull(byVersion)?.name
}

/** Highest numeric `android-<N>` platform name; preview dirs (`android-Baklava`) are skipped. */
internal fun pickHighestPlatformName(names: List<String>): String? =
  names
    .mapNotNull { name ->
      Regex("^android-(\\d+)$").find(name)?.groupValues?.get(1)?.toInt()?.let { name to it }
    }
    .maxByOrNull { it.second }
    ?.first

/**
 * Appends [add] to the zip at [zip] under [entryName], **preserving every existing entry's
 * compression method** — re-deflating a STORED entry (e.g. `resources.arsc`, which API 30+
 * requires uncompressed) would corrupt the APK in a way only visible at install time. Rewrites
 * through a sibling temp file and swaps it in. Top-level and filesystem-pure (no task state) so the
 * method-preservation contract is unit-testable; throws [IllegalStateException] if the swap fails.
 */
internal fun appendFileToZip(zip: File, add: File, entryName: String) {
  val tmp = File(zip.parentFile, "${zip.name}.tmp")
  ZipOutputStream(tmp.outputStream().buffered()).use { out ->
    ZipFile(zip).use { existing ->
      existing.entries().asSequence().forEach { entry ->
        val copied = ZipEntry(entry.name)
        copied.time = entry.time
        copied.method = entry.method
        if (entry.method == ZipEntry.STORED) {
          copied.size = entry.size
          copied.compressedSize = entry.compressedSize
          copied.crc = entry.crc
        }
        out.putNextEntry(copied)
        existing.getInputStream(entry).use { it.copyTo(out) }
        out.closeEntry()
      }
    }
    out.putNextEntry(ZipEntry(entryName))
    add.inputStream().use { it.copyTo(out) }
    out.closeEntry()
  }
  check(zip.delete() && tmp.renameTo(zip)) { "could not replace $zip while adding $entryName." }
}

/**
 * Builds + signs one inprocess-idle instrumentation APK from the plugin-jar-embedded source using
 * the consumer's own Android SDK tools — no Gradle android plugin involvement, no checked-in
 * binaries, roughly a second of work:
 *
 * 1. Extract `InProcessIdleInstrumentation.java` + the manifest template from the plugin jar and
 *    stamp the manifest for [targetApplicationId].
 * 2. `javac --release 11` against the SDK's `android.jar` (NOT `-bootclasspath`, which would hide
 *    the JDK's lambda metafactory) — `d8 --min-api` desugars down to [minSdkVersion].
 * 3. `aapt2 link` with explicit min/target SDK versions (defaults to 0 otherwise, which installs
 *    reject with `INSTALL_FAILED_DEPRECATED_SDK_VERSION`), add `classes.dex`, `zipalign`.
 * 4. `apksigner sign` with the resolved keystore. `--v4-signing-enabled false` skips the `.idsig`
 *    sidecar (only needed for adb incremental install) so the staged asset is just the APK.
 */
abstract class BuildTrailblazeInProcessIdleApkTask : DefaultTask() {
  @get:Input abstract val targetApplicationId: Property<String>

  @get:Input abstract val minSdkVersion: Property<Int>

  @get:Input abstract val targetSdkVersion: Property<Int>

  // The keystore PATH is @Internal (the standard debug keystore may not exist until this task
  // creates it, which @InputFile would reject); the file's CONTENTS are tracked via
  // [keystoreInputFiles] so a rotated keystore re-signs the idle detector — otherwise it could no longer
  // attach to an app signed with the new key.
  @get:Internal abstract val keystoreFile: Property<File>

  // Passwords are deliberately NOT build inputs: they don't change the built APK's identity
  // beyond what the keystore bytes already capture, and they don't belong in cache keys.
  @get:Internal abstract val keystorePassword: Property<String>

  @get:Input abstract val keyAlias: Property<String>

  @get:Internal abstract val keyPassword: Property<String>

  /** True only for the standard-debug-keystore default — never for a consumer-supplied keystore. */
  @get:Input abstract val generateDebugKeystoreIfMissing: Property<Boolean>

  // Which SDK install builds the APK doesn't change the output's identity (any modern
  // build-tools produces an equivalent idle detector) — deliberately untracked.
  @get:Internal abstract val sdkDir: Property<File>

  @get:Internal abstract val workDir: DirectoryProperty

  @get:OutputFile abstract val outputApk: RegularFileProperty

  @get:Inject internal abstract val execOperations: ExecOperations

  @get:Inject internal abstract val objectFactoryForInProcessIdle: ObjectFactory

  @get:InputFiles
  @get:PathSensitive(PathSensitivity.NONE)
  val keystoreInputFiles: FileCollection
    get() = objectFactoryForInProcessIdle.fileCollection().from(keystoreFile)

  @TaskAction
  fun build() {
    val appId = targetApplicationId.get()
    val sdk =
      sdkDir.orNull
        ?: throw GradleException(
          "$name: no Android SDK found. Set `sdk.dir` in the root project's local.properties " +
            "or export ANDROID_HOME."
        )
    val buildTools =
      pickHighestVersionedName(File(sdk, "build-tools").list()?.toList().orEmpty())?.let {
        File(sdk, "build-tools/$it")
      }
        ?: throw GradleException(
          "$name: no build-tools installed under $sdk — install any modern Android build-tools."
        )
    val androidJar =
      pickHighestPlatformName(File(sdk, "platforms").list()?.toList().orEmpty())?.let {
        File(sdk, "platforms/$it/android.jar")
      }
        ?.takeIf { it.isFile }
        ?: throw GradleException(
          "$name: no platform android.jar under $sdk — install any modern Android platform."
        )

    val work = workDir.get().asFile
    work.deleteRecursively()
    work.mkdirs()

    // 1. Extract the embedded source + stamped manifest.
    val javaFile = File(work, "InProcessIdleInstrumentation.java")
    javaFile.writeText(readPluginResource(InProcessIdleConventions.SOURCE_RESOURCE))
    val manifestFile = File(work, "AndroidManifest.xml")
    manifestFile.writeText(
      InProcessIdleConventions.stampManifest(
        template = readPluginResource(InProcessIdleConventions.MANIFEST_RESOURCE),
        targetApplicationId = appId,
      )
    )

    // 2. Compile with the JDK running Gradle, then dex.
    val classesDir = File(work, "classes").also(File::mkdirs)
    val compiler =
      ToolProvider.getSystemJavaCompiler()
        ?: throw GradleException("$name: no system Java compiler — run Gradle on a JDK, not a JRE.")
    val javacOut = ByteArrayOutputStream()
    val javacExit =
      compiler.run(
        null,
        javacOut,
        javacOut,
        "--release",
        "11",
        "-cp",
        androidJar.absolutePath,
        "-d",
        classesDir.absolutePath,
        javaFile.absolutePath,
      )
    if (javacExit != 0) {
      throw GradleException("$name: javac failed (exit $javacExit):\n$javacOut")
    }
    val classFiles =
      classesDir.walkTopDown().filter { it.isFile && it.extension == "class" }.toList()
    runBuildTool(
      buildTools,
      "d8",
      listOf(
        "--release",
        "--lib",
        androidJar.absolutePath,
        "--min-api",
        minSdkVersion.get().toString(),
        "--output",
        work.absolutePath,
      ) + classFiles.map { it.absolutePath },
    )

    // 3. Link, add the dex, align.
    val unsignedApk = File(work, "unsigned.apk")
    runBuildTool(
      buildTools,
      "aapt2",
      listOf(
        "link",
        "-o",
        unsignedApk.absolutePath,
        "-I",
        androidJar.absolutePath,
        "--manifest",
        manifestFile.absolutePath,
        "--min-sdk-version",
        minSdkVersion.get().toString(),
        "--target-sdk-version",
        targetSdkVersion.get().toString(),
      ),
    )
    appendFileToZip(unsignedApk, File(work, "classes.dex"), "classes.dex")
    val alignedApk = File(work, "aligned.apk")
    runBuildTool(
      buildTools,
      "zipalign",
      listOf("-f", "4", unsignedApk.absolutePath, alignedApk.absolutePath),
    )

    // 4. Sign.
    val keystore = keystoreFile.get()
    if (!keystore.isFile) {
      if (!generateDebugKeystoreIfMissing.get()) {
        throw GradleException("$name: keystore not found at $keystore.")
      }
      generateDebugKeystore(keystore)
    }
    val output = outputApk.get().asFile
    output.parentFile.mkdirs()
    runBuildTool(
      buildTools,
      "apksigner",
      listOf(
        "sign",
        "--ks",
        keystore.absolutePath,
        "--ks-pass",
        "pass:${keystorePassword.get()}",
        "--ks-key-alias",
        keyAlias.get(),
        "--key-pass",
        "pass:${keyPassword.get()}",
        "--v4-signing-enabled",
        "false",
        "--out",
        output.absolutePath,
        alignedApk.absolutePath,
      ),
    )
    logger.lifecycle(
      "$name: built inprocess-idle APK targeting $appId (${output.length()} bytes) at $output"
    )
  }

  private fun readPluginResource(path: String): String =
    javaClass.classLoader.getResourceAsStream(path)?.use { it.readBytes().toString(Charsets.UTF_8) }
      ?: throw GradleException(
        "$name: plugin resource `$path` not found — the plugin jar is missing its embedded " +
          "inprocess-idle source."
      )

  private fun runBuildTool(buildToolsDir: File, toolName: String, args: List<String>) {
    val isWindows = System.getProperty("os.name").lowercase().contains("win")
    val candidates = if (isWindows) listOf("$toolName.exe", "$toolName.bat") else listOf(toolName)
    val executable =
      candidates.map { File(buildToolsDir, it) }.firstOrNull { it.isFile }
        ?: throw GradleException("$name: `$toolName` not found in $buildToolsDir.")
    val toolOutput = ByteArrayOutputStream()
    val result =
      execOperations.exec { exec ->
        exec.commandLine(listOf(executable.absolutePath) + args)
        exec.standardOutput = toolOutput
        exec.errorOutput = toolOutput
        exec.isIgnoreExitValue = true
      }
    if (result.exitValue != 0) {
      throw GradleException("$name: `$toolName` failed (exit ${result.exitValue}):\n$toolOutput")
    }
  }

  /**
   * Creates the standard Android debug keystore with the canonical parameters AGP uses, so AGP
   * reuses this very file to sign the app under test and the two signatures match regardless of
   * which build ran first (a fresh CI agent may run this task before any AGP debug build).
   */
  private fun generateDebugKeystore(keystore: File) {
    keystore.parentFile?.mkdirs()
    val isWindows = System.getProperty("os.name").lowercase().contains("win")
    val keytool =
      File(System.getProperty("java.home"), if (isWindows) "bin/keytool.exe" else "bin/keytool")
    logger.lifecycle("$name: generating the standard Android debug keystore at $keystore")
    val toolOutput = ByteArrayOutputStream()
    val result =
      execOperations.exec { exec ->
        exec.commandLine(
          keytool.absolutePath,
          "-genkeypair",
          "-keystore",
          keystore.absolutePath,
          "-storepass",
          "android",
          "-keypass",
          "android",
          "-alias",
          "androiddebugkey",
          "-dname",
          "CN=Android Debug,O=Android,C=US",
          "-keyalg",
          "RSA",
          "-keysize",
          "2048",
          "-validity",
          "10950",
        )
        exec.standardOutput = toolOutput
        exec.errorOutput = toolOutput
        exec.isIgnoreExitValue = true
      }
    if (result.exitValue != 0) {
      throw GradleException(
        "$name: keytool failed to create $keystore (exit ${result.exitValue}):\n$toolOutput"
      )
    }
  }
}
