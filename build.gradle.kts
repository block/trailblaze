import com.diffplug.gradle.spotless.SpotlessExtension
import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.MavenPublishBaseExtension

plugins {
  alias(libs.plugins.android.library) apply false
  alias(libs.plugins.kotlin.android) apply false
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.dokka) apply false
  alias(libs.plugins.spotless) apply false
  alias(libs.plugins.dagp) apply false
  alias(libs.plugins.compose.compiler) apply false
  alias(libs.plugins.jetbrains.compose.multiplatform) apply false
  alias(libs.plugins.kotlin.multiplatform) apply false
  alias(libs.plugins.vanniktech.maven.publish) apply false
}

// Add ASM configuration to support sealed classes
allprojects {
  configurations.all {
    resolutionStrategy.eachDependency {
      if (requested.group == "org.ow2.asm") {
        useVersion("9.5")
        because("Sealed classes support requires ASM 9+")
      }
    }
  }
}

subprojects {
  apply(plugin = "org.jetbrains.dokka")
}

subprojects
  .forEach {
    it.plugins.withId("com.android.library") {
      it.extensions.configure<com.android.build.gradle.LibraryExtension>("android") {
        defaultConfig {
          System.getenv("OPENAI_API_KEY")?.let { apiKey ->
            testInstrumentationRunnerArguments["OPENAI_API_KEY"] = apiKey
          }
          testInstrumentationRunnerArguments["trailblaze.logs.endpoint"] =
            rootProject.property("trailblaze.logs.endpoint")
              ?.toString() ?: "https://10.0.2.2:8443"
          testInstrumentationRunnerArguments["trailblaze.ai.enabled"] =
            rootProject.findProperty("trailblaze.ai.enabled")?.toString() ?: "true"
        }
      }
    }

    it.afterEvaluate {
      if (plugins.hasPlugin("com.diffplug.spotless")) {
        it.extensions.getByType(SpotlessExtension::class.java).apply {
          kotlin {
            target("**/*.kt", "**/*.kts")
            targetExclude("**/dependencies/*.txt")
            ktlint("1.5.0").editorConfigOverride(
              mapOf(
                "indent_style" to "space", // match IntelliJ indent
                "indent_size" to "2", // match IntelliJ indent
                "ktlint_standard_indent" to "2", // match IntelliJ indent
                "ij_kotlin_imports_layout" to "*,java.**,javax.**,kotlin.**,^", // match IntelliJ import order
              ),
            )
          }
        }
      }
    }

    it.plugins.withId("com.vanniktech.maven.publish") {
      it.extensions.getByType(MavenPublishBaseExtension::class.java).also { publishing ->
        publishing.publishToMavenCentral(automaticRelease = true)
        publishing.pom {
          name = "trailblaze"
          description = "An AI-Driven end-to-end testing library"
        }

        if (it.plugins.hasPlugin("org.jetbrains.kotlin.jvm")) {
          publishing.configure(
            KotlinJvm(
              sourcesJar = true,
              javadocJar = JavadocJar.None(),
            )
          )
        }
        if (it.plugins.hasPlugin("com.android.library")) {
          publishing.configure(
            AndroidSingleVariantLibrary(
              sourcesJar = true,
              publishJavadocJar = false,
            )
          )
        }
        if (it.plugins.hasPlugin("org.jetbrains.kotlin.multiplatform")) {
          publishing.configure(
            KotlinMultiplatform(
              sourcesJar = true,
              javadocJar = JavadocJar.None(),
            )
          )
        }
      }
    }
  }
