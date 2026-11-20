package xyz.block.trailblaze.toolcalls.commands.camera

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import javax.imageio.ImageIO

internal actual fun readImageFile(path: String): ByteArray {
  requireExistingFile(path, "image")
  return Files.readAllBytes(Paths.get(path))
}

internal actual fun requireExistingFile(path: String, name: String) {
  val file = absolute(path, name)
  require(Files.isRegularFile(file, NOFOLLOW_LINKS)) { "$name $path is not an existing file." }
}

internal actual fun requireDifferentFiles(image: String, cameraFile: String) {
  require(!Files.isSameFile(Paths.get(image), Paths.get(cameraFile))) {
    "image is the camera file itself; save the screen to another file first."
  }
}

internal actual fun writeImageAsPng(imageBytes: ByteArray, destination: String, fitToCameraFrame: Boolean) {
  // ImageIO reads PNG and JPEG; the webp-imageio plugin on the JVM classpath reads WebP.
  val decoded = ImageIO.read(ByteArrayInputStream(imageBytes))
    ?: error("the image is not a PNG, JPEG or WebP.")
  val image = if (fitToCameraFrame) inCameraFrame(decoded) else decoded
  val target = absolute(destination, "destination")
  val directory = target.parent
  require(Files.isDirectory(directory)) { "the directory $directory does not exist." }
  val staged = Files.createTempFile(directory, ".${target.fileName}-", ".png")
  try {
    check(ImageIO.write(image, "png", staged.toFile())) { "no PNG writer is available." }
    Files.move(staged, target, ATOMIC_MOVE, REPLACE_EXISTING)
  } finally {
    Files.deleteIfExists(staged)
  }
}

/**
 * The emulator's camera (checked on emulator 37.2.13, API 29 and 36) fills its frame from a 4:3
 * image but shows only the left 3:4 portrait slice of it, at full height. So the image goes,
 * centred, into that slice of a white 4:3 canvas; an emulator that shows the whole frame still
 * shows all of it, just left of centre.
 */
private fun inCameraFrame(image: BufferedImage): BufferedImage {
  val height = maxOf(image.height, ceilDiv(image.width * 4, 3))
  val sliceWidth = ceilDiv(height * 3, 4)
  return BufferedImage(ceilDiv(height * 4, 3), height, BufferedImage.TYPE_INT_RGB).also { canvas ->
    val graphics = canvas.createGraphics()
    try {
      graphics.color = Color.WHITE
      graphics.fillRect(0, 0, canvas.width, canvas.height)
      graphics.drawImage(image, (sliceWidth - image.width) / 2, (height - image.height) / 2, null)
    } finally {
      graphics.dispose()
    }
  }
}

private fun ceilDiv(numerator: Int, denominator: Int) = (numerator + denominator - 1) / denominator

private fun absolute(path: String, name: String): Path =
  Paths.get(path).also { require(it.isAbsolute) { "$name must be an absolute path, got $path." } }
