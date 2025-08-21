@file:OptIn(ExperimentalEncodingApi::class)

package xyz.block.trailblaze.report

import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.report.utils.TrailblazeYamlSessionRecording.generateRecordedYaml
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Generates the Trailblaze report in a WASM-compatible HTML format.
 */
object WasmReport {

  fun mainTest() {
    val logsDir = File(System.getProperty("user.dir"), "logs").also {
      println("Using logs directory: ${it.canonicalPath}")
      if (!it.exists()) {
        error("Logs directory does not exist: ${it.canonicalPath}")
      }
    }
    val outputFile = File(System.getProperty("user.dir"), "trailblaze_report.html")

    val logsRepo = LogsRepo(logsDir)

    val trailblazeUiProjectDir = File(logsRepo.logsDir.parentFile, "opensource/trailblaze-ui").also {
      println("Using project directory: ${it.canonicalPath}")
    }
    generate(logsRepo, trailblazeUiProjectDir, outputFile)
  }

  fun generate(logsRepo: LogsRepo, trailblazeUiProjectDir: File, outputFile: File) {
    if (!trailblazeUiProjectDir.exists() || !trailblazeUiProjectDir.isDirectory) {
      error("Project directory does not exist or is not a directory: ${trailblazeUiProjectDir.canonicalPath}")
    }
    val buildDir = File(trailblazeUiProjectDir, "build/kotlin-webpack/wasmJs/productionExecutable")
    val resourcesDir = File(trailblazeUiProjectDir, "src/wasmJsMain/resources")
    val outputDir = File(trailblazeUiProjectDir, "build/embedded")

    // Ensure output directory exists
    outputDir.mkdirs()

    // Find the source HTML template
    val indexHtmlFile = File(resourcesDir, "index.html")
    if (!indexHtmlFile.exists()) {
      error("index.html not found at ${indexHtmlFile.absolutePath}")
    }

    // Find the JS file
    val jsFile = File(buildDir, "composeApp.js")
    if (!jsFile.exists()) {
      error("composeApp.js not found at ${jsFile.absolutePath}")
    }

    // Find WASM files
    val wasmFiles = buildDir.listFiles { _, name -> name.endsWith(".wasm") }
      ?: emptyArray()

    if (wasmFiles.isEmpty()) {
      error("No WASM files found in ${buildDir.absolutePath}")
    }

    println("Processing files:")
    println("  JS: ${jsFile.name} (${jsFile.length() / 1024}KB)")
    wasmFiles.forEach {
      println("  WASM: ${it.name} (${it.length() / 1024}KB)")
    }

    // Read the HTML template
    val htmlTemplate = indexHtmlFile.readText().let { html ->
      val sessionIds = logsRepo.getSessionIds()
      val sessionJson = TrailblazeJsonInstance.encodeToString(sessionIds)
      val sessionDetail: Map<String, List<TrailblazeLog>> = sessionIds.associateWith {
        logsRepo.getLogsForSession(it)
      }
      val sessionDetailJson = TrailblazeJsonInstance.encodeToString(sessionDetail)
      val sessionToYamlRecording: Map<String, String> = sessionIds.associateWith {
        logsRepo.getLogsForSession(it).generateRecordedYaml()
      }
      val sessionToYamlRecordingJson = TrailblazeJsonInstance.encodeToString(sessionToYamlRecording)

      val trailblazeReportWindowVarStr = "window.trailblaze_report = {};"
      html.replace(
        trailblazeReportWindowVarStr,
        buildString {
          appendLine(trailblazeReportWindowVarStr)
          appendLine("window.trailblaze_report.sessions = $sessionJson;\n")
          appendLine("window.trailblaze_report.session_detail = $sessionDetailJson;\n")
          appendLine("window.trailblaze_report.session_yaml = $sessionToYamlRecordingJson;\n")
        },
      )
    }

    // Read and encode WASM files as base64
    println("\nEncoding WASM files to base64...")
    val wasmData = wasmFiles.associate { file ->
      println("  Encoding ${file.name}...")
      file.name to Base64.encode(file.readBytes())
    }

    // Read JS content
    println("Reading JS content...")
    val jsContent = jsFile.readText()

    // Create the embedded HTML
    println("Creating embedded HTML...")
    val embeddedHtml = createEmbeddedHtml(htmlTemplate, jsContent, wasmData)

    // Write the final HTML file
    outputFile.writeText(embeddedHtml)

    println("\n✅ Success!")
    println("Embedded HTML created at: ${outputFile.absolutePath}")
    println("Final file size: ${outputFile.length() / 1024}KB")
    println("\nYou can now open the HTML file in a web browser.")
  }

  fun createEmbeddedHtml(template: String, jsContent: String, wasmData: Map<String, String>): String {
    val wasmLoaderScript = createWasmLoaderScript(wasmData)
    val webpackPatchScript = createWebpackPatchScript()
    val patchedJsContent = patchWebpackForEmbedding(jsContent, wasmData)

    // Replace the external JS script tag with inline JS
    val jsScriptRegex = """<script\s+type="application/javascript"\s+src="composeApp\.js"></script>""".toRegex()

    return template.replace(jsScriptRegex) {
      """
        $wasmLoaderScript
        $webpackPatchScript
        <script type="application/javascript">
        $patchedJsContent
        </script>
      """.trimIndent()
    }
  }

  fun createWebpackPatchScript(): String = """
    <script type="application/javascript">
    // Webpack publicPath patch - must be set before webpack module loads
    (function() {
      // Override webpack's automatic publicPath detection
      if (typeof window !== 'undefined') {
        window.__webpack_public_path__ = '';
        
        // Patch Error constructor to catch and handle publicPath errors gracefully
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

  fun patchWebpackForEmbedding(jsContent: String, wasmData: Map<String, String>): String {
    var patchedContent = jsContent

    // Replace WASM file references with base64 data URLs
    wasmData.forEach { (filename, _) ->
      patchedContent = patchedContent.replace(
        """module.exports = __webpack_require__.p + "$filename";""",
        """module.exports = "data:application/wasm;base64," + window.wasmFiles["$filename"];""",
      )
    }

    // Patch webpack publicPath errors more robustly using a function wrapper
    patchedContent = patchedContent.replace(
      Regex("if\\s*\\(!scriptUrl\\)\\s*throw\\s+new\\s+Error\\([^)]+\\);"),
      """if (!scriptUrl) { 
      console.warn("Using empty publicPath for embedded context"); 
      scriptUrl = ""; 
    }""",
    )

    return patchedContent
  }

  fun createWasmLoaderScript(wasmData: Map<String, String>): String {
    val wasmMap = wasmData.entries.joinToString(",\n        ") { (filename, base64Data) ->
      "\"$filename\": \"$base64Data\""
    }

    return """
    <script type="application/javascript">
    // Embedded WASM files as base64 - make globally accessible
    window.wasmFiles = {
        $wasmMap
    };
    const wasmFiles = window.wasmFiles;
    
    // Function to load WASM from base64
    function loadWasmFromBase64(filename) {
        const base64Data = wasmFiles[filename];
        if (!base64Data) {
            throw new Error('WASM file not found: ' + filename);
        }
        
        // Convert base64 to Uint8Array
        const binaryString = atob(base64Data);
        const bytes = new Uint8Array(binaryString.length);
        for (let i = 0; i < binaryString.length; i++) {
            bytes[i] = binaryString.charCodeAt(i);
        }
        
        return bytes;
    }
    
    // Override fetch for WASM files to load from embedded data
    const originalFetch = window.fetch;
    window.fetch = function(resource, options) {
        if (typeof resource === 'string' && resource.endsWith('.wasm')) {
            // Extract filename from path
            const filename = resource.split('/').pop();
            if (wasmFiles[filename]) {
                console.log('Loading embedded WASM:', filename);
                const wasmBytes = loadWasmFromBase64(filename);
                return Promise.resolve(new Response(wasmBytes, {
                    headers: { 
                        'Content-Type': 'application/wasm',
                        'Content-Length': wasmBytes.length.toString()
                    }
                }));
            }
        }
        return originalFetch.call(this, resource, options);
    };
    
    // Also override WebAssembly.instantiateStreaming for embedded WASM
    const originalWebAssemblyInstantiateStreaming = WebAssembly.instantiateStreaming;
    if (originalWebAssemblyInstantiateStreaming) {
        WebAssembly.instantiateStreaming = function(source, importObject) {
            // If source is a fetch response for a WASM file, handle it
            if (source && typeof source.then === 'function') {
                return source.then(response => {
                    const url = response.url || '';
                    const filename = url.split('/').pop();
                    if (filename && filename.endsWith('.wasm') && wasmFiles[filename]) {
                        console.log('Instantiating embedded WASM:', filename);
                        const wasmBytes = loadWasmFromBase64(filename);
                        return WebAssembly.instantiate(wasmBytes, importObject);
                    }
                    return originalWebAssemblyInstantiateStreaming.call(this, response, importObject);
                });
            }
            return originalWebAssemblyInstantiateStreaming.call(this, source, importObject);
        };
    }
    
    console.log('🚀 WASM loader initialized with files:', Object.keys(wasmFiles));
    </script>
    """.trimIndent()
  }
}
