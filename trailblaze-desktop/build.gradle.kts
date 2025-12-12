plugins {
  kotlin("jvm")
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.jetbrains.compose.multiplatform)
  alias(libs.plugins.dependency.guard)
}

dependencies {
  implementation(project(":trailblaze-agent"))
  implementation(project(":trailblaze-common"))
  implementation(project(":trailblaze-host"))
  implementation(project(":trailblaze-models"))
  implementation(project(":trailblaze-report"))
  implementation(project(":trailblaze-server"))
  implementation(project(":trailblaze-ui"))

  implementation(compose.desktop.currentOs)
  implementation(compose.ui)
  implementation(compose.runtime)
  implementation(compose.foundation)
  implementation(compose.material3)
  implementation(compose.components.resources)
  implementation(libs.koog.prompt.executor.clients)
}

compose.desktop {
  application {
    mainClass = "xyz.block.trailblaze.desktop.Trailblaze"

    nativeDistributions {
      targetFormats(
        org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg,
      )

      packageName = "Trailblaze"
      // Use shared git-based version from root build file
      packageVersion = rootProject.extra["gitVersion"] as String
      description = "Trailblaze Desktop Application (Open Source)"
      vendor = "Block, Inc."

      macOS {
        iconFile.set(project.file("../trailblaze-ui/src/jvmMain/resources/icons/icon.icns"))
        bundleID = "xyz.block.trailblaze.opensource.desktop"

        // Minimum macOS version required
        minimumSystemVersion = "11.0"

        // App store category
        appCategory = "public.app-category.developer-tools"

        // Set to true when ready to sign
        signing {
          sign.set(false)
        }
      }
    }
  }
}
afterEvaluate {
  tasks.withType<JavaExec> {
    if (System.getProperty("os.name").contains("Mac")) {
      jvmArgs("--add-opens", "java.desktop/sun.awt=ALL-UNNAMED")
      jvmArgs("--add-opens", "java.desktop/sun.lwawt=ALL-UNNAMED")
      jvmArgs("--add-opens", "java.desktop/sun.lwawt.macosx=ALL-UNNAMED")
    }
  }
}

dependencyGuard {
  configuration("runtimeClasspath") {
    baselineMap = {
      it.replace("-macos-arm64", "_PLATFORM_")
        .replace("-linux-x64", "_PLATFORM_")
    }
  }
}
