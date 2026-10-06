plugins {
  kotlin("jvm")
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.dependency.guard)
  // Puts build-logic's classes (registerPackageUberJarForCurrentOs and registerCliRunTask below)
  // on this script's classpath.
  id("trailblaze.build-logic-classpath")
}

// JVM args for macOS — Skiko's JNI native code needs access to internal AWT classes. Without
// these, `java -jar` crashes with SIGSEGV. The canonical source for these is scripts/trailblaze
// (used at runtime). They're duplicated here for Gradle `JavaExec` tasks.
val macOsJvmArgs = listOf(
  "--add-opens", "java.desktop/sun.awt=ALL-UNNAMED",
  "--add-opens", "java.desktop/sun.lwawt=ALL-UNNAMED",
  "--add-opens", "java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
)

// Same Dispatchers.IO ceiling the JAR launcher sets (scripts/trailblaze), and for the same reason:
// each scripted-tool subprocess pins one IO permit for its whole session, and exhausting the
// 64-permit default does not fail - it hangs every daemon route while /ping keeps answering.
// A Gradle `JavaExec` task does not read the launcher script, so it needs it here or it wedges
// where the shipped CLI does not. TRAILBLAZE_IO_PARALLELISM is read from the environment that
// starts the task.
//
// Validated for the same reason the launcher script validates it, and against the same rule:
// kotlinx parses the raw property with `toLongOrNull()` and calls error() on anything that is not
// an integer >= 1, killing the JVM the first time Dispatchers.IO is touched. An empty or blank
// value is the trap - it is a SET property with no usable value, which kotlinx rejects, so it must
// not be treated as "unset" here. Failing the build names the variable; the JVM's own death names
// only a system property nobody set by hand.
// SISTER-IMPL-TAG: io-parallelism-default. The 512 below must stay in lockstep with every other
// site carrying that tag — grep for it; the launcher contract test pins them together.
val ioParallelism = System.getenv("TRAILBLAZE_IO_PARALLELISM").orEmpty().ifBlank { "512" }
require(ioParallelism.toIntOrNull().let { it != null && it >= 1 }) {
  "TRAILBLAZE_IO_PARALLELISM must be an integer >= 1, got '$ioParallelism'. kotlinx.coroutines " +
    "rejects anything else and kills the JVM the first time Dispatchers.IO is touched."
}
val ioParallelismJvmArg = "-Dkotlinx.coroutines.io.parallelism=$ioParallelism"

// Exclude heavy transitive dependencies not needed in the uber JAR.
// See trailblaze-host/build.gradle.kts for detailed "Why" comments on each exclusion.
configurations.all {
  exclude(group = "ai.koog", module = "prompt-executor-bedrock-client")
  exclude(group = "ai.koog", module = "prompt-executor-dashscope-client")
  exclude(group = "ai.koog", module = "prompt-executor-deepseek-client")
  exclude(group = "ai.koog", module = "prompt-executor-mistralai-client")
  exclude(group = "ai.koog", module = "prompt-cache-redis")
  exclude(group = "aws.sdk.kotlin")
  exclude(group = "aws.smithy.kotlin")
  exclude(group = "io.lettuce")
  exclude(group = "redis.clients.authentication")
  // Note: io.micrometer is NOT excluded — maestro-utils MetricsProvider depends on it.
  exclude(group = "io.projectreactor")
  exclude(group = "org.apache.httpcomponents.client5")
  exclude(group = "org.apache.httpcomponents.core5")
  exclude(group = "io.ktor", module = "ktor-client-apache5")
  // GraalVM Enterprise polyglot runtime. maestro pulls both the community truffle-runtime and the
  // EE truffle-enterprise; each ships a META-INF/services AbstractPolyglotImpl provider. Flattened
  // into the uber JAR the two collide at one path and the merge keeps only one — EE — dropping the
  // community PolyglotImpl, so maestro's GraalJS engine throws AbstractMethodError ("No
  // implementation available") on the first ${...} script interpolation. Excluding the EE runtime
  // leaves community as the sole, correct provider; it runs in interpreter mode on the standard JDK
  // (EE needs the GraalVM compiler we don't ship). Works on a normal multi-jar classpath because the
  // two service files stay separate there — the bug is uber-JAR-only.
  exclude(group = "org.graalvm.truffle", module = "truffle-enterprise")
}

dependencies {
  implementation(project(":trailblaze-agent"))
  implementation(project(":trailblaze-common"))
  implementation(project(":trailblaze-host"))
  implementation(project(":trailblaze-revyl"))
  implementation(project(":trailblaze-models"))
  implementation(project(":trailblaze-report"))
  implementation(project(":trailblaze-server"))

  implementation(libs.koog.prompt.executor.clients)
  implementation(libs.ktor.network.tls.certificates)
  implementation(libs.picocli) // For CLI interface
}

// Task to copy the APK to resources
// Extract paths at configuration time to avoid capturing Gradle script objects (configuration cache requirement)
val rootProjectDir: String = rootProject.projectDir.absolutePath
val currentProjectDir: String = projectDir.absolutePath

val copyAndroidTestApkToResources by tasks.registering(Copy::class) {
  description = "Copies the Android test APK to desktop app resources"
  group = "build"
  dependsOn(":trailblaze-android-ondevice-mcp:assembleDebugAndroidTest")

  val apkSourcePath =
    "$rootProjectDir/trailblaze-android-ondevice-mcp/build/outputs/apk/androidTest/debug/trailblaze-android-ondevice-mcp-debug-androidTest.apk"
  val resourcesDir = "$currentProjectDir/src/main/resources/apks"

  from(apkSourcePath)
  into(resourcesDir)
  rename { "trailblaze-ondevice-runner.apk" }
}

// Make processResources depend on copying the APK
tasks.named("processResources") {
  dependsOn(copyAndroidTestApkToResources)
}

val packageUberJar = registerPackageUberJarForCurrentOs(
  mainClass = "xyz.block.trailblaze.desktop.Trailblaze",
  appJar = tasks.named<Jar>("jar"),
  runtimeClasspath = configurations.getByName("runtimeClasspath"),
  // Shared git-based version from the root build file.
  version = rootProject.extra["gitVersion"] as String,
)

registerCliRunTask(
  mainClass = "xyz.block.trailblaze.desktop.Trailblaze",
  appJar = tasks.named<Jar>("jar"),
  runtimeClasspath = configurations.getByName("runtimeClasspath"),
)

// ---------------------------------------------------------------------------
// ProGuard shrinking (standalone task with correct kotlin-metadata-jvm version)
// Enable with: -Ptrailblaze.proguard=true
// ---------------------------------------------------------------------------
val useProguard = project.findProperty("trailblaze.proguard") == "true"

apply(from = file("../gradle/proguard-utils.gradle.kts"))
val proguardInjarsResourceFilter: String by extra
val restoreArchiveEntries: (File, File) -> Unit by extra

val kotlinVersion = libs.versions.kotlin.asProvider().get()
val proguardClasspath: Configuration by configurations.creating {
  isTransitive = true
  resolutionStrategy { force("org.jetbrains.kotlin:kotlin-metadata-jvm:$kotlinVersion") }
}
dependencies { proguardClasspath(libs.proguard.gradle) }

val shrinkUberJar by tasks.registering(JavaExec::class) {
  description = "Shrinks the uber JAR with ProGuard to remove unused classes"
  group = "distribution"
  dependsOn("packageUberJarForCurrentOS")
  onlyIf { useProguard }

  classpath = proguardClasspath
  mainClass.set("proguard.ProGuard")

  val outputJar = layout.buildDirectory.file("compose/jars-shrunk/trailblaze.jar")
  inputs.file(layout.projectDirectory.file("proguard-rules.pro"))
    .withPropertyName("proguardRules")
    .withPathSensitivity(PathSensitivity.RELATIVE)
  inputs.dir(layout.buildDirectory.dir("compose/jars"))
    .withPropertyName("inputUberJars")
    .withPathSensitivity(PathSensitivity.RELATIVE)
  // The -injars resource filter decides which entries ProGuard skips (and restoreArchiveEntries
  // puts back). Editing that list changes the shrunk JAR, so it must invalidate this task too.
  inputs.property("injarsFilter", proguardInjarsResourceFilter)
  outputs.file(outputJar)

  val javaHome = System.getProperty("java.home")
  // Resolved in doFirst, reused in doLast so both operate on the same JAR.
  var resolvedInputJar: File? = null

  doFirst {
    outputJar.get().asFile.parentFile.mkdirs()
    // Find the newest uber JAR (old builds may leave stale JARs in this directory).
    val jarsDir = layout.buildDirectory.dir("compose/jars").get().asFile
    val actualJar = jarsDir.listFiles()
      ?.filter { it.extension == "jar" }
      ?.maxByOrNull { it.lastModified() }
      ?: error("No uber JAR found in ${jarsDir.absolutePath}")
    resolvedInputJar = actualJar

    val jmodsArgs = File("$javaHome/jmods").listFiles { f -> f.extension == "jmod" }
      ?.sorted()
      ?.flatMap { listOf("-libraryjars", "${it.absolutePath}(!**.jar;!module-info.class)") }
      ?: error("No jmod files found in $javaHome/jmods")

    args(
      "-include", project.file("proguard-rules.pro").absolutePath,
      "-injars", "${actualJar.absolutePath}($proguardInjarsResourceFilter)",
      "-outjars", outputJar.get().asFile.absolutePath,
      *jmodsArgs.toTypedArray(),
    )
  }

  doLast {
    val originalJar = resolvedInputJar ?: return@doLast
    restoreArchiveEntries(originalJar, outputJar.get().asFile)
  }
}

// Task to build release artifacts.
// Use -Ptrailblaze.proguard=true to produce a ProGuard-shrunk JAR.
val releaseArtifacts by tasks.registering(Copy::class) {
  description = "Builds the release JAR artifact for distribution"
  group = "distribution"

  if (useProguard) {
    dependsOn(shrinkUberJar)
    from(layout.buildDirectory.dir("compose/jars-shrunk")) { include("*.jar") }
  } else {
    dependsOn("packageUberJarForCurrentOS")
    from(layout.buildDirectory.dir("compose/jars")) { include("*.jar") }
  }

  val releaseDir = layout.buildDirectory.dir("release")
  into(releaseDir)
  rename(".*", "trailblaze.jar")
  duplicatesStrategy = DuplicatesStrategy.INCLUDE

  // Copy the launcher script alongside the JAR. In java -jar mode (the default),
  // it passes the --add-opens JVM flags Skiko needs on macOS.
  doLast {
    val launcher = project.file("../scripts/trailblaze")
    val dest = releaseDir.get().asFile.resolve("trailblaze")
    launcher.copyTo(dest, overwrite = true)
    dest.setExecutable(true)
  }
}

packageUberJar.configure {
  // Maestro ships its own Android instrumentation APKs as classpath resources, for
  // `maestro.drivers.AndroidDriver` to install onto a device. Trailblaze never builds that
  // driver -- Android runs through our own on-device runner APK -- and `AndroidDriver` is the
  // only class in maestro-client that reads either file, so they are 12.6 MB of dead weight
  // in every JAR download and Homebrew install.
  exclude("maestro-app.apk", "maestro-server.apk")
  // The WebP encoder ships libwebp for 11 platforms in one artifact. Keep the three
  // `TrailblazeDesktopUtil.assertSupportedPlatform()` lets start (linux-x64, linux-arm64,
  // macos-arm64); the rest can never load.
  exclude(
    "native/Windows/**",
    "native/Mac/x86_64/**",
    "native/Linux/arm/**",
    "native/Linux/armv6/**",
    "native/Linux/armv7/**",
    "native/Linux/ppc64/**",
    "native/Linux/x86/**",
  )
  // Maestro's XCUITest runner built for physical iPhones (~9 MB). Trailblaze supports only iOS
  // simulators, which use `driver-iPhoneSimulator/`.
  exclude("driver-iphoneos/**")
  // GraalVM's shaded ICU locale tables (~13 MB, ~4 200 files, no classes) — data for a
  // JavaScript engine that cannot run in the SHRUNK JAR. Maestro drags GraalJS in for `${...}`
  // interpolation and for `evalScript`/`runScript`; Trailblaze evaluates scripted tools on
  // QuickJS instead, and our YAML layer rejects both script commands. What settles it is the
  // shipped artifact: ProGuard leaves ZERO `com/oracle/truffle/js/**` class files in it
  // (4 886 in the dependency, 0 in the JAR, on `main` as well), so a `${...}` on the host
  // already fails there today, with or without these tables.
  //
  // Gated on the shrinker for exactly that reason: it is only the ProGuard pass that makes this
  // data unreachable. An UNSHRUNK JAR keeps the JS language, and `scripts/install-trailblaze-source.sh`
  // — the source dev loop — builds this task with no `-Ptrailblaze.proguard`, so pruning
  // unconditionally would leave a locally installed `./trailblaze` with a language that dies
  // inside missing ICU on the first `${...}`, where `main` works. Gating keeps the full 13 MB
  // win on the released JAR and every developer build byte-comparable to `main`.
  //
  // A PACKAGING exclude and not `configurations.all` for the same reason at one more remove: a
  // dependency exclude reaches `run`, `JavaExec` and every test classpath as well, none of
  // which are shrunk. And `org.graalvm.polyglot` stays in all shapes — `Orchestra.runFlow`
  // constructs a `GraalJsEngine` before it dispatches anything, so removing polyglot breaks
  // every host-side Maestro flow, the iOS driver among them, with
  // `NoClassDefFoundError: org/graalvm/polyglot/PolyglotException`.
  //
  // The `inputs.property` is load-bearing, not decoration: a `Jar` task's exclude patterns are
  // not part of its up-to-date check, so without it a shrunk build right after an unshrunk one
  // reuses the unshrunk JAR verbatim and ships the ICU data it was supposed to drop. Verified
  // by observing exactly that before the line was added.
  inputs.property("trailblazeProguard", useProguard)
  if (useProguard) exclude("org/graalvm/shadowed/**")
}

afterEvaluate {
  tasks.withType<JavaExec> {
    // Run from the repository root so relative paths in target configs (e.g., `trails/` directories
    // referenced by a target YAML) resolve correctly.
    workingDir = rootProject.projectDir
    // Forward stdin to the JVM process so STDIO MCP transport can read JSON-RPC
    // from the parent process's stdin (e.g., `./trailblaze mcp`).
    standardInput = System.`in`
    // Same bounded heap the JAR launcher sets (scripts/trailblaze at the OSS repo root) - without it a
    // Gradle-mode daemon runs at the JVM default and drifts from the shipped configuration.
    maxHeapSize = System.getenv("TRAILBLAZE_MAX_HEAP") ?: "4g"
    jvmArgs(ioParallelismJvmArg)

    if (System.getProperty("os.name").contains("Mac")) {
      jvmArgs(*macOsJvmArgs.toTypedArray())
    }
  }
}

dependencyGuard {
  configuration("runtimeClasspath")
}
