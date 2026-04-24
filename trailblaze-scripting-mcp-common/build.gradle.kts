import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.vanniktech.maven.publish)
  alias(libs.plugins.kotlin.serialization)
}

android {
  namespace = "xyz.block.trailblaze.scripting.mcp"
  compileSdk = 36
  defaultConfig {
    minSdk = 26
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

kotlin {
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

  applyDefaultHierarchyTemplate()

  sourceSets {
    commonMain.dependencies {
      api(project(":trailblaze-common"))
      api(project(":trailblaze-models"))
      api(libs.mcp.sdk)
      implementation(libs.kotlinx.serialization.json)
      implementation(libs.coroutines)
    }

    // Shared source set for JVM and Android — the whole module's code lives here today;
    // the split exists so a future wasm/native target could selectively pull in just the
    // wire-shape types without the Kotlin-reflection leakage that some dependencies imply.
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
    }

    jvmTest {
      dependsOn(jvmAndAndroidTest)
    }

    androidUnitTest {
      dependsOn(jvmAndAndroidTest)
    }

    androidMain {
      dependsOn(jvmAndAndroid)
    }
  }
}
