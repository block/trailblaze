plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.jetbrains.compose.multiplatform)
}

kotlin {
  compilerOptions {
    freeCompilerArgs.addAll(
      "-opt-in=androidx.compose.ui.test.ExperimentalTestApi",
    )
  }
}

dependencies {
  // Interface depends on compose-ui (SemanticsNode, ImageBitmap). Just `ui`, not the
  // `compose.desktop.currentOs` aggregator: that one adds foundation, material and animation, which
  // a driver never touches, and every one of them lands in the CLI JAR.
  api(compose.ui)

  // DriverDispatch marker — ComposeTestTarget implements it to declare the
  // "dispatch action, then settle" contract shared with the other driver managers.
  api(project(":trailblaze-models"))

  // ComposeUiTestTarget wraps ComposeUiTest — callers must provide this dependency
  compileOnly(libs.compose.ui.test.junit4)
}
