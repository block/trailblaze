import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.vanniktech.maven.publish)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.dependency.guard)
  alias(libs.plugins.dagp)
}

android {
  namespace = "xyz.block.trailblaze.common"
  compileSdk = 35
  defaultConfig {
    minSdk = 26
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

kotlin {
  compilerOptions {
    // Suppress Beta warning for expect/actual classes
    freeCompilerArgs.add("-Xexpect-actual-classes")
  }

  androidTarget {
    compilerOptions {
      jvmTarget = JvmTarget.JVM_17
    }
  }

  jvm {
    compilerOptions {
      jvmTarget = JvmTarget.JVM_17
    }
  }

  // Apply the default hierarchy template explicitly
  applyDefaultHierarchyTemplate()

  sourceSets {
    commonMain.dependencies {
      api(libs.kotlinx.datetime)
      api(libs.kotlinx.serialization.json)
      api(libs.coroutines)
      api(libs.junit)
      api(libs.kaml)
      api(libs.okio)
      api(libs.koog.agents.tools)
      api(libs.ktor.client.core)

      api(project(":trailblaze-models"))
      implementation(project(":trailblaze-tracing"))

      implementation(libs.exp4j)
      implementation(libs.gson)
      implementation(libs.koog.prompt.model)
      implementation(libs.kotlinx.serialization.core)
      implementation(libs.ktor.client.logging)
      implementation(libs.ktor.client.okhttp)
      implementation(libs.ktor.http)
      implementation(libs.ktor.utils)
      implementation(libs.kotlin.reflect)
      implementation(libs.snakeyaml)

      runtimeOnly(libs.jackson.dataformat.yaml)
      runtimeOnly(libs.jackson.module.kotlin)
    }


    // Shared source set for JVM and Android (Maestro-dependent code)
    val jvmAndAndroid by creating {
      dependsOn(commonMain.get())
    }

    val jvmAndAndroidTest by creating {
      dependsOn(commonTest.get())
      dependencies {
        implementation(libs.kotlin.test.junit4)
        implementation(libs.assertk)
      }
    }

    jvmMain {
      dependsOn(jvmAndAndroid)
      dependencies {
        // JVM-specific dependencies if needed
      }
    }

    jvmTest {
      dependsOn(jvmAndAndroidTest)
      dependencies {
        implementation(libs.kotlin.test.junit4)
        implementation(libs.assertk)
      }
    }

    androidUnitTest {
      dependsOn(jvmAndAndroidTest)
    }

    androidMain {
      dependsOn(jvmAndAndroid)
      dependencies {
        // AndroidX Test and UiAutomator for on-device testing
        api(libs.androidx.test.monitor)
        api(libs.androidx.uiautomator)
      }
    }
  }
}

dependencies {
  val maestroVersion = libs.versions.maestro.get()

  // Maestro dependencies with non-transitive configuration for jvmAndAndroid
  // These are shared between JVM (host) and Android (on-device) builds
  add("jvmAndAndroidApi", "dev.mobile:maestro-orchestra-models:$maestroVersion") { isTransitive = false }
  add("jvmAndAndroidApi", "dev.mobile:maestro-client:$maestroVersion") { isTransitive = false }
  add("jvmAndAndroidApi", "dev.mobile:maestro-utils:$maestroVersion") { isTransitive = false }
  add("jvmAndAndroidImplementation", "dev.mobile:maestro-orchestra:$maestroVersion") { isTransitive = false }
}

dependencyGuard {
  configuration("jvmRuntimeClasspath") {
    modules = true
  }
}
