plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.spotless)
  alias(libs.plugins.vanniktech.maven.publish)
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

spotless {
  kotlin {
    target("src/**/*.kt", "build.gradle.kts")
    ktfmt().googleStyle()
  }
}

dependencies {
  api(libs.ktor.client.core)
  api(libs.ktor.client.okhttp)
  testImplementation(libs.kotlin.test.junit4)
}

java {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
}
