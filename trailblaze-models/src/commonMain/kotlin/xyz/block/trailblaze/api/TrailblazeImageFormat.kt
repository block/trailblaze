package xyz.block.trailblaze.api

/**
 * Represents the format of an image file.
 *
 * @property formatName The name of the format (e.g., "PNG", "JPEG")
 * @property fileExtension The file extension for this format (e.g., "png", "jpg")
 */
enum class TrailblazeImageFormat(
  val formatName: String,
  val fileExtension: String,
  val mimeType: String,
) {
  PNG("PNG", "png", "image/png"),
  JPEG("JPEG", "jpg", "image/jpeg"),
}
