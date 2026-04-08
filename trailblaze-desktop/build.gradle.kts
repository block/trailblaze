import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
  kotlin("jvm")
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.jetbrains.compose.multiplatform)
  alias(libs.plugins.dependency.guard)
}

// JVM args required for Compose Desktop on macOS — Skiko's JNI native code needs
// access to internal AWT classes. Without these, `java -jar` crashes with SIGSEGV.
// The canonical source for these is opensource/scripts/trailblaze (used at runtime).
// They're duplicated here for native distributions (DMG) and development run tasks.
val macOsJvmArgs = listOf(
  "--add-opens", "java.desktop/sun.awt=ALL-UNNAMED",
  "--add-opens", "java.desktop/sun.lwawt=ALL-UNNAMED",
  "--add-opens", "java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
)

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
}

dependencies {
  implementation(project(":trailblaze-agent"))
  implementation(project(":trailblaze-common"))
  implementation(project(":trailblaze-compose"))
  implementation(project(":trailblaze-host"))
  implementation(project(":trailblaze-revyl"))
  implementation(project(":trailblaze-models"))
  implementation(project(":trailblaze-report"))
  implementation(project(":trailblaze-server"))
  implementation(project(":trailblaze-ui"))

  implementation(compose.desktop.currentOs)
  implementation(libs.compose.ui)
  implementation(libs.compose.runtime)
  implementation(libs.compose.foundation)
  implementation(libs.compose.material3)
  implementation(libs.compose.components.resources)
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

  doFirst {
    mkdir(resourcesDir)
  }
}

// Make processResources depend on copying the APK
tasks.named("processResources") {
  dependsOn(copyAndroidTestApkToResources)
}

compose.desktop {
  application {
    mainClass = "xyz.block.trailblaze.desktop.Trailblaze"
    jvmArgs += macOsJvmArgs

    nativeDistributions {
      targetFormats(
        TargetFormat.Dmg,
      )

      packageName = "Trailblaze"
      // Use shared git-based version from root build file
      packageVersion = rootProject.extra["gitVersion"] as String
      description = "Trailblaze Desktop Application (Open Source)"
      vendor = "Block, Inc."

      macOS {
        iconFile.set(project.file("../trailblaze-host/src/main/resources/icons/icon.icns"))
        bundleID = "xyz.block.trailblaze.opensource.desktop"

        // Minimum macOS version required
        minimumSystemVersion = "11.0"

        // App store category
        appCategory = "public.app-category.developer-tools"

        // Set to true when ready to sign
        signing {
          sign.set(false)
        }
      }
    }

    // ProGuard shrinking is configured as a standalone post-processing task below
    // (not using the Compose plugin's built-in ProGuard, which lags behind Kotlin versions).
    // Build with: ./gradlew :trailblaze-desktop:shrinkUberJar -Ptrailblaze.proguard=true
  }
}

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
  // it passes the --add-opens JVM flags required for macOS Compose Desktop.
  doLast {
    val launcher = project.file("../scripts/trailblaze")
    val dest = releaseDir.get().asFile.resolve("trailblaze")
    launcher.copyTo(dest, overwrite = true)
    dest.setExecutable(true)
  }
}

afterEvaluate {
  // The uber JAR exceeds 65 535 entries; enable zip64 so packaging succeeds.
  tasks.named("packageUberJarForCurrentOS") {
    (this as org.gradle.api.tasks.bundling.Zip).isZip64 = true
  }

  tasks.withType<JavaExec> {
    // Run from the repository root so relative paths (e.g., merchant-factory/trails/) resolve correctly.
    workingDir = rootProject.projectDir
    // Forward stdin to the JVM process so STDIO MCP transport can read JSON-RPC
    // from the parent process's stdin (e.g., `./trailblaze mcp`).
    standardInput = System.`in`

    if (System.getProperty("os.name").contains("Mac")) {
      jvmArgs(*macOsJvmArgs.toTypedArray())
    }
  }
}

dependencyGuard {
  configuration("runtimeClasspath") {
    @Suppress("UNCHECKED_CAST")
    val map = rootProject.extra["trailblazePlatformBaselineMap"] as (String) -> String
    baselineMap = map
  }
}
