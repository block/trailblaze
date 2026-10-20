package xyz.block.trailblaze.quickjs.tools

import xyz.block.trailblaze.util.HostPlatform

internal actual fun hostPlatformOrNull(): QuickJsHostPlatform? =
  HostPlatform.current().let { QuickJsHostPlatform(os = it.os.hostOsName, arch = it.arch?.hostArchName) }
