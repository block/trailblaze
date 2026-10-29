package xyz.block.trailblaze.util

/**
 * The computer a host-side scripted tool runs on, which it reads as `ctx.host.os` and
 * `ctx.host.arch`. Only meaningful on the host JVM: on an Android device `os.name` reads `Linux`,
 * so on-device runtimes leave both null instead of building one of these.
 *
 * @property arch The JVM's architecture, or null for one [HostArch] doesn't name. An x64 JVM on
 *   Windows on Arm reads [HostArch.X64], which is what the programs it runs must be.
 */
data class HostPlatform(val os: DesktopOsType, val arch: HostArch?) {
  companion object {
    fun current(): HostPlatform = HostPlatform(
      os = DesktopOsType.current(),
      arch = HostArch.fromOsArch(System.getProperty("os.arch").orEmpty()),
    )
  }
}

/** The CPU architectures a host JVM reports, under the name scripted tools read as `ctx.host.arch`. */
enum class HostArch(val hostArchName: String, private val osArchNames: Set<String>) {
  ARM64("arm64", setOf("aarch64", "arm64")),
  X64("x64", setOf("amd64", "x86_64", "x64")),
  ;

  companion object {
    /** The architecture for a JVM `os.arch` value, or null for one Trailblaze doesn't name. */
    fun fromOsArch(osArch: String): HostArch? = osArch.lowercase().let { arch -> entries.firstOrNull { arch in it.osArchNames } }
  }
}
