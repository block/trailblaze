@file:OptIn(ExperimentalEncodingApi::class)

package xyz.block.trailblaze.report

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.HasScreenshot
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.report.utils.TrailblazeYamlSessionRecording.generateRecordedYaml
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Data for a single session that can be lazy-loaded
 */
data class PerSessionData(
  val logs: List<TrailblazeLog>,
  val yaml: String,
  val imageKeys: List<String>,
)

/**
 * Generates the Trailblaze report in a WASM-compatible HTML format.
 */
object WasmReport {

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
    println("Encoding JSON data...")
    val allSessionIds = logsRepo.getSessionIds()
    val sessionToImageFiles = allSessionIds.associateWith { logsRepo.getImagesForSession(it) }

    println("Generating lightweight session info metadata...")
    val skippedSessions = mutableListOf<SessionId>()
    val sessionInfoMap = allSessionIds.mapNotNull { sessionId ->
      val sessionInfo = logsRepo.getSessionInfo(sessionId)
      if (sessionInfo == null) {
        println("  ⚠️  WARNING: Skipping session '$sessionId' - no session info available")
        skippedSessions.add(sessionId)
        null
      } else {
        sessionId to sessionInfo
      }
    }.toMap()

    if (skippedSessions.isNotEmpty()) {
      println("  ⚠️  Skipped ${skippedSessions.size} session(s) due to missing session info:")
      skippedSessions.forEach { println("     - $it") }
    }

    // Only include valid sessions in the report
    val validSessionIds = allSessionIds.filter { it !in skippedSessions }
    val sessionJson = compactJson(TrailblazeJsonInstance.encodeToString(validSessionIds.map { it.value }))
    val sessionInfoJson = compactJson(TrailblazeJsonInstance.encodeToString(sessionInfoMap.mapKeys { it.key.value }))

    // Only build data for sessions that have valid session info
    val perSessionData = buildPerSessionData(logsRepo, validSessionIds, sessionToImageFiles)

    // Convert SessionId keys to String for template generation
    val sessionToImageFilesStr = sessionToImageFiles.mapKeys { it.key.value }

    if (reportTemplateFile.exists()) {
      println("Generating report from template: ${reportTemplateFile.absolutePath}")
      generateFromTemplate(
        reportTemplateFile = reportTemplateFile,
        reportOutputFile = outputFile,
        sessionJson = sessionJson,
        sessionInfoJson = sessionInfoJson,
        sessionToImageFiles = sessionToImageFilesStr,
        perSessionData = perSessionData,
      )
    } else {
      println("Generating report from raw WASM UI build artifacts...")
      generateRaw(
        trailblazeUiProjectDir = trailblazeUiProjectDir,
        reportOutputFile = outputFile,
        sessionJson = sessionJson,
        sessionInfoJson = sessionInfoJson,
        sessionToImageFiles = sessionToImageFilesStr,
        perSessionData = perSessionData,
      )
    }

    println("\n✅ Success!")
    println("Embedded HTML created at: ${outputFile.absolutePath}")
    println("Final file size: ${outputFile.length() / 1024}KB")
  }

  private fun buildPerSessionData(
    logsRepo: LogsRepo,
    sessionIds: List<SessionId>,
    sessionToImageFiles: Map<SessionId, List<File>>,
  ): Map<String, PerSessionData> = sessionIds.associate { sessionId ->
    val logs = logsRepo.getLogsForSession(sessionId)
    val imageFiles = sessionToImageFiles[sessionId] ?: emptyList()
    val logsWithKeys = replaceScreenshotPathsWithImageKeys(logs, sessionId.value)

    val yamlRecording = try {
      logs.generateRecordedYaml()
    } catch (e: Exception) {
      buildString {
        appendLine("Exception while generating recorded YAML:")
        appendLine(e.message)
        appendLine(e.stackTraceToString())
      }
    }

    sessionId.value to PerSessionData(
      logs = logsWithKeys,
      yaml = yamlRecording,
      imageKeys = imageFiles.map { "${sessionId.value}/${it.name}" },
    )
  }

  private fun compressPerSessionData(
    perSessionData: Map<String, PerSessionData>,
  ): Pair<Map<String, String>, Map<String, String>> {
    val startTime = System.currentTimeMillis()

    val compressedData = runBlocking(Dispatchers.Default) {
      perSessionData.entries.map { (sessionId, data) ->
        async {
          val logsJson = compactJson(TrailblazeJsonInstance.encodeToString(data.logs))
          val yamlJson = compactJson(TrailblazeJsonInstance.encodeToString(data.yaml))

          sessionId to Pair(
            compressStringToBase64(logsJson),
            compressStringToBase64(yamlJson),
          )
        }
      }.awaitAll().toMap()
    }

    val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
    println("  ✅ Compressed ${perSessionData.size} sessions in ${elapsed.toInt()}s")

    return Pair(
      compressedData.mapValues { it.value.first },
      compressedData.mapValues { it.value.second },
    )
  }

  private fun generateFromTemplate(
    reportTemplateFile: File,
    reportOutputFile: File,
    sessionJson: String,
    sessionInfoJson: String,
    sessionToImageFiles: Map<String, List<File>>,
    perSessionData: Map<String, PerSessionData>,
  ) {
    println("Generating report from template: ${reportTemplateFile.absolutePath}")

    val compressedSessionJson = compressStringToBase64(sessionJson)
    val compressedSessionInfoJson = compressStringToBase64(sessionInfoJson)

    val (compressedPerSessionLogs, compressedPerSessionYaml) = compressPerSessionData(perSessionData)

    println("\nCompressing and encoding images...")
    val compressedImages = compressImages(sessionToImageFiles)

    val templateContent = reportTemplateFile.readText()

    println("\nExtracting and compressing JavaScript bundle from template...")
    val jsExtractRegex = """<script type="application/javascript">\s*([\s\S]*?)\s*</script>""".toRegex()
    val mainJsScript = jsExtractRegex.findAll(templateContent)
      .maxByOrNull { it.groupValues[1].length }

    if (mainJsScript == null) {
      println("⚠️  WARNING: Could not find JavaScript bundle in template!")
    }

    val jsContent = mainJsScript?.groupValues?.get(1) ?: ""
    val compressedJsBase64 = if (jsContent.isNotEmpty()) {
      compressStringToBase64(jsContent)
    } else {
      ""
    }

    val wasmFileRegex = """["']([^"']+\.wasm)["']""".toRegex()
    val wasmFiles = wasmFileRegex.findAll(jsContent)
      .map { it.groupValues[1].substringAfterLast('/') }
      .toSet()
      .associateWith { "" }

    var output = if (mainJsScript != null) {
      templateContent.replace(mainJsScript.value, createJsLoaderScript(compressedJsBase64, wasmFiles))
    } else {
      templateContent
    }

    println("\nGenerating chunked script tags for lazy loading...")
    val chunkScripts = generateChunkedScriptTags(
      compressedPerSessionLogs,
      compressedPerSessionYaml,
      compressedImages,
    )

    val compressedDataRegex = """window\.trailblaze_report_compressed\s*=\s*\{[\s\S]*?\};""".toRegex()
    val replacementBlock = createCompressedDataBlock(compressedSessionJson, compressedSessionInfoJson)

    val matches = compressedDataRegex.findAll(output).count()
    if (matches == 0) {
      println("⚠️  WARNING: Could not find window.trailblaze_report_compressed in template!")
    }

    output = output.replace(compressedDataRegex, replacementBlock)
    output = output.replace("</body>", "$chunkScripts</body>")

    reportOutputFile.writeText(output)
  }

  private fun generateRaw(
    trailblazeUiProjectDir: File,
    reportOutputFile: File,
    sessionJson: String,
    sessionInfoJson: String,
    sessionToImageFiles: Map<String, List<File>>,
    perSessionData: Map<String, PerSessionData>,
  ) {
    println("Generating report from wasm ui build artifacts: ${trailblazeUiProjectDir.absolutePath}")

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

    println("Processing files:")
    println("  JS: ${jsFile.name} (${jsFile.length() / 1024}KB)")
    wasmFiles.forEach { println("  WASM: ${it.name} (${it.length() / 1024}KB)") }

    println("\nCompressing and encoding lightweight metadata...")
    val compressedSessionJson = compressStringToBase64(sessionJson)
    val compressedSessionInfoJson = compressStringToBase64(sessionInfoJson)

    println("\nCompressing per-session data for lazy loading...")
    val (compressedPerSessionLogs, compressedPerSessionYaml) = compressPerSessionData(perSessionData)

    val compressedImages = compressImages(sessionToImageFiles)

    println("\nGenerating chunked script tags for lazy loading...")
    val chunkScripts = generateChunkedScriptTags(
      compressedPerSessionLogs,
      compressedPerSessionYaml,
      compressedImages,
    )

    val htmlTemplate = indexHtmlFile.readText().let { html ->
      html
        .replace(
          "window.trailblaze_report = {};",
          buildString {
            appendLine("window.trailblaze_report = {};")
            appendLine(createCompressedDataBlock(compressedSessionJson, compressedSessionInfoJson))
          },
        )
        .replace(
          "window.trailblaze_report_compressed = {};",
          "// window.trailblaze_report_compressed already initialized above",
        )
        .replace("</body>", "$chunkScripts</body>")
    }

    println("\nCompressing and encoding WASM files...")
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

    println("\nCompressing JavaScript bundle...")
    val jsContent = jsFile.readText()
    val compressedJsBase64 = compressStringToBase64(jsContent)

    println("Creating embedded HTML...")
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
      println("⚠️  WARNING: window.trailblaze_report_compressed not found in template after script replacement!")
    }

    return replaced
  }

  private fun createCompressedDataBlock(
    compressedSessionJson: String,
    compressedSessionInfoJson: String,
  ): String = buildString {
    append("window.trailblaze_report_compressed = {\n")
    append("  sessions: \"$compressedSessionJson\",\n")
    append("  session_info: \"$compressedSessionInfoJson\",\n")
    append("  session_detail: null,\n")
    append("  session_yaml: null,\n")
    append("  per_session_logs: {},\n")
    append("  per_session_yaml: {},\n")
    append("  images: {}\n")
    append("};\n\n")
    append(getDefaultTransformImageUrlFunction())
  }

  private fun getDefaultTransformImageUrlFunction(): String = """
    // Default image URL transformation (returns unchanged)
    // This can be overridden by scripts loaded later in the HTML
    window.transformImageUrl = window.transformImageUrl || function(screenshotRef) {
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

  private fun compressImages(sessionToImageFiles: Map<String, List<File>>): Map<String, String> {
    val allImagePairs = sessionToImageFiles.flatMap { (sessionId, imageFiles) ->
      imageFiles.map { sessionId to it }
    }

    return runBlocking(Dispatchers.Default) {
      allImagePairs.map { (sessionId, imageFile) ->
        async {
          val imageKey = "$sessionId/${imageFile.name}"
          val imageBytes = imageFile.readBytes()
          val compressed = compressBytes(imageBytes)
          val base64Compressed = Base64.encode(compressed)
          imageKey to base64Compressed
        }
      }.awaitAll().toMap()
    }
  }

  private fun generateChunkedScriptTags(
    compressedPerSessionLogs: Map<String, String>,
    compressedPerSessionYaml: Map<String, String>,
    compressedImages: Map<String, String>,
  ): String = buildString {
    appendLine("<script>")
    appendLine("window.loadChunkById = function(chunkId) {")
    appendLine("  const scriptElement = document.getElementById(chunkId);")
    appendLine("  if (!scriptElement) {")
    appendLine("    console.error('Chunk not found:', chunkId);")
    appendLine("    return null;")
    appendLine("  }")
    appendLine("  const data = scriptElement.textContent;")
    appendLine("  scriptElement.remove();")
    appendLine("  return data;")
    appendLine("};")
    appendLine("</script>")
    appendLine()

    compressedPerSessionLogs.forEach { (sessionId, compressed) ->
      val chunkId = "chunk-logs-${sessionId.replace("/", "-")}"
      appendLine("<script type=\"application/octet-stream\" id=\"$chunkId\">$compressed</script>")
    }

    compressedPerSessionYaml.forEach { (sessionId, compressed) ->
      val chunkId = "chunk-yaml-${sessionId.replace("/", "-")}"
      appendLine("<script type=\"application/octet-stream\" id=\"$chunkId\">$compressed</script>")
    }

    val imagesBySession = compressedImages.entries.groupBy { it.key.substringBefore("/") }
    imagesBySession.forEach { (_, images) ->
      images.forEach { (imageKey, compressed) ->
        val chunkId = "chunk-image-${imageKey.replace("/", "-").replace(".", "-").replace("_", "-")}"
        appendLine("<script type=\"application/octet-stream\" id=\"$chunkId\">$compressed</script>")
      }
    }

    appendLine("<script>")
    appendLine("console.log('📦 All chunks loaded:', document.querySelectorAll('[id^=\"chunk-\"]').length, 'chunks');")
    appendLine("window.chunksReady = true;")
    appendLine("if (window.onChunksReady) window.onChunksReady();")
    appendLine("</script>")
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
          is TrailblazeLog.MaestroDriverLog -> log.copy(screenshotFile = imageKey)
          is TrailblazeLog.TrailblazeLlmRequestLog -> log.copy(screenshotFile = imageKey)
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
