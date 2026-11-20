package xyz.block.trailblaze.toolcalls.commands.camera

// Both camera image tools are host-only (requiresHost), so these never run on a device.
private fun hostOnly(): Nothing = error("saveScreenshot and setCameraImage run on the host only.")

internal actual fun readImageFile(path: String): ByteArray = hostOnly()

internal actual fun requireExistingFile(path: String, name: String): Unit = hostOnly()

internal actual fun requireDifferentFiles(image: String, cameraFile: String): Unit = hostOnly()

internal actual fun writeImageAsPng(imageBytes: ByteArray, destination: String, fitToCameraFrame: Boolean): Unit =
  hostOnly()
