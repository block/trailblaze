package xyz.block.trailblaze.report.snapshot

import java.io.File
import java.util.Base64

/**
 * Generates a standalone HTML viewer for test snapshots.
 */
class SnapshotViewerGenerator {
  
  /**
   * Generate a standalone HTML file with embedded snapshot images.
   */
  fun generateHtml(
    snapshots: Map<String, List<SnapshotMetadata>>,
    outputFile: File
  ) {
    println("📝 Generating HTML viewer...")
    
    val html = buildString {
      appendLine("<!DOCTYPE html>")
      appendLine("<html lang=\"en\">")
      appendLine("<head>")
      appendLine("  <meta charset=\"UTF-8\">")
      appendLine("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">")
      appendLine("  <title>Test Snapshots</title>")
      appendLine("  <style>")
      appendLine(getEmbeddedCss())
      appendLine("  </style>")
      appendLine("</head>")
      appendLine("<body>")
      appendLine("  <div class=\"container\">")
      appendLine("    <header>")
      appendLine("      <h1>📸 Test Snapshots</h1>")
      appendLine("      <p class=\"summary\">")
      appendLine("        ${snapshots.size} test(s) • ")
      appendLine("        ${snapshots.values.sumOf { it.size }} snapshot(s)")
      appendLine("      </p>")
      appendLine("    </header>")
      
      if (snapshots.isEmpty()) {
        appendLine("    <div class=\"no-snapshots\">")
        appendLine("      <h2>No Snapshots Available</h2>")
        appendLine("      <p>This could mean:</p>")
        appendLine("      <ul>")
        appendLine("        <li>No tests called <code>TakeSnapshotTool</code> during this run</li>")
        appendLine("        <li>Screenshot files were not saved to the logs directory</li>")
        appendLine("        <li>Logs are from an older version with a different format</li>")
        appendLine("      </ul>")
        appendLine("      <p class=\"tip\">💡 <strong>Tip:</strong> Use <code>TakeSnapshotTool</code> in your tests to capture snapshots:</p>")
        appendLine("      <pre>agent.executeToolCall(TakeSnapshotTool(screenName = \"login_screen\"))</pre>")
        appendLine("    </div>")
      } else {
        snapshots.forEach { (testName, snapshotList) ->
          appendTestSection(testName, snapshotList)
        }
      }
      
      appendLine("  </div>")
      appendLine("  <script>")
      appendLine(getEmbeddedJavaScript())
      appendLine("  </script>")
      appendLine("</body>")
      appendLine("</html>")
    }
    
    outputFile.writeText(html)
    println("✅ HTML viewer generated: ${outputFile.absolutePath}")
    println("   File size: ${outputFile.length() / 1024} KB")
  }
  
  private fun StringBuilder.appendTestSection(testName: String, snapshots: List<SnapshotMetadata>) {
    val testId = testName.replace(".", "_").replace(" ", "_")
    val shortName = testName.substringAfterLast(".")
    
    appendLine("    <div class=\"test-section\">")
    appendLine("      <div class=\"test-header\" onclick=\"toggleSection('$testId')\">")
    appendLine("        <h2>")
    appendLine("          <span class=\"toggle-icon\" id=\"icon-$testId\">▼</span>")
    appendLine("          $shortName")
    appendLine("        </h2>")
    appendLine("        <span class=\"snapshot-count\">${snapshots.size} snapshot(s)</span>")
    appendLine("      </div>")
    appendLine("      <div class=\"test-content\" id=\"content-$testId\">")
    appendLine("        <p class=\"test-full-name\">$testName</p>")
    appendLine("        <div class=\"snapshots-grid\">")
    
    snapshots.forEach { snapshot ->
      appendSnapshotCard(snapshot)
    }
    
    appendLine("        </div>")
    appendLine("      </div>")
    appendLine("    </div>")
  }
  
  private fun StringBuilder.appendSnapshotCard(snapshot: SnapshotMetadata) {
    val base64Image = encodeImageToBase64(snapshot.file)
    val imageId = "img-${snapshot.epochMillis}"
    
    appendLine("          <div class=\"snapshot-card\">")
    appendLine("            <div class=\"snapshot-header\">")
    appendLine("              <h3>${snapshot.displayName()}</h3>")
    appendLine("              <span class=\"timestamp\">${snapshot.formattedTimestamp()}</span>")
    appendLine("            </div>")
    appendLine("            <div class=\"snapshot-image-container\">")
    appendLine("              <img ")
    appendLine("                id=\"$imageId\"")
    appendLine("                src=\"data:image/${snapshot.file.extension};base64,$base64Image\" ")
    appendLine("                alt=\"${snapshot.displayName()}\"")
    appendLine("                onclick=\"openModal('$imageId')\"")
    appendLine("                loading=\"lazy\"")
    appendLine("              />")
    appendLine("            </div>")
    appendLine("          </div>")
  }
  
  private fun encodeImageToBase64(file: File): String {
    return Base64.getEncoder().encodeToString(file.readBytes())
  }
  
  private fun getEmbeddedCss(): String = """
    * {
      margin: 0;
      padding: 0;
      box-sizing: border-box;
    }
    
    body {
      font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, Cantarell, sans-serif;
      background: #f5f5f5;
      color: #333;
      line-height: 1.6;
    }
    
    .container {
      max-width: 1400px;
      margin: 0 auto;
      padding: 20px;
    }
    
    header {
      background: white;
      padding: 30px;
      border-radius: 8px;
      box-shadow: 0 2px 4px rgba(0,0,0,0.1);
      margin-bottom: 30px;
    }
    
    header h1 {
      font-size: 2em;
      margin-bottom: 10px;
      color: #2c3e50;
    }
    
    .summary {
      color: #7f8c8d;
      font-size: 1.1em;
    }
    
    .test-section {
      background: white;
      border-radius: 8px;
      box-shadow: 0 2px 4px rgba(0,0,0,0.1);
      margin-bottom: 20px;
      overflow: hidden;
    }
    
    .test-header {
      padding: 20px 30px;
      background: #3498db;
      color: white;
      cursor: pointer;
      display: flex;
      justify-content: space-between;
      align-items: center;
      transition: background 0.3s;
    }
    
    .test-header:hover {
      background: #2980b9;
    }
    
    .test-header h2 {
      font-size: 1.3em;
      display: flex;
      align-items: center;
      gap: 10px;
    }
    
    .toggle-icon {
      display: inline-block;
      transition: transform 0.3s;
      font-size: 0.8em;
    }
    
    .toggle-icon.collapsed {
      transform: rotate(-90deg);
    }
    
    .snapshot-count {
      background: rgba(255,255,255,0.2);
      padding: 5px 15px;
      border-radius: 20px;
      font-size: 0.9em;
    }
    
    .test-content {
      padding: 30px;
      transition: max-height 0.3s ease-out, padding 0.3s;
      overflow: hidden;
    }
    
    .test-content.collapsed {
      max-height: 0;
      padding: 0 30px;
    }
    
    .test-full-name {
      color: #7f8c8d;
      font-size: 0.9em;
      margin-bottom: 20px;
      font-family: 'Courier New', monospace;
    }
    
    .snapshots-grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(350px, 1fr));
      gap: 20px;
    }
    
    .snapshot-card {
      border: 1px solid #e0e0e0;
      border-radius: 8px;
      overflow: hidden;
      transition: transform 0.2s, box-shadow 0.2s;
    }
    
    .snapshot-card:hover {
      transform: translateY(-2px);
      box-shadow: 0 4px 8px rgba(0,0,0,0.15);
    }
    
    .snapshot-header {
      padding: 15px;
      background: #f8f9fa;
      border-bottom: 1px solid #e0e0e0;
    }
    
    .snapshot-header h3 {
      font-size: 1em;
      color: #2c3e50;
      margin-bottom: 5px;
    }
    
    .timestamp {
      font-size: 0.85em;
      color: #7f8c8d;
    }
    
    .snapshot-image-container {
      position: relative;
      background: #000;
      overflow: hidden;
    }
    
    .snapshot-image-container img {
      width: 100%;
      height: auto;
      display: block;
      cursor: pointer;
      transition: opacity 0.2s;
    }
    
    .snapshot-image-container img:hover {
      opacity: 0.9;
    }
    
    .no-snapshots {
      background: white;
      padding: 60px;
      text-align: left;
      border-radius: 8px;
      box-shadow: 0 2px 4px rgba(0,0,0,0.1);
      max-width: 800px;
      margin: 0 auto;
    }
    
    .no-snapshots h2 {
      color: #2c3e50;
      margin-bottom: 20px;
      font-size: 1.5em;
    }
    
    .no-snapshots p {
      font-size: 1.1em;
      color: #555;
      margin-bottom: 15px;
    }
    
    .no-snapshots ul {
      margin: 20px 0 20px 30px;
      color: #555;
    }
    
    .no-snapshots li {
      margin: 10px 0;
      font-size: 1em;
    }
    
    .no-snapshots code {
      background: #f4f4f4;
      padding: 2px 6px;
      border-radius: 3px;
      font-family: 'Courier New', monospace;
      font-size: 0.9em;
    }
    
    .no-snapshots pre {
      background: #f4f4f4;
      padding: 15px;
      border-radius: 5px;
      overflow-x: auto;
      margin-top: 10px;
      font-size: 0.9em;
    }
    
    .no-snapshots .tip {
      margin-top: 30px;
      padding-top: 20px;
      border-top: 1px solid #e0e0e0;
    }
    
    /* Modal for full-size image viewing */
    .modal {
      display: none;
      position: fixed;
      z-index: 1000;
      left: 0;
      top: 0;
      width: 100%;
      height: 100%;
      background-color: rgba(0,0,0,0.9);
      cursor: pointer;
    }
    
    .modal-content {
      margin: auto;
      display: block;
      max-width: 90%;
      max-height: 90%;
      position: absolute;
      top: 50%;
      left: 50%;
      transform: translate(-50%, -50%);
    }
    
    .modal-close {
      position: absolute;
      top: 20px;
      right: 35px;
      color: #f1f1f1;
      font-size: 40px;
      font-weight: bold;
      transition: 0.3s;
    }
    
    .modal-close:hover,
    .modal-close:focus {
      color: #bbb;
      cursor: pointer;
    }
    
    @media (max-width: 768px) {
      .container {
        padding: 10px;
      }
      
      header {
        padding: 20px;
      }
      
      header h1 {
        font-size: 1.5em;
      }
      
      .snapshots-grid {
        grid-template-columns: 1fr;
      }
      
      .test-header {
        padding: 15px 20px;
      }
      
      .test-content {
        padding: 20px;
      }
    }
  """
  
  private fun getEmbeddedJavaScript(): String = """
    function toggleSection(testId) {
      const content = document.getElementById('content-' + testId);
      const icon = document.getElementById('icon-' + testId);
      
      content.classList.toggle('collapsed');
      icon.classList.toggle('collapsed');
    }
    
    function openModal(imageId) {
      const modal = document.getElementById('imageModal') || createModal();
      const modalImg = document.getElementById('modalImage');
      const img = document.getElementById(imageId);
      
      modal.style.display = 'block';
      modalImg.src = img.src;
    }
    
    function createModal() {
      const modal = document.createElement('div');
      modal.id = 'imageModal';
      modal.className = 'modal';
      modal.onclick = function() { modal.style.display = 'none'; };
      
      const close = document.createElement('span');
      close.className = 'modal-close';
      close.innerHTML = '&times;';
      
      const img = document.createElement('img');
      img.id = 'modalImage';
      img.className = 'modal-content';
      
      modal.appendChild(close);
      modal.appendChild(img);
      document.body.appendChild(modal);
      
      return modal;
    }
    
    // Close modal with Escape key
    document.addEventListener('keydown', function(event) {
      if (event.key === 'Escape') {
        const modal = document.getElementById('imageModal');
        if (modal) modal.style.display = 'none';
      }
    });
  """
}
