plugins {
  id("com.android.application")
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.compose.compiler)
  id("trailblaze.bundle")
}

// Materialize per-pack `client.d.ts` + workspace `.trailblaze/sdk/` for IDE autocomplete
// on every `./gradlew build` (#3210). `bundleEnabled = false` because this pack still
// ships a legacy `host_writeArtifact.js` host-tool — see `TrailblazeBundleExtension`
// kdoc for the full rationale.
trailblazeBundle {
  packsDir.set(layout.projectDirectory.dir("trails/config/packs"))
  bundleEnabled.set(false)
}

android {
  namespace = "xyz.block.trailblaze.examples.sampleapp"
  compileSdk = 36
  defaultConfig {
    applicationId = "xyz.block.trailblaze.examples.sampleapp"
    minSdk = 28
    targetSdk = 35
    versionCode = 1
    versionName = "1.0"
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlin {
    compilerOptions {
      jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
  }
  buildFeatures { compose = true }
  lint { abortOnError = false }
}

dependencies {
  val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
  implementation(composeBom)
  implementation("androidx.compose.material3:material3")
  implementation("androidx.compose.ui:ui")
  implementation("androidx.compose.ui:ui-tooling-preview")
  implementation("androidx.compose.foundation:foundation")
  implementation("androidx.compose.material:material-icons-extended")
  implementation("androidx.activity:activity-compose:1.9.3")
  implementation("androidx.navigation:navigation-compose:2.8.5")
  implementation("androidx.viewpager2:viewpager2:1.1.0")
  implementation("androidx.recyclerview:recyclerview:1.3.2")
  implementation("com.google.android.material:material:1.12.0")
  implementation("androidx.appcompat:appcompat:1.7.0")
  implementation("androidx.fragment:fragment-ktx:1.8.5")

  debugImplementation("androidx.compose.ui:ui-tooling")
}
