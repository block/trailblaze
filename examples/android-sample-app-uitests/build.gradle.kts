plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.android)
  id("trailblaze.spotless")
}

android {
  namespace = "xyz.block.trailblaze.examples.sampleapp.uitests"
  compileSdk = 36
  defaultConfig {
    minSdk = 28
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  sourceSets {
    getByName("androidTest") {
      // Bundle sample-app trails as assets in the test APK so runFromAsset() can read them.
      // `src/androidTest/assets/` is added so A5's fixture bundle JS
      // (`fixtures/bundle-roundtrip-fixture.js`) ships alongside the trails — the
      // on-device round-trip test loads it via `AndroidAssetBundleJsSource` to exercise
      // the same asset-path resolution production `AndroidTrailblazeRule.mcpServers`
      // consumers will use.
      assets.srcDirs("../android-sample-app/trails", "src/androidTest/assets")
      java.srcDirs("src/androidTest/java", "src/androidTest/generated")
    }
  }

  packaging {
    exclude("META-INF/INDEX.LIST")
    exclude("META-INF/AL2.0")
    exclude("META-INF/LICENSE.md")
    exclude("META-INF/LICENSE-notice.md")
    exclude("META-INF/LGPL2.1")
    exclude("META-INF/io.netty.versions.properties")
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }
  lint { abortOnError = false }
  testOptions { animationsDisabled = true }
}

dependencies {
  androidTestImplementation(project(":trailblaze-common"))
  androidTestImplementation(project(":trailblaze-android"))
  // PR A5: the on-device bundle runtime. Tests here exercise `McpBundleSession.connect`
  // directly from an instrumentation context to prove QuickJS + the in-process MCP
  // transport work on a real device — a step up from the JVM-side fixture round-trip.
  androidTestImplementation(project(":trailblaze-scripting-bundle"))
  androidTestImplementation(libs.junit)
  androidTestImplementation(libs.koog.prompt.executor.ollama)
  androidTestImplementation(libs.koog.prompt.executor.openai)
  androidTestImplementation(libs.koog.prompt.executor.openrouter)
  androidTestImplementation(libs.koog.prompt.executor.clients)
  androidTestImplementation(libs.koog.prompt.llm)
  androidTestImplementation(libs.ktor.client.core)
  androidTestImplementation(libs.kotlinx.datetime)
  androidTestRuntimeOnly(libs.androidx.test.runner)
  androidTestRuntimeOnly(libs.coroutines.android)
  androidTestImplementation(libs.maestro.orchestra.models) { isTransitive = false }
}

// ---------------------------------------------------------------------------
// generateSampleAppTests
// Scans ../android-sample-app/trails/android-ondevice-instrumentation/**/*.trail.yaml
// and writes a JUnit test class so the sample-app trails can run on a remote device farm.
// Usage: ./gradlew :examples:android-sample-app-uitests:generateSampleAppTests
// ---------------------------------------------------------------------------
tasks.register("generateSampleAppTests") {
  description = "Generate JUnit instrumentation tests from trail YAML files for remote device farm"
  group = "trailblaze"

  val trailsDir = file("../android-sample-app/trails/android-ondevice-instrumentation")
  val outputFile =
    file(
      "src/androidTest/generated/xyz/block/trailblaze/examples/sampleapp/generated/GeneratedSampleAppTests.kt"
    )

  inputs.dir(trailsDir)
  outputs.file(outputFile)

  doLast {
    outputFile.parentFile.mkdirs()

    val testMethods =
      fileTree(trailsDir)
        .filter { it.name.endsWith(".trail.yaml") }
        .map { trailFile ->
          val relPath = trailFile.relativeTo(trailsDir).path
          val testDirName = relPath.split("/").let { it[it.size - 2] }
          val methodName =
            testDirName
              .split("-")
              .mapIndexed { i, s -> if (i == 0) s else s.replaceFirstChar { c -> c.uppercase() } }
              .joinToString("")
          val assetPath = "android-ondevice-instrumentation/$relPath"
          Pair(methodName, assetPath)
        }
        .sortedBy { it.first }

    outputFile.writeText(
      buildString {
        appendLine("// AUTO-GENERATED — do not edit manually.")
        appendLine(
          "// Re-generate: ./gradlew :examples:android-sample-app-uitests:generateSampleAppTests"
        )
        appendLine()
        appendLine("package xyz.block.trailblaze.examples.sampleapp.generated")
        appendLine()
        appendLine("import org.junit.Rule")
        appendLine("import org.junit.Test")
        appendLine("import xyz.block.trailblaze.android.AndroidTrailblazeRule")
        appendLine()
        appendLine("class GeneratedSampleAppTests {")
        appendLine()
        appendLine("  @get:Rule val rule = AndroidTrailblazeRule()")
        appendLine()
        testMethods.forEach { (method, path) ->
          appendLine("  @Test fun $method() = rule.runFromAsset(\"$path\")")
        }
        appendLine("}")
      }
    )

    println("Generated ${testMethods.size} tests → ${outputFile.relativeTo(projectDir)}")
  }
}
