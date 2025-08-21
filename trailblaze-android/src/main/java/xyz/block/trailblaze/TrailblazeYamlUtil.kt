package xyz.block.trailblaze

import xyz.block.trailblaze.rules.TestStackTraceUtil

object TrailblazeYamlUtil {

  fun TestStackTraceUtil.TestMethodInfo.calculateTrailblazeYamlAssetPathFromStackTrace() = "trails/$simpleClassName/$methodName.trail.yaml"

  fun TestStackTraceUtil.TestMethodInfo.calculateTrailblazeYamlAssetWithPackagePathFromStackTrace() = "trails/$packageName/$simpleClassName/$methodName.trail.yaml"

  fun calculateTrailblazeYamlAssetPathFromStackTrace(): String {
    val testMethod = TestStackTraceUtil.getJUnit4TestMethodFromCurrentStacktrace()
    val possibleAssetPaths = listOf(
      testMethod.calculateTrailblazeYamlAssetWithPackagePathFromStackTrace(),
      testMethod.calculateTrailblazeYamlAssetPathFromStackTrace(),
    )
    possibleAssetPaths.forEach { assetPath ->
      if (AndroidAssetsUtil.assetExists(assetPath)) {
        return assetPath
      }
    }
    error("Could not locate asset at any of the following paths: $possibleAssetPaths")
  }

  fun calculateTrailblazeYamlAssetWithPackagePathFromStackTrace() = TestStackTraceUtil.getJUnit4TestMethodFromCurrentStacktrace()
    .calculateTrailblazeYamlAssetWithPackagePathFromStackTrace()
}
