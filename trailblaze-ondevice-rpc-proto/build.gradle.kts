import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.wire)
  alias(libs.plugins.dependency.guard)
  alias(libs.plugins.vanniktech.maven.publish)
}

android {
  namespace = "xyz.block.trailblaze.ondevice.rpc.proto"
  compileSdk = 36
  defaultConfig { minSdk = 26 }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  lint { abortOnError = false }
}

kotlin {
  androidTarget { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }
  jvm { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

  applyDefaultHierarchyTemplate()

  sourceSets {
    commonMain.dependencies {
      api(project(":trailblaze-models"))
      api(libs.wire.runtime)
      implementation(libs.kotlinx.serialization.json)
    }
    commonTest.dependencies {
      implementation(kotlin("test"))
      implementation(libs.kotlinx.serialization.json)
    }
  }
}

wire {
  sourcePath { srcDir("src/main/proto") }
  kotlin {}
}

dependencyGuard {
  configuration("jvmRuntimeClasspath")
}
