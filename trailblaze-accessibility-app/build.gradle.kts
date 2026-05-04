import com.android.build.api.variant.ApplicationAndroidComponentsExtension

plugins {
  id("com.android.application")
  alias(libs.plugins.kotlin.android)
}

android {
  namespace = "xyz.block.trailblaze.evalapp"
  compileSdk = 36
  defaultConfig {
    applicationId = "xyz.block.trailblaze.evalapp"
    minSdk = 26
    targetSdk = 35
    versionCode = 1
    versionName = "1.0"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  lint {
    abortOnError = false
  }
  @Suppress("UnstableApiUsage")
  testOptions {
    unitTests.all {
      it.useJUnitPlatform()
    }
  }
}

val androidComponents = extensions.getByType<ApplicationAndroidComponentsExtension>()
androidComponents.beforeVariants {
  if (it.buildType != "debug") {
    it.enable = false
  }
}

dependencies {
  implementation(project(":trailblaze-android"))
}
