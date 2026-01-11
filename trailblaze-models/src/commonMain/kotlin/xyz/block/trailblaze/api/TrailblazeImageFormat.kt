package xyz.block.trailblaze.api

/**
 * Represents the format of an image file.
 *
 * @property formatName The name of the format (e.g., "PNG", "JPEG")
 * @property fileExtension The file extension for this format (e.g., "png", "jpg")
 * @property mimeSubtype The mime subtype for this format (e.g., "png", "jpeg")
 */
enum class TrailblazeImageFormat(
  val formatName: String,
  val fileExtension: String,
  val mimeSubtype: String,
  val mimeType: String = "image/$mimeSubtype"
) {
  PNG(
    formatName = "PNG",
    fileExtension = "png",
    mimeSubtype = "png"
  ),
  JPEG(
    formatName = "JPEG",
    fileExtension = "jpg",
    mimeSubtype = "jpeg"
  )
}
