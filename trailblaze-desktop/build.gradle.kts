plugins {
  kotlin("jvm")
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.jetbrains.compose.multiplatform)
  alias(libs.plugins.dependency.guard)
  alias(libs.plugins.spotless)
}

dependencies {
  implementation(compose.desktop.currentOs)
  implementation(compose.ui)
  implementation(compose.runtime)
  implementation(compose.foundation)
  implementation(compose.material3)
  implementation(compose.components.resources)

  implementation(project(":trailblaze-common"))
  implementation(project(":trailblaze-agent"))
  implementation(project(":trailblaze-report"))
  implementation(project(":trailblaze-server"))
  implementation(project(":trailblaze-ui"))
}

compose.desktop {
  application {
    mainClass = "xyz.block.trailblaze.desktop.Trailblaze"
  }

  nativeApplication {
    distributions {
      macOS.iconFile.set(project.file("src/main/resources/icons/icon.png"))
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
