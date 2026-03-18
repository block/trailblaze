plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.jetbrains.compose.multiplatform)
}

kotlin {
  compilerOptions {
    freeCompilerArgs.addAll(
      "-opt-in=androidx.compose.ui.test.ExperimentalTestApi",
      "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi",
    )
  }
}

dependencies {
  // Interface depends on compose-ui (SemanticsNode, SemanticsActions, ImageBitmap)
  api(compose.desktop.currentOs)

  // ComposeUiTestTarget wraps ComposeUiTest — callers must provide this dependency
  compileOnly(libs.compose.ui.test.junit4)
}
