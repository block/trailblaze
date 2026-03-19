plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.android)
  id("trailblaze.spotless")
}

android {
  namespace = "xyz.block.trailblaze.examples.sampleapp.uitests"
  compileSdk = 35
  defaultConfig {
    minSdk = 28
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  sourceSets {
    getByName("androidTest") {
      // Bundle sample-app trails as assets in the test APK so runFromAsset() can read them.
      assets.srcDirs("../android-sample-app/trails")
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
// and writes a JUnit test class so the sample-app trails can run on Test Farm.
// Usage: ./gradlew :examples:android-sample-app-uitests:generateSampleAppTests
// ---------------------------------------------------------------------------
tasks.register("generateSampleAppTests") {
  description = "Generate JUnit instrumentation tests from trail YAML files for Test Farm"
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
        appendLine("import xyz.block.trailblaze.examples.sampleapp.SampleAppTrailblazeRule")
        appendLine()
        appendLine("class GeneratedSampleAppTests {")
        appendLine()
        appendLine("  @get:Rule val rule = SampleAppTrailblazeRule()")
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
