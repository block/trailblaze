import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.dependency.guard)
}

android {
  namespace = "xyz.block.trailblaze.androidworldbenchmarks"
  compileSdk = 36
  defaultConfig {
    minSdk = 26
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  // KMP commonMain/resources/ are not automatically included as Java resources on Android.
  // Explicitly add them so trailblaze-config/tools/*.yaml files are bundled into the AAR/APK
  // and discoverable as Android assets from on-device instrumentation tests.
  sourceSets.getByName("main") {
    resources.srcDirs("src/commonMain/resources")
    assets.srcDirs("src/commonMain/resources")
  }
}

kotlin {
  applyDefaultHierarchyTemplate()

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

  sourceSets {
    commonMain.dependencies {
      api(project(":trailblaze-common"))
      implementation(libs.kotlinx.serialization.core)
      implementation(libs.koog.agents.tools)
    }

    val jvmAndAndroid by creating {
      dependsOn(commonMain.get())
    }

    jvmMain {
      dependsOn(jvmAndAndroid)
    }

    androidMain {
      dependsOn(jvmAndAndroid)
    }
  }
}

dependencyGuard {
  configuration("jvmRuntimeClasspath") {
    modules = true
  }
}
