package xyz.block.trailblaze.recordings

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Coverage for [TrailRecordings.shortTrailName] — the `trails/`-relative shortening used by
 * the Sessions list and the HTML/JSON reports. The regression this guards against: a trail run
 * through the CLI records `file.absolutePath`, which doesn't start with a literal `trails/`, so
 * the old `removePrefix("trails/")` was a no-op and the whole absolute path leaked into the UI.
 */
class TrailRecordingsShortNameTest {

  @Test
  fun `strips up to and including the last trails segment of an absolute path`() {
    assertEquals(
      "ExperimentalIosTests/set_feature_flag",
      TrailRecordings.shortTrailName(
        "/var/ci/workspace/checkout/src/test/resources/trails/ExperimentalIosTests/" +
          "set_feature_flag.trail.yaml",
      ),
    )
  }

  @Test
  fun `strips a literal leading trails prefix on a relative path`() {
    assertEquals(
      "EvaluationLongTest/tenKey",
      TrailRecordings.shortTrailName("trails/EvaluationLongTest/tenKey.trail.yaml"),
    )
  }

  @Test
  fun `preserves nested structure under trails`() {
    assertEquals(
      "xyz/block/trailblaze/square/staging/banking/regression/" +
        "BankingRegressionSampleLongTest/sample_regression_test",
      TrailRecordings.shortTrailName(
        "/ci/trails/xyz/block/trailblaze/square/staging/banking/regression/" +
          "BankingRegressionSampleLongTest/sample_regression_test.trail.yaml",
      ),
    )
  }

  @Test
  fun `falls back to suffix-only stripping when there is no trails segment`() {
    // Best-effort: no `trails/` directory in the path, so we can only drop the suffix.
    assertEquals(
      "/some/other/place/foo",
      TrailRecordings.shortTrailName("/some/other/place/foo.trail.yaml"),
    )
  }

  @Test
  fun `uses the LAST trails segment when the word appears more than once`() {
    assertEquals(
      "Login/case_1",
      TrailRecordings.shortTrailName("/trails/archive/trails/Login/case_1.trail.yaml"),
    )
  }

  @Test
  fun `normalizes Windows backslash separators before shortening`() {
    // File.absolutePath on Windows uses backslashes; without normalization the whole
    // machine-specific path would leak (no "/trails/" to match).
    assertEquals(
      "ExperimentalIosTests/set_feature_flag",
      TrailRecordings.shortTrailName(
        "C:\\repo\\src\\test\\resources\\trails\\ExperimentalIosTests\\set_feature_flag.trail.yaml",
      ),
    )
  }

  // --- deriveTestIdentityFromTrailPath -------------------------------------------------

  @Test
  fun `derives parent directory as suite and file basename as method, verbatim`() {
    val identity = TrailRecordings.deriveTestIdentityFromTrailPath(
      "/ci/.../trails/ExperimentalIosTests/set_feature_flag.trail.yaml",
      fallbackClassName = "Trailblaze",
    )
    assertEquals("ExperimentalIosTests", identity.className)
    assertEquals("set_feature_flag", identity.methodName)
  }

  @Test
  fun `preserves authored camelCase in the suite name`() {
    // The directory name is already readable — it must not be collapsed (e.g. to
    // "Multisteploginflow") by a case normalizer.
    val identity = TrailRecordings.deriveTestIdentityFromTrailPath(
      "trails/MultiStepLoginFlow/login_recorded.trail.yaml",
      fallbackClassName = "Trailblaze",
    )
    assertEquals("MultiStepLoginFlow", identity.className)
    assertEquals("login_recorded", identity.methodName)
  }

  @Test
  fun `uses only the immediate parent directory for deeply nested trails`() {
    val identity = TrailRecordings.deriveTestIdentityFromTrailPath(
      "/ci/trails/xyz/block/square/staging/banking/regression/" +
        "BankingRegressionSampleLongTest/sample_regression_test.trail.yaml",
      fallbackClassName = "Trailblaze",
    )
    assertEquals("BankingRegressionSampleLongTest", identity.className)
    assertEquals("sample_regression_test", identity.methodName)
  }

  @Test
  fun `falls back to the provided class name when there is no parent segment`() {
    val identity = TrailRecordings.deriveTestIdentityFromTrailPath(
      "trails/lonely.trail.yaml",
      fallbackClassName = "HostAccessibilityV3",
    )
    assertEquals("HostAccessibilityV3", identity.className)
    assertEquals("lonely", identity.methodName)
  }

  @Test
  fun `derives identity from a Windows path with backslash separators`() {
    val identity = TrailRecordings.deriveTestIdentityFromTrailPath(
      "C:\\repo\\trails\\ExperimentalIosTests\\set_feature_flag.trail.yaml",
      fallbackClassName = "Trailblaze",
    )
    assertEquals("ExperimentalIosTests", identity.className)
    assertEquals("set_feature_flag", identity.methodName)
  }

  @Test
  fun `falls back to class name and run when the path yields no usable segments`() {
    // A path that shortens to "" (e.g. the trails/ root itself) has neither a suite nor a
    // method segment, so both fall back: the provided class name and the literal "run".
    val identity = TrailRecordings.deriveTestIdentityFromTrailPath(
      "/ci/trails/",
      fallbackClassName = "ComposeRpc",
    )
    assertEquals("ComposeRpc", identity.className)
    assertEquals("run", identity.methodName)
  }
}
