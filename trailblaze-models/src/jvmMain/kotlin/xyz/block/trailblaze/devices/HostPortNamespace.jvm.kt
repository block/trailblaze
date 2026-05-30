package xyz.block.trailblaze.devices

actual object HostPortNamespace {
  actual val current: String by lazy {
    System.getenv("ANDROID_ADB_SERVER_PORT") ?: ""
  }
}
