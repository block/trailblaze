import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UberJarForCurrentOsTest {
  // The launcher and install scripts glob `Trailblaze-<os>-<arch>-*.jar`, so these spellings are a
  // file-name contract, not cosmetics.
  @Test
  fun `names each supported host the way the shipped JAR names it`() {
    assertEquals("macos-arm64", currentOsTargetId("Mac OS X", "aarch64"))
    assertEquals("linux-x64", currentOsTargetId("Linux", "amd64"))
    assertEquals("linux-arm64", currentOsTargetId("Linux", "aarch64"))
    assertEquals("macos-x64", currentOsTargetId("Mac OS X", "x86_64"))
    assertEquals("windows-x64", currentOsTargetId("Windows 11", "amd64"))
  }

  @Test
  fun `fails the build on a host it cannot name rather than inventing a name`() {
    assertFailsWith<IllegalStateException> { currentOsTargetId("SunOS", "amd64") }
    assertFailsWith<IllegalStateException> { currentOsTargetId("Linux", "riscv64") }
  }
}
