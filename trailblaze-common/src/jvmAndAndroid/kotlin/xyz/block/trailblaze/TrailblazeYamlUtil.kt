package xyz.block.trailblaze

import xyz.block.trailblaze.rules.TestStackTraceUtil

object TrailblazeYamlUtil {

  fun TestStackTraceUtil.TestMethodInfo.calculateTrailblazeYamlAssetPathFromStackTrace() = "trails/$simpleClassName/$methodName.trail.yaml"

  fun TestStackTraceUtil.TestMethodInfo.calculateTrailblazeYamlAssetWithPackagePathFromStackTrace() = "trails/$packageName/$simpleClassName/$methodName.trail.yaml"

/** The path reflects the nested structure of the test's package and class hierarchy.
   * Example: For a test method `testFoo` in class `com.example.tests.MyTest`, the generated path will be:
   * trails/com/example/tests/MyTest/testFoo.trail.yaml
   * This helps Trailblaze locate test-specific YAML assets based on their source location.
   **/
  fun TestStackTraceUtil.TestMethodInfo.calculateTrailblazeYamlAssetPathWithNestedStructure(): String {
    val packageAsPath = packageName.replace(".", "/")
    return "trails/$packageAsPath/$simpleClassName/$methodName.trail.yaml"
  }

  fun calculateTrailblazeYamlAssetPathFromStackTrace(doesFileExistAtPath: (String) -> Boolean): String {
    val testMethod = TestStackTraceUtil.getJUnit4TestMethodFromCurrentStacktrace()
    val possibleAssetPaths = listOf(
      testMethod.calculateTrailblazeYamlAssetWithPackagePathFromStackTrace(),
      testMethod.calculateTrailblazeYamlAssetPathFromStackTrace(),
      testMethod.calculateTrailblazeYamlAssetPathWithNestedStructure(),
    )
    possibleAssetPaths.forEach { assetPath ->
      if (doesFileExistAtPath(assetPath)) {
        return assetPath
      }
    }
    error("Could not locate asset at any of the following paths: $possibleAssetPaths")
  }

  fun calculateTrailblazeYamlAssetWithPackagePathFromStackTrace() = TestStackTraceUtil.getJUnit4TestMethodFromCurrentStacktrace()
    .calculateTrailblazeYamlAssetWithPackagePathFromStackTrace()
}
