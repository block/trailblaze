package xyz.block.trailblaze.ui.images

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import xyz.block.trailblaze.ui.resolveScreenshot

/**
 * WASM-specific implementation of NetworkImageLoader that uses lazy loading.
 * Images are resolved on-demand when the composable renders, not upfront.
 */
class WasmNetworkImageLoader : ImageLoader {

    override fun getImageModel(sessionId: String, screenshotFile: String?): Any? {
        // Return the image key as-is - resolution happens in the composable
        return screenshotFile
    }
}

/**
 * Wrapper around NetworkImageLoader.getImageModel that lazily resolves image keys
 * on first composition. Uses produceState for proper async state management.
 *
 * Usage:
 * ```
 * val imageModel = lazyResolveImage(sessionId, screenshotFile)
 * if (imageModel != null) {
 *     AsyncImage(model = imageModel, ...)
 * }
 * ```
 */
@Composable
fun lazyResolveImage(sessionId: String, screenshotFile: String?): String? {
    // Return null immediately if no screenshot file
    if (screenshotFile == null) {
        return null
    }

    // Skip resolution if already a data URL or remote URL
    if (screenshotFile.startsWith("data:") ||
        screenshotFile.startsWith("http://") ||
        screenshotFile.startsWith("https://")
    ) {
        return screenshotFile
    }

    // Use remember with key to maintain state across recompositions for this specific file
    var resolvedUrl by remember(screenshotFile) { mutableStateOf<String?>(null) }

    // Launch resolution when the composable first appears
    LaunchedEffect(screenshotFile) {
        if (resolvedUrl == null) {
            try {
                val shortName = screenshotFile.takeLast(30)
                println("🔍 lazyResolveImage: Starting resolution for $shortName")
                val resolved = resolveScreenshot(screenshotFile)
                if (resolved != null) {
                    println("🎨 lazyResolveImage: Got dataURL from resolveScreenshot, updating state for $shortName")
                    resolvedUrl = resolved
                    println("✅ lazyResolveImage: State updated! resolvedUrl is now: ${resolved.take(60)}...")
                } else {
                    println("⚠️ lazyResolveImage: Resolution returned null for $shortName")
                }
            } catch (e: Exception) {
                println("❌ lazyResolveImage: Failed to resolve image: ${screenshotFile.takeLast(30)} - ${e.message}")
                e.printStackTrace()
            }
        } else {
            println("♻️ lazyResolveImage: Already resolved for ${screenshotFile.takeLast(30)}, returning cached")
        }
    }

    val returnValue = resolvedUrl
    println(
        "📤 lazyResolveImage: Returning ${if (returnValue == null) "null" else "data URL (${returnValue.take(50)}...)"} for ${
            screenshotFile.takeLast(
                30
            )
        }"
    )
    return returnValue
}

/**
 * Alternative implementation using produceState for better async state handling.
 * This might handle recomposition more reliably than LaunchedEffect.
 */
@Composable
fun lazyResolveImageV2(sessionId: String, screenshotFile: String?): String? {
    // Return null immediately if no screenshot file
    if (screenshotFile == null) {
        return null
    }

    // Skip resolution if already a data URL or remote URL
    if (screenshotFile.startsWith("data:") ||
        screenshotFile.startsWith("http://") ||
        screenshotFile.startsWith("https://")
    ) {
        return screenshotFile
    }

    // Use produceState which is designed for async operations
    val state: State<String?> = produceState<String?>(initialValue = null, screenshotFile) {
        try {
            val shortName = screenshotFile.takeLast(30)
            println("🔍 lazyResolveImageV2: Starting resolution for $shortName")
            val resolved = resolveScreenshot(screenshotFile)
            if (resolved != null) {
                println("🎨 lazyResolveImageV2: Got dataURL, updating state for $shortName")
                value = resolved
                println("✅ lazyResolveImageV2: State updated! value is now: ${resolved.take(60)}...")
            } else {
                println("⚠️ lazyResolveImageV2: Resolution returned null for $shortName")
            }
        } catch (e: Exception) {
            println("❌ lazyResolveImageV2: Failed to resolve image: ${screenshotFile.takeLast(30)} - ${e.message}")
            e.printStackTrace()
        }
    }

    val returnValue = state.value
    println(
        "📤 lazyResolveImageV2: Returning ${if (returnValue == null) "null" else "data URL (${returnValue.take(50)}...)"} for ${
            screenshotFile.takeLast(
                30
            )
        }")
    return returnValue
}
