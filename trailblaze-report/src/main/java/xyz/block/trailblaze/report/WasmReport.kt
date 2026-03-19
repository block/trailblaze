@file:OptIn(ExperimentalEncodingApi::class)

package xyz.block.trailblaze.report

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.HasScreenshot
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.report.utils.LogsRepo
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.Writer
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import xyz.block.trailblaze.util.Console

/**
 * Data for a single session that can be lazy-loaded
 */
data class PerSessionData(
  val logs: List<TrailblazeLog>,
  val imageKeys: List<String>,
)

/**
 * Generates the Trailblaze report in a WASM-compatible HTML format.
 */
object WasmReport {

  /** Low FPS for WASM embedded frames to keep report file size reasonable. */
  private const val WASM_VIDEO_FPS = 2

  private val compactJsonReformatter = Json {
    prettyPrint = false
    ignoreUnknownKeys = true
  }

  private fun compactJson(prettyJson: String): String {
    val jsonElement = compactJsonReformatter.parseToJsonElement(prettyJson)
    return compactJsonReformatter.encodeToString(JsonElement.serializer(), jsonElement)
  }

  fun generate(
    logsRepo: LogsRepo,
    trailblazeUiProjectDir: File,
    outputFile: File,
    reportTemplateFile: File,
    useRelativeImageUrls: Boolean = false,
  ) {
    Console.log("Encoding JSON data...")
    val allSessionIds = logsRepo.getSessionIds()
    val sessionToImageFiles = allSessionIds.associateWith { logsRepo.getImagesForSession(it) }

    Console.log("Generating lightweight session info metadata...")
    val skippedSessions = mutableListOf<SessionId>()
    val sessionInfoMap = allSessionIds.mapNotNull { sessionId ->
      val sessionInfo = logsRepo.getSessionInfo(sessionId)
      if (sessionInfo == null) {
        Console.log("  ⚠️  WARNING: Skipping session '$sessionId' - no session info available")
        skippedSessions.add(sessionId)
        null
      } else {
        sessionId to sessionInfo
      }
    }.toMap()

    if (skippedSessions.isNotEmpty()) {
      Console.log("  ⚠️  Skipped ${skippedSessions.size} session(s) due to missing session info:")
      skippedSessions.forEach { Console.log("     - $it") }
    }

    // Only include valid sessions in the report
    val validSessionIds = allSessionIds.filter { it !in skippedSessions }
    val sessionJson = compactJson(TrailblazeJsonInstance.encodeToString(validSessionIds.map { it.value }))
    val sessionInfoJson = compactJson(TrailblazeJsonInstance.encodeToString(sessionInfoMap.mapKeys { it.key.value }))

    // Only build data for sessions that have valid session info
    val perSessionData = buildPerSessionData(logsRepo, validSessionIds, sessionToImageFiles)

    // Extract video frames for embedding in WASM reports (trimmed to test execution window)
    Console.log("\nExtracting video frames for WASM embedding...")
    val (videoFrameImages, videoTrimInfo) = extractVideoFrames(logsRepo, validSessionIds)

    // Load capture_metadata.json for each session, adjusting timestamps to trimmed window
    Console.log("\nLoading capture metadata...")
    val captureMetadataMap = loadCaptureMetadata(logsRepo, validSessionIds, videoTrimInfo)
    val compressedCaptureMetadata = captureMetadataMap.mapValues { (_, json) ->
      compressStringToBase64(json)
    }

    // When useRelativeImageUrls is true, skip image embedding entirely so the report stays
    // small. This is useful for very large CI runs where embedding hundreds of screenshots
    // would produce a multi-gigabyte HTML file.
    val sessionToImageFilesStr = if (useRelativeImageUrls) {
      Console.log("⚠️  useRelativeImageUrls=true: skipping image embedding in report")
      emptyMap()
    } else {
      sessionToImageFiles.mapKeys { it.key.value }
    }

    if (reportTemplateFile.exists()) {
      Console.log("Generating report from template: ${reportTemplateFile.absolutePath}")
      generateFromTemplate(
        reportTemplateFile = reportTemplateFile,
        reportOutputFile = outputFile,
        sessionJson = sessionJson,
        sessionInfoJson = sessionInfoJson,
        sessionToImageFiles = sessionToImageFilesStr,
        perSessionData = perSessionData,
        videoFrameImages = videoFrameImages,
        compressedCaptureMetadata = compressedCaptureMetadata,
      )
    } else {
      Console.log("Generating report from raw WASM UI build artifacts...")
      generateRaw(
        trailblazeUiProjectDir = trailblazeUiProjectDir,
        reportOutputFile = outputFile,
        sessionJson = sessionJson,
        sessionInfoJson = sessionInfoJson,
        sessionToImageFiles = sessionToImageFilesStr,
        perSessionData = perSessionData,
        videoFrameImages = videoFrameImages,
        compressedCaptureMetadata = compressedCaptureMetadata,
      )
    }

    Console.log("\n✅ Success!")
    Console.log("Embedded HTML created at: ${outputFile.absolutePath}")
    Console.log("Final file size: ${outputFile.length() / 1024}KB")
  }

  private fun buildPerSessionData(
    logsRepo: LogsRepo,
    sessionIds: List<SessionId>,
    sessionToImageFiles: Map<SessionId, List<File>>,
  ): Map<String, PerSessionData> = sessionIds.associate { sessionId ->
    val logs = logsRepo.getLogsForSession(sessionId)
    val imageFiles = sessionToImageFiles[sessionId] ?: emptyList()
    val logsWithKeys = replaceScreenshotPathsWithImageKeys(logs, sessionId.value)

    sessionId.value to PerSessionData(
      logs = logsWithKeys,
      imageKeys = imageFiles.map { "${sessionId.value}/${it.name}" },
    )
  }

  private fun compressPerSessionData(
    perSessionData: Map<String, PerSessionData>,
  ): Map<String, String> {
    val startTime = System.currentTimeMillis()

    val compressedLogs = runBlocking(Dispatchers.Default) {
      perSessionData.entries.map { (sessionId, data) ->
        async {
          val logsJson = compactJson(TrailblazeJsonInstance.encodeToString(data.logs))
          sessionId to compressStringToBase64(logsJson)
        }
      }.awaitAll().toMap()
    }

    val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
    Console.log("  ✅ Compressed ${perSessionData.size} sessions in ${elapsed.toInt()}s")

    return compressedLogs
  }

  private fun generateFromTemplate(
    reportTemplateFile: File,
    reportOutputFile: File,
    sessionJson: String,
    sessionInfoJson: String,
    sessionToImageFiles: Map<String, List<File>>,
    perSessionData: Map<String, PerSessionData>,
    videoFrameImages: Map<String, ByteArray> = emptyMap(),
    compressedCaptureMetadata: Map<String, String> = emptyMap(),
  ) {
    Console.log("Generating report from template: ${reportTemplateFile.absolutePath}")

    val compressedSessionJson = compressStringToBase64(sessionJson)
    val compressedSessionInfoJson = compressStringToBase64(sessionInfoJson)

    val compressedPerSessionLogs = compressPerSessionData(perSessionData)

    Console.log("\nCompressing and encoding images...")
    val compressedImages = compressImages(sessionToImageFiles, videoFrameImages)

    // The template already contains working WASM loader, webpack patch, and JS loader scripts
    // from the generateRaw() build step. We only need to replace the compressed data block
    // with one that includes the real session data. All per-session data (logs, images)
    // is inlined directly in the object literal so it's available synchronously before the
    // WASM app starts — no chunk loading or chunksReady mechanism needed.
    // Recording YAML is generated on-the-fly in the Wasm UI from the session logs.

    // Read the template into memory. The template itself is small (just HTML + WASM/JS bundles,
    // typically a few MB). The session data block (especially images) can be several GB on large
    // CI runs, so we must NOT accumulate the full result as a String — that would hit Java's
    // 2 GB array size limit. Instead, we apply all removals to the small template first, find
    // the insertion point, then stream-write the three parts directly to disk.
    var templateContent = reportTemplateFile.readText()

    // Strip any legacy chunk scripts from template (loadChunkById, octet-stream tags, etc.).
    // These patterns target the original template HTML only (never the data block), so applying
    // them before inserting the data block is safe and keeps the template small.
    val newChunkBlockRegex =
      Regex("""<!-- TRAILBLAZE_CHUNKS_START -->[\s\S]*?<!-- TRAILBLAZE_CHUNKS_END -->\s*""")
    val oldChunkBlockRegex =
      Regex("""<script>\s*\n?\s*window\.loadChunkById[\s\S]*?window\.chunksReady\s*=\s*true;[\s\S]*?</script>\s*\n?""")
    val octetStreamRegex =
      Regex("""<script\s+type="application/octet-stream"\s+id="chunk-[^"]*">[^<]*</script>\s*""")
    templateContent = templateContent.replace(newChunkBlockRegex, "")
    templateContent = templateContent.replace(oldChunkBlockRegex, "")
    templateContent = templateContent.replace(octetStreamRegex, "")

    // Locate the placeholder in the cleaned template.
    Console.log("\nReplacing compressed data block with new session data...")
    val compressedDataRegex = """window\.trailblaze_report_compressed\s*=\s*\{[\s\S]*?\};""".toRegex()
    val matchResult = compressedDataRegex.find(templateContent)

    if (matchResult == null) {
      Console.log("⚠️  WARNING: Could not find window.trailblaze_report_compressed in template!")
    }

    // Stream-write the output in three parts to avoid building a 2 GB+ String in memory:
    //   1. Template content before the data-block placeholder.
    //   2. The data block, written entry-by-entry via writeCompressedDataBlock().
    //   3. Template content after the placeholder.
    reportOutputFile.bufferedWriter(Charsets.UTF_8).use { writer ->
      if (matchResult == null) {
        // No placeholder found — write template as-is (graceful degradation).
        writer.write(templateContent)
      } else {
        val afterMatchStart = matchResult.range.last + 1
        // Part 1: everything before the placeholder.
        writer.write(templateContent, 0, matchResult.range.first)
        // Part 2: data block (images written one entry at a time — never one giant String).
        writeCompressedDataBlock(
          writer = writer,
          compressedSessionJson = compressedSessionJson,
          compressedSessionInfoJson = compressedSessionInfoJson,
          compressedPerSessionLogs = compressedPerSessionLogs,
          compressedImages = compressedImages,
          compressedCaptureMetadata = compressedCaptureMetadata,
          imageAliases = imageAliases,
        )
        // Part 3: everything after the placeholder.
        if (afterMatchStart < templateContent.length) {
          writer.write(templateContent, afterMatchStart, templateContent.length - afterMatchStart)
        }
      }
    }
  }

  private fun generateRaw(
    trailblazeUiProjectDir: File,
    reportOutputFile: File,
    sessionJson: String,
    sessionInfoJson: String,
    sessionToImageFiles: Map<String, List<File>>,
    perSessionData: Map<String, PerSessionData>,
    videoFrameImages: Map<String, ByteArray> = emptyMap(),
    compressedCaptureMetadata: Map<String, String> = emptyMap(),
  ) {
    Console.log("Generating report from wasm ui build artifacts: ${trailblazeUiProjectDir.absolutePath}")

    require(trailblazeUiProjectDir.exists() && trailblazeUiProjectDir.isDirectory) {
      "Project directory does not exist or is not a directory: ${trailblazeUiProjectDir.canonicalPath}"
    }

    val buildDir = File(trailblazeUiProjectDir, "build/kotlin-webpack/wasmJs/productionExecutable")
    val resourcesDir = File(trailblazeUiProjectDir, "src/wasmJsMain/resources")
    val outputDir = File(trailblazeUiProjectDir, "build/embedded")

    outputDir.mkdirs()

    val indexHtmlFile = File(resourcesDir, "index.html")
    require(indexHtmlFile.exists()) { "index.html not found at ${indexHtmlFile.absolutePath}" }

    val jsFile = File(buildDir, "composeApp.js")
    require(jsFile.exists()) { "composeApp.js not found at ${jsFile.absolutePath}" }

    val wasmFiles = buildDir.listFiles { _, name -> name.endsWith(".wasm") } ?: emptyArray()
    require(wasmFiles.isNotEmpty()) { "No WASM files found in ${buildDir.absolutePath}" }

    Console.log("Processing files:")
    Console.log("  JS: ${jsFile.name} (${jsFile.length() / 1024}KB)")
    wasmFiles.forEach { Console.log("  WASM: ${it.name} (${it.length() / 1024}KB)") }

    Console.log("\nCompressing and encoding lightweight metadata...")
    val compressedSessionJson = compressStringToBase64(sessionJson)
    val compressedSessionInfoJson = compressStringToBase64(sessionInfoJson)

    Console.log("\nCompressing per-session data...")
    val compressedPerSessionLogs = compressPerSessionData(perSessionData)

    val compressedImages = compressImages(sessionToImageFiles, videoFrameImages)

    val htmlTemplate = indexHtmlFile.readText()
      .replace(
        "window.trailblaze_report = {};",
        buildString {
          appendLine("window.trailblaze_report = {};")
          appendLine(createCompressedDataBlock(
            compressedSessionJson,
            compressedSessionInfoJson,
            compressedPerSessionLogs,
            compressedImages,
            compressedCaptureMetadata,
            imageAliases,
          ))
        },
      )
      .replace(
        "window.trailblaze_report_compressed = {};",
        "// window.trailblaze_report_compressed already initialized above",
      )

    Console.log("\nCompressing and encoding WASM files...")
    val wasmData = runBlocking(Dispatchers.IO) {
      wasmFiles.map { file ->
        async {
          val bytes = file.readBytes()
          val compressed = compressBytes(bytes)
          val encoded = Base64.encode(compressed)
          file.name to encoded
        }
      }.awaitAll().toMap()
    }

    Console.log("\nCompressing JavaScript bundle...")
    val jsContent = jsFile.readText()
    val compressedJsBase64 = compressStringToBase64(jsContent)

    Console.log("Creating embedded HTML...")
    val embeddedHtml = createEmbeddedHtml(htmlTemplate, compressedJsBase64, wasmData)

    reportOutputFile.writeText(embeddedHtml)
  }

  private fun createEmbeddedHtml(
    template: String,
    compressedJsBase64: String,
    wasmData: Map<String, String>,
  ): String {
    val jsScriptRegex = """<script\s+type="application/javascript"\s+src="composeApp\.js"></script>""".toRegex()

    val replaced = template.replace(jsScriptRegex) {
      """
        ${createWasmLoaderScript(wasmData)}
        ${createWebpackPatchScript()}
        ${createJsLoaderScript(compressedJsBase64, wasmData)}
      """.trimIndent()
    }

    // Check if the compressed data was already injected (it should be in generateRaw path)
    if (!replaced.contains("window.trailblaze_report_compressed")) {
      Console.log("⚠️  WARNING: window.trailblaze_report_compressed not found in template after script replacement!")
    }

    return replaced
  }

  private fun createCompressedDataBlock(
    compressedSessionJson: String,
    compressedSessionInfoJson: String,
    compressedPerSessionLogs: Map<String, String> = emptyMap(),
    compressedImages: Map<String, String> = emptyMap(),
    compressedCaptureMetadata: Map<String, String> = emptyMap(),
    imageAliases: Map<String, String> = emptyMap(),
  ): String = buildString {
    append("window.trailblaze_report_compressed = {\n")
    append("  sessions: \"$compressedSessionJson\",\n")
    append("  session_info: \"$compressedSessionInfoJson\",\n")
    append("  session_detail: null,\n")
    append("  per_session_logs: {")
    append(compressedPerSessionLogs.entries.joinToString(",") { (id, data) ->
      val escapedId = id.replace("\\", "\\\\").replace("\"", "\\\"")
      "\"$escapedId\":\"$data\""
    })
    append("},\n")
    append("  capture_metadata: {")
    append(compressedCaptureMetadata.entries.joinToString(",") { (id, data) ->
      val escapedId = id.replace("\\", "\\\\").replace("\"", "\\\"")
      "\"$escapedId\":\"$data\""
    })
    append("},\n")
    append("  images: {")
    append(compressedImages.entries.joinToString(",") { (key, data) ->
      val escapedKey = key.replace("\\", "\\\\").replace("\"", "\\\"")
      "\"$escapedKey\":\"$data\""
    })
    append("},\n")
    append("  image_aliases: {")
    append(imageAliases.entries.joinToString(",") { (alias, canonical) ->
      val escapedAlias = alias.replace("\\", "\\\\").replace("\"", "\\\"")
      val escapedCanonical = canonical.replace("\\", "\\\\").replace("\"", "\\\"")
      "\"$escapedAlias\":\"$escapedCanonical\""
    })
    append("}\n")
    append("};\n\n")
    append(getDefaultTransformImageUrlFunction())
  }

  /**
   * Writes the compressed data block directly to a [Writer], entry-by-entry, to avoid
   * accumulating a potentially 2 GB+ String in memory on large CI runs.
   *
   * This is the streaming equivalent of [createCompressedDataBlock] and must be kept in sync
   * with it. Use this overload inside [generateFromTemplate] where the output is streamed to disk.
   */
  private fun writeCompressedDataBlock(
    writer: Writer,
    compressedSessionJson: String,
    compressedSessionInfoJson: String,
    compressedPerSessionLogs: Map<String, String> = emptyMap(),
    compressedImages: Map<String, String> = emptyMap(),
    compressedCaptureMetadata: Map<String, String> = emptyMap(),
    imageAliases: Map<String, String> = emptyMap(),
  ) {
    writer.write("window.trailblaze_report_compressed = {\n")
    writer.write("  sessions: \"$compressedSessionJson\",\n")
    writer.write("  session_info: \"$compressedSessionInfoJson\",\n")
    writer.write("  session_detail: null,\n")
    writer.write("  per_session_logs: {")
    var first = true
    compressedPerSessionLogs.forEach { (id, data) ->
      if (!first) writer.write(",")
      first = false
      val escapedId = id.replace("\\", "\\\\").replace("\"", "\\\"")
      writer.write("\"$escapedId\":\"$data\"")
    }
    writer.write("},\n")
    writer.write("  capture_metadata: {")
    first = true
    compressedCaptureMetadata.forEach { (id, data) ->
      if (!first) writer.write(",")
      first = false
      val escapedId = id.replace("\\", "\\\\").replace("\"", "\\\"")
      writer.write("\"$escapedId\":\"$data\"")
    }
    writer.write("},\n")
    writer.write("  images: {")
    first = true
    compressedImages.forEach { (key, data) ->
      if (!first) writer.write(",")
      first = false
      val escapedKey = key.replace("\\", "\\\\").replace("\"", "\\\"")
      writer.write("\"$escapedKey\":\"$data\"")
    }
    writer.write("},\n")
    writer.write("  image_aliases: {")
    first = true
    imageAliases.forEach { (alias, canonical) ->
      if (!first) writer.write(",")
      first = false
      val escapedAlias = alias.replace("\\", "\\\\").replace("\"", "\\\"")
      val escapedCanonical = canonical.replace("\\", "\\\\").replace("\"", "\\\"")
      writer.write("\"$escapedAlias\":\"$escapedCanonical\"")
    }
    writer.write("}\n")
    writer.write("};\n\n")
    writer.write(getDefaultTransformImageUrlFunction())
  }

  private fun getDefaultTransformImageUrlFunction(): String = """
    // Snapshot whether images were originally embedded at script-load time.
    // The images map gets entries deleted after decompression (to free memory), so
    // checking its length at call time is unreliable.
    window._trailblaze_images_embedded = (function() {
      var c = window.trailblaze_report_compressed;
      return c && c.images && Object.keys(c.images).length > 0;
    })();
    // Default image URL transformation
    // When served with no embedded images (on-demand /report endpoint), converts image
    // keys to /static/ URLs so the browser can fetch them from the server. This works
    // for both localhost and remote hosts (e.g., via Blox proxy).
    // For embedded reports, images are in the compressed data and decompressed directly.
    // This can be overridden by scripts loaded later in the HTML (e.g., Buildkite transformer).
    window.transformImageUrl = window.transformImageUrl || function(screenshotRef) {
      if (screenshotRef.startsWith('http://') || screenshotRef.startsWith('https://') || screenshotRef.startsWith('data:')) {
        return screenshotRef;
      }
      // Only redirect to /static/ when images were NOT originally embedded
      // AND the report is served over HTTP (not opened as a local file://).
      if (!window._trailblaze_images_embedded &&
          (window.location.protocol === 'http:' || window.location.protocol === 'https:')) {
        return window.location.origin + '/static/' + screenshotRef;
      }
      return screenshotRef;
    };
  """.trimIndent()

  private fun createWebpackPatchScript(): String = """
    <script type="application/javascript">
    // Webpack publicPath patch - must be set before webpack module loads
    (function() {
      if (typeof window !== 'undefined') {
        window.__webpack_public_path__ = '';
        
        const originalError = window.Error;
        window.Error = function(message) {
          if (typeof message === 'string' && message.includes('Automatic publicPath is not supported')) {
            console.warn('Webpack publicPath error caught and handled for embedded context');
            const err = new originalError('Webpack publicPath handled for embedding');
            err.webpackEmbedded = true;
            return err;
          }
          return new originalError(message);
        };
        window.Error.prototype = originalError.prototype;
      }
    })();
    </script>
  """.trimIndent()

  private fun createJsLoaderScript(compressedJsBase64: String, wasmData: Map<String, String>): String {
    val wasmPatches = wasmData.keys.joinToString("\n        ") { filename ->
      "patchedJs = patchedJs.replace('module.exports = __webpack_require__.p + \"$filename\";', 'module.exports = \"$filename\";');"
    }

    return """
    <script type="application/javascript">
    window.trailblaze_compressed_js = "$compressedJsBase64";
    
    (async function() {
      try {
        console.log('📦 Decompressing JavaScript bundle...');
        const startTime = performance.now();
        
        const jsCode = await decompressString(window.trailblaze_compressed_js);
        
        const endTime = performance.now();
        console.log('✅ JavaScript decompressed in ' + (endTime - startTime).toFixed(0) + 'ms');
        
        let patchedJs = jsCode;
        $wasmPatches
        
        patchedJs = patchedJs.replace(
          /if\s*\(!scriptUrl\)\s*throw\s+new\s+Error\([^)]+\);/g,
          'if (!scriptUrl) { console.warn("Using empty publicPath for embedded context"); scriptUrl = ""; }'
        );
        
        (0, eval)(patchedJs);
        
        console.log('✅ JavaScript bundle loaded and executed');
      } catch (error) {
        console.error('❌ Failed to load JavaScript bundle:', error);
        document.body.innerHTML = '<div style="padding: 20px; color: red;">Failed to load application: ' + error.message + '</div>';
      }
    })();
    </script>
    """.trimIndent()
  }

  private fun compressBytes(bytes: ByteArray): ByteArray = ByteArrayOutputStream().use { outputStream ->
    GZIPOutputStream(outputStream).use { gzipStream ->
      gzipStream.write(bytes)
      gzipStream.flush()
      gzipStream.finish()
    }
    outputStream.toByteArray()
  }

  private fun compressStringToBase64(text: String): String = Base64.encode(compressBytes(text.toByteArray(Charsets.UTF_8)))

  private fun compressImages(
    sessionToImageFiles: Map<String, List<File>>,
    videoFrameImages: Map<String, ByteArray> = emptyMap(),
  ): Map<String, String> {
    val allImagePairs = sessionToImageFiles.flatMap { (sessionId, imageFiles) ->
      imageFiles.map { sessionId to it }
    }
    val totalItems = allImagePairs.size + videoFrameImages.size
    if (totalItems == 0) return emptyMap()
    val imageCompressionParallelism = resolveImageCompressionParallelism()
    Console.log("  Using image compression parallelism: $imageCompressionParallelism")
    val compressedImages = LinkedHashMap<String, String>(totalItems)
    val imageCompressorDispatcher = Dispatchers.IO.limitedParallelism(imageCompressionParallelism)
    runBlocking {
      // Compress regular screenshot files
      allImagePairs
        .chunked(imageCompressionParallelism)
        .forEach { batch ->
          batch.map { (sessionId, imageFile) ->
            async(imageCompressorDispatcher) {
              val imageKey = "$sessionId/${imageFile.name}"
              val imageBytes = imageFile.readBytes()
              val compressed = compressBytes(imageBytes)
              val base64Compressed = Base64.encode(compressed)
              imageKey to base64Compressed
            }
          }.awaitAll().forEach { (imageKey, base64Compressed) ->
            compressedImages[imageKey] = base64Compressed
          }
        }

      // Compress video frame byte arrays
      if (videoFrameImages.isNotEmpty()) {
        Console.log("  Compressing ${videoFrameImages.size} video frames...")
        videoFrameImages.entries.toList()
          .chunked(imageCompressionParallelism)
          .forEach { batch ->
            batch.map { (imageKey, imageBytes) ->
              async(imageCompressorDispatcher) {
                val compressed = compressBytes(imageBytes)
                val base64Compressed = Base64.encode(compressed)
                imageKey to base64Compressed
              }
            }.awaitAll().forEach { (imageKey, base64Compressed) ->
              compressedImages[imageKey] = base64Compressed
            }
          }
      }
    }
    return compressedImages
  }
 
  private fun resolveImageCompressionParallelism(): Int {
    val envParallelism = System.getenv("TRAILBLAZE_REPORT_IMAGE_COMPRESSION_PARALLELISM")
      ?.toIntOrNull()
      ?.takeIf { it > 0 }
    if (envParallelism != null) return envParallelism
    val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    return minOf(cores, 4)
  }

  private fun createWasmLoaderScript(wasmData: Map<String, String>): String {
    val wasmMap = wasmData.entries.joinToString(",\n        ") { (filename, base64Data) ->
      "\"$filename\": \"$base64Data\""
    }

    return """
    <script type="application/javascript">
    window.wasmFiles = {
        $wasmMap
    };
    const wasmFiles = window.wasmFiles;
    const wasmCache = new Map();
    
    function decompressGzip(compressedBytes) {
        if (typeof DecompressionStream !== 'undefined') {
            const ds = new DecompressionStream('gzip');
            const writer = ds.writable.getWriter();
            writer.write(compressedBytes);
            writer.close();
            return new Response(ds.readable).arrayBuffer().then(buffer => new Uint8Array(buffer));
        } else if (typeof pako !== 'undefined') {
            return Promise.resolve(pako.inflate(compressedBytes));
        }
        throw new Error('Decompression not supported in this browser. Please use a modern browser with DecompressionStream support.');
    }
    
    window.decompressString = async function(base64CompressedString) {
        try {
            const binaryString = atob(base64CompressedString);
            const compressedBytes = new Uint8Array(binaryString.length);
            for (let i = 0; i < binaryString.length; i++) {
                compressedBytes[i] = binaryString.charCodeAt(i);
            }
            
            const decompressedBytes = await decompressGzip(compressedBytes);
            const decoder = new TextDecoder('utf-8');
            return decoder.decode(decompressedBytes);
        } catch (error) {
            console.error('Error decompressing string:', error);
            throw error;
        }
    };
    
    function loadWasmFromBase64(filename) {
        if (wasmCache.has(filename)) {
            console.log('Using cached WASM:', filename);
            return Promise.resolve(wasmCache.get(filename));
        }
        
        const base64Data = wasmFiles[filename];
        if (!base64Data) {
            return Promise.reject(new Error('WASM file not found: ' + filename));
        }
        
        try {
            const binaryString = atob(base64Data);
            const compressedBytes = new Uint8Array(binaryString.length);
            for (let i = 0; i < binaryString.length; i++) {
                compressedBytes[i] = binaryString.charCodeAt(i);
            }
            
            return decompressGzip(compressedBytes).then(wasmBytes => {
                wasmCache.set(filename, wasmBytes);
                console.log('Decompressed and cached WASM:', filename, '(' + wasmBytes.length + ' bytes)');
                return wasmBytes;
            });
        } catch (error) {
            console.error('Error loading WASM file:', filename, error);
            return Promise.reject(error);
        }
    }
    
    const OriginalXMLHttpRequest = window.XMLHttpRequest;
    window.XMLHttpRequest = function() {
        const xhr = new OriginalXMLHttpRequest();
        const originalOpen = xhr.open;
        const originalSend = xhr.send;
        let wasmUrl = null;
        
        xhr.open = function(method, url, ...args) {
            wasmUrl = url;
            return originalOpen.call(this, method, url, ...args);
        };
        
        xhr.send = function(...args) {
            if (wasmUrl && typeof wasmUrl === 'string' && wasmUrl.endsWith('.wasm')) {
                const filename = wasmUrl.split('/').pop();
                if (wasmFiles[filename]) {
                    console.log('📦 Loading embedded WASM via XHR:', filename);
                    if (window.showWasmLoading) window.showWasmLoading();
                    
                    loadWasmFromBase64(filename).then(wasmBytes => {
                        if (window.hideWasmLoading) window.hideWasmLoading();
                        
                        Object.defineProperty(xhr, 'readyState', { writable: true, value: 4 });
                        Object.defineProperty(xhr, 'status', { writable: true, value: 200 });
                        Object.defineProperty(xhr, 'response', { writable: true, value: wasmBytes.buffer });
                        Object.defineProperty(xhr, 'responseType', { writable: true, value: 'arraybuffer' });
                        
                        if (xhr.onload) xhr.onload();
                        if (xhr.onreadystatechange) xhr.onreadystatechange();
                    }).catch(error => {
                        if (window.hideWasmLoading) window.hideWasmLoading();
                        console.error('Failed to load WASM via XHR:', filename, error);
                        
                        Object.defineProperty(xhr, 'readyState', { writable: true, value: 4 });
                        Object.defineProperty(xhr, 'status', { writable: true, value: 500 });
                        
                        if (xhr.onerror) xhr.onerror(error);
                        if (xhr.onreadystatechange) xhr.onreadystatechange();
                    });
                    
                    return;
                }
            }
            return originalSend.call(this, ...args);
        };
        
        return xhr;
    };
    
    const originalFetch = window.fetch;
    window.fetch = function(resource, options) {
        if (typeof resource === 'string' && resource.endsWith('.wasm')) {
            const filename = resource.split('/').pop();
            if (wasmFiles[filename]) {
                console.log('📦 Loading embedded WASM via fetch:', filename);
                if (window.showWasmLoading) window.showWasmLoading();
                
                return loadWasmFromBase64(filename)
                    .then(wasmBytes => {
                        if (window.hideWasmLoading) window.hideWasmLoading();
                        return new Response(wasmBytes, {
                            headers: { 
                                'Content-Type': 'application/wasm',
                                'Content-Length': wasmBytes.length.toString()
                            }
                        });
                    })
                    .catch(error => {
                        if (window.hideWasmLoading) window.hideWasmLoading();
                        throw error;
                    });
            }
        }
        return originalFetch.call(this, resource, options);
    };
    
    const originalWebAssemblyInstantiateStreaming = WebAssembly.instantiateStreaming;
    if (originalWebAssemblyInstantiateStreaming) {
        WebAssembly.instantiateStreaming = function(source, importObject) {
            if (source && typeof source.then === 'function') {
                return source.then(response => {
                    const url = response.url || '';
                    const filename = url.split('/').pop();
                    if (filename && filename.endsWith('.wasm') && wasmFiles[filename]) {
                        console.log('Instantiating embedded WASM:', filename);
                        return loadWasmFromBase64(filename).then(wasmBytes => {
                            return WebAssembly.instantiate(wasmBytes, importObject);
                        });
                    }
                    return originalWebAssemblyInstantiateStreaming.call(this, response, importObject);
                });
            }
            return originalWebAssemblyInstantiateStreaming.call(this, source, importObject);
        };
    }
    
    console.log('🚀 WASM loader initialized');
    console.log('   Files:', Object.keys(wasmFiles));
    console.log('   Compression: enabled (gzip)');
    
    function initLoadingIndicator() {
        if (typeof document === 'undefined' || !document.head || !document.body) {
            return;
        }
        
        const style = document.createElement('style');
        style.textContent = `
            .wasm-loading-indicator {
                position: fixed;
                top: 50%;
                left: 50%;
                transform: translate(-50%, -50%);
                background: rgba(0, 0, 0, 0.8);
                color: white;
                padding: 20px 40px;
                border-radius: 8px;
                font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
                font-size: 14px;
                z-index: 10000;
                display: none;
            }
            .wasm-loading-indicator.active {
                display: block;
            }
        `;
        document.head.appendChild(style);
        
        const loadingDiv = document.createElement('div');
        loadingDiv.className = 'wasm-loading-indicator';
        loadingDiv.textContent = 'Loading WASM...';
        document.body.appendChild(loadingDiv);
        
        window.showWasmLoading = () => loadingDiv.classList.add('active');
        window.hideWasmLoading = () => loadingDiv.classList.remove('active');
    }
    
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initLoadingIndicator);
    } else {
        initLoadingIndicator();
    }
    </script>
    """.trimIndent()
  }

  /**
   * Per-session info about the trimmed video window (test execution only, not full capture).
   */
  private data class TrimmedVideoInfo(
    val trimmedStartMs: Long,
    val trimmedEndMs: Long,
  )

  /**
   * Extracts video frames at [WASM_VIDEO_FPS] for each session that has a video capture.
   * Frames are trimmed to the test execution window (first log event to last log event)
   * to avoid wasting space on pre-test and post-test dead time.
   *
   * Returns a pair of:
   * - Map of image keys to JPEG byte arrays
   * - Map of session ID to [TrimmedVideoInfo] for adjusting capture metadata
   */
  /**
   * Image aliases for deduplication: maps an image key to a canonical key that holds the actual
   * data. When multiple video frames are identical (e.g. static UI), only the canonical frame is
   * stored and the rest are aliases.
   */
  private val imageAliases = LinkedHashMap<String, String>()

  private fun extractVideoFrames(
    logsRepo: LogsRepo,
    sessionIds: List<SessionId>,
  ): Pair<Map<String, ByteArray>, Map<String, TrimmedVideoInfo>> {
    val frames = LinkedHashMap<String, ByteArray>()
    val trimInfo = LinkedHashMap<String, TrimmedVideoInfo>()
    for (sessionId in sessionIds) {
      val sessionDir = File(logsRepo.logsDir, sessionId.value)
      val metadataFile = File(sessionDir, "capture_metadata.json")
      if (!metadataFile.exists()) continue

      try {
        val metadata = compactJsonReformatter.decodeFromString<JsonElement>(metadataFile.readText())
        val artifacts = metadata.jsonObject["artifacts"]?.jsonArray ?: continue

        // Prefer sprite sheet (VIDEO_FRAMES) over raw video (VIDEO)
        val spritesArtifact = artifacts.firstOrNull { entry ->
          entry.jsonObject["type"]?.jsonPrimitive?.content == "VIDEO_FRAMES"
        }
        if (spritesArtifact != null) {
          extractFromSpriteSheet(sessionId, sessionDir, spritesArtifact, logsRepo, frames, trimInfo)
          continue
        }

        val videoArtifact = artifacts.firstOrNull { entry ->
          entry.jsonObject["type"]?.jsonPrimitive?.content == "VIDEO"
        } ?: continue
        extractFromVideo(sessionId, sessionDir, videoArtifact, logsRepo, frames, trimInfo)
      } catch (e: Exception) {
        Console.log("  WARNING: Failed to extract video frames for ${sessionId.value}: ${e.message}")
      }
    }
    Console.log("  Total video frames: ${frames.size} unique + ${imageAliases.size} aliased (${frames.size + imageAliases.size} logical)")
    return frames to trimInfo
  }

  /** Crops individual frames from a pre-built sprite sheet — no ffmpeg needed. */
  private fun extractFromSpriteSheet(
    sessionId: SessionId,
    sessionDir: File,
    artifact: JsonElement,
    logsRepo: LogsRepo,
    frames: MutableMap<String, ByteArray>,
    trimInfo: MutableMap<String, TrimmedVideoInfo>,
  ) {
    val filename = artifact.jsonObject["filename"]?.jsonPrimitive?.content ?: return
    val startMs = artifact.jsonObject["startTimestampMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: return
    val endMs = artifact.jsonObject["endTimestampMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: return
    val spriteFile = File(sessionDir, filename)
    if (!spriteFile.exists()) return

    // Parse sprite metadata
    val metaFile = File(sessionDir, "video_sprites.txt")
    if (!metaFile.exists()) return
    val props = metaFile.readLines().associate {
      val (k, v) = it.split("=", limit = 2)
      k.trim() to v.trim()
    }
    val fps = props["fps"]?.toIntOrNull() ?: return
    val frameCount = props["frames"]?.toIntOrNull() ?: return
    val frameHeight = props["height"]?.toIntOrNull() ?: return
    val columns = props["columns"]?.toIntOrNull() ?: 1
    val frameMap = props["frameMap"]?.split(",")?.map { it.toInt() }
    val uniqueFrameCount = props["uniqueFrames"]?.toIntOrNull()
    val rows = props["rows"]?.toIntOrNull() ?: (uniqueFrameCount ?: frameCount)

    Console.log("  Loading sprite sheet for ${sessionId.value}/$filename ($frameCount frames at ${fps}fps)")

    // Trim to test execution window
    val logs = logsRepo.getLogsForSession(sessionId)
    val sortedLogs = logs.sortedBy { it.timestamp }
    val firstLogMs = sortedLogs.firstOrNull()?.timestamp?.toEpochMilliseconds()
    val lastLogMs = sortedLogs.lastOrNull()?.timestamp?.toEpochMilliseconds()
    val trimStartMs = if (firstLogMs != null) maxOf(startMs, firstLogMs) else startMs
    val trimEndMs = if (lastLogMs != null) minOf(endMs, lastLogMs) else endMs

    // Calculate which frames fall in the trimmed window
    val totalDurationMs = endMs - startMs
    val firstFrameIndex = if (totalDurationMs > 0) {
      ((trimStartMs - startMs) * fps / 1000).toInt().coerceIn(0, frameCount - 1)
    } else 0
    val lastFrameIndex = if (totalDurationMs > 0) {
      ((trimEndMs - startMs) * fps / 1000).toInt().coerceIn(0, frameCount - 1)
    } else frameCount - 1

    // Load the sprite sheet image
    val spriteImage = javax.imageio.ImageIO.read(spriteFile) ?: return
    val spriteWidth = spriteImage.width

    // Crop each frame and write as JPEG bytes, deduplicating via frameMap.
    // When frameMap is present, multiple logical frames may map to the same physical sprite
    // position — we only crop a physical frame once and alias the rest.
    val frameWidth = spriteWidth / columns
    var outputIndex = 1
    val physicalToCropKey = mutableMapOf<Int, String>() // physical index -> first image key
    var aliasCount = 0
    for (i in firstFrameIndex..lastFrameIndex) {
      val physicalIndex = frameMap?.getOrNull(i) ?: i
      val imageKey = "${sessionId.value}/vf_%06d.jpg".format(outputIndex)

      val existingKey = physicalToCropKey[physicalIndex]
      if (existingKey != null) {
        // This logical frame is identical to a previously cropped frame — alias it.
        imageAliases[imageKey] = existingKey
        aliasCount++
      } else {
        // Crop this physical frame from the sprite grid.
        val col = physicalIndex / rows
        val row = physicalIndex % rows
        val x = col * frameWidth
        val y = row * frameHeight
        val w = frameWidth.coerceAtMost(spriteImage.width - x)
        val h = frameHeight.coerceAtMost(spriteImage.height - y)
        if (w <= 0 || h <= 0) break
        val frameImage = spriteImage.getSubimage(x, y, w, h)
        val baos = ByteArrayOutputStream()
        javax.imageio.ImageIO.write(frameImage, "jpg", baos)
        frames[imageKey] = baos.toByteArray()
        physicalToCropKey[physicalIndex] = imageKey
      }
      outputIndex++
    }
    trimInfo[sessionId.value] = TrimmedVideoInfo(trimStartMs, trimEndMs)
    val totalCropped = outputIndex - 1
    Console.log(
      "    Cropped $totalCropped frames from sprite sheet (frames $firstFrameIndex..$lastFrameIndex)" +
        if (aliasCount > 0) ", $aliasCount aliased as duplicates" else ""
    )
  }

  /** Extracts frames from a raw video file using ffmpeg (legacy path). */
  private fun extractFromVideo(
    sessionId: SessionId,
    sessionDir: File,
    artifact: JsonElement,
    logsRepo: LogsRepo,
    frames: MutableMap<String, ByteArray>,
    trimInfo: MutableMap<String, TrimmedVideoInfo>,
  ) {
    val videoFilename = artifact.jsonObject["filename"]?.jsonPrimitive?.content ?: return
    val videoStartMs = artifact.jsonObject["startTimestampMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: return
    val videoEndMs = artifact.jsonObject["endTimestampMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: return
    val videoFile = File(sessionDir, videoFilename)
    if (!videoFile.exists()) return

    val logs = logsRepo.getLogsForSession(sessionId)
    val sortedLogs = logs.sortedBy { it.timestamp }
    val firstLogMs = sortedLogs.firstOrNull()?.timestamp?.toEpochMilliseconds()
    val lastLogMs = sortedLogs.lastOrNull()?.timestamp?.toEpochMilliseconds()
    val trimStartMs = if (firstLogMs != null) maxOf(videoStartMs, firstLogMs) else videoStartMs
    val trimEndMs = if (lastLogMs != null) minOf(videoEndMs, lastLogMs) else videoEndMs
    val seekOffsetSec = (trimStartMs - videoStartMs) / 1000.0
    val durationSec = (trimEndMs - trimStartMs) / 1000.0

    Console.log("  Extracting frames from ${sessionId.value}/$videoFilename at ${WASM_VIDEO_FPS}fps...")
    Console.log("    Video: ${(videoEndMs - videoStartMs) / 1000.0}s, trimmed to test window: ${durationSec}s (offset: ${seekOffsetSec}s)")

    val tempDir = java.nio.file.Files.createTempDirectory("trailblaze_wasm_frames_").toFile()
    try {
      val command = mutableListOf(
        "ffmpeg",
        "-i", videoFile.absolutePath,
        "-ss", "%.3f".format(seekOffsetSec),
        "-t", "%.3f".format(durationSec),
        "-vf", "fps=$WASM_VIDEO_FPS,scale=-2:360",
        "-q:v", "5",
        "-loglevel", "error",
        "${tempDir.absolutePath}/vf_%06d.jpg",
      )
      val process = ProcessBuilder(command).redirectErrorStream(true).start()
      process.inputStream.bufferedReader().readText()
      process.waitFor(120, TimeUnit.SECONDS)

      val frameFiles = tempDir.listFiles { _, name -> name.startsWith("vf_") && name.endsWith(".jpg") }
        ?.sortedBy { it.name } ?: emptyList()

      for (frameFile in frameFiles) {
        val imageKey = "${sessionId.value}/${frameFile.name}"
        frames[imageKey] = frameFile.readBytes()
      }
      trimInfo[sessionId.value] = TrimmedVideoInfo(trimStartMs, trimEndMs)
      Console.log("    Extracted ${frameFiles.size} frames")
    } finally {
      tempDir.deleteRecursively()
    }
  }

  /**
   * Loads capture_metadata.json for each session that has one.
   * When [trimInfo] is provided, the VIDEO artifact timestamps are adjusted to the
   * test execution window so the UI timeline only covers the active test period.
   * Returns a map of session ID to the JSON string content.
   */
  private fun loadCaptureMetadata(
    logsRepo: LogsRepo,
    sessionIds: List<SessionId>,
    trimInfo: Map<String, TrimmedVideoInfo> = emptyMap(),
  ): Map<String, String> {
    val result = LinkedHashMap<String, String>()
    for (sessionId in sessionIds) {
      val sessionDir = File(logsRepo.logsDir, sessionId.value)
      val metadataFile = File(sessionDir, "capture_metadata.json")
      if (!metadataFile.exists()) continue
      try {
        val metadata = compactJsonReformatter.decodeFromString<JsonElement>(metadataFile.readText())
        val trim = trimInfo[sessionId.value]
        val adjusted = if (trim != null) {
          // Rewrite VIDEO artifact timestamps to the trimmed test window
          val artifacts = metadata.jsonObject["artifacts"]?.jsonArray ?: metadata.jsonObject["artifacts"]
          val newArtifacts = artifacts?.jsonArray?.map { artifact ->
            val obj = artifact.jsonObject
            if (obj["type"]?.jsonPrimitive?.content == "VIDEO" || obj["type"]?.jsonPrimitive?.content == "VIDEO_FRAMES") {
              kotlinx.serialization.json.buildJsonObject {
                obj.forEach { (key, value) ->
                  when (key) {
                    "startTimestampMs" -> put(key, kotlinx.serialization.json.JsonPrimitive(trim.trimmedStartMs))
                    "endTimestampMs" -> put(key, kotlinx.serialization.json.JsonPrimitive(trim.trimmedEndMs))
                    else -> put(key, value)
                  }
                }
              }
            } else {
              artifact
            }
          }
          if (newArtifacts != null) {
            kotlinx.serialization.json.buildJsonObject {
              metadata.jsonObject.forEach { (key, value) ->
                if (key == "artifacts") {
                  put(key, kotlinx.serialization.json.JsonArray(newArtifacts))
                } else {
                  put(key, value)
                }
              }
            }
          } else metadata
        } else metadata
        val json = compactJsonReformatter.encodeToString(JsonElement.serializer(), adjusted)
        result[sessionId.value] = json
        Console.log("  Loaded capture metadata for ${sessionId.value}${if (trim != null) " (trimmed to test window)" else ""}")
      } catch (e: Exception) {
        Console.log("  WARNING: Failed to load capture metadata for ${sessionId.value}: ${e.message}")
      }
    }
    return result
  }

  private fun replaceScreenshotPathsWithImageKeys(
    logs: List<TrailblazeLog>,
    sessionId: String,
  ): List<TrailblazeLog> = logs.map { log ->
    if (log is HasScreenshot) {
      val screenshotFile = log.screenshotFile
      if (screenshotFile != null && !screenshotFile.startsWith("http://") && !screenshotFile.startsWith("https://")) {
        val filename = screenshotFile.substringAfterLast('/')
        val imageKey = "$sessionId/$filename"

        when (log) {
          is TrailblazeLog.AgentDriverLog -> log.copy(screenshotFile = imageKey)
          is TrailblazeLog.TrailblazeLlmRequestLog -> log.copy(screenshotFile = imageKey)
          is TrailblazeLog.TrailblazeSnapshotLog -> log.copy(screenshotFile = imageKey)
          else -> log
        }
      } else {
        log
      }
    } else {
      log
    }
  }
}
