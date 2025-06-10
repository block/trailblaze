package xyz.block.trailblaze.recording

import org.junit.runner.Description
import xyz.block.trailblaze.util.TemplatingUtil
import java.security.MessageDigest
import kotlin.text.format

object TrailblazeResourceCacheUtil {

  private fun hashString(input: String, algorithm: String = "SHA-256"): String {
    val bytes = MessageDigest
      .getInstance(algorithm)
      .digest(input.toByteArray())

    val hash = bytes.joinToString("") { "%02x".format(it) }

    // Return the first 7 characters of the hash
    return hash.substring(0, 7)
  }

  fun getFilenameWithExtensionForCache(testDescription: Description, prompt: String, key: String? = null): String = getFilenameWithoutExtensionForCache(testDescription, prompt, key) + ".yaml"

  fun cleanKey(key: String?): String? = key?.replace(Regex("[^a-zA-Z0-9]"), "_")?.lowercase()

  fun getKeyOrHash(key: String?, prompt: String): String = cleanKey(key) ?: hashString(prompt)

  fun getFilenameWithoutExtensionForCache(testDescription: Description, prompt: String, key: String? = null): String {
    val computedKey = getKeyOrHash(
      key = key,
      prompt = prompt,
    )
    return "${testDescription.className}_${testDescription.methodName}-$computedKey"
  }

  fun readCacheForTest(filenameWithoutExtensionForCache: String): String? {
    val resourceFileName = "$TRAILBLAZE_RECORDINGS_DIR_NAME/$filenameWithoutExtensionForCache.yaml"
    val yaml: String? = TemplatingUtil.getResourceAsText(resourceFileName)
    return yaml
  }

  const val TRAILBLAZE_RECORDINGS_DIR_NAME = "trailblaze_recordings"
}
