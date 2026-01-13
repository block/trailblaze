package xyz.block.trailblaze.ui.tabs.sessions.editor

import xyz.block.trailblaze.yaml.TrailblazeYaml

/**
 * Utility for formatting YAML content.
 * 
 * Uses a manual line-by-line approach to fix indentation issues,
 * then optionally applies TrailblazeYaml formatting for proper structure.
 */
object YamlFormatter {

  /**
   * Format YAML content, fixing indentation and structure issues.
   * 
   * This uses a two-pass approach:
   * 1. Manual indentation fixer (works on any YAML, even severely malformed)
   * 2. TrailblazeYaml (if valid) to apply Trailblaze-specific formatting
   * 
   * @param yaml The YAML content to format
   * @return The formatted YAML, or null if the YAML is empty
   */
  fun format(yaml: String): String? {
    if (yaml.isBlank()) return null

    // First pass: Fix indentation manually
    val indentFixed = fixIndentation(yaml)

    // Second pass: Try TrailblazeYaml for Trailblaze-specific formatting
    return try {
      val trailblazeYaml = TrailblazeYaml()
      val parsed = trailblazeYaml.decodeTrail(indentFixed)
      trailblazeYaml.encodeToString(parsed)
    } catch (e: Exception) {
      // If TrailblazeYaml can't parse it, return the indent-fixed result
      indentFixed
    }
  }

  /**
   * Fix indentation issues in YAML content without parsing it.
   * 
   * This works by:
   * 1. Detecting the structure based on key patterns (keys ending with :)
   * 2. Tracking expected indentation levels
   * 3. Fixing lines that are obviously misaligned with their siblings
   */
  private fun fixIndentation(yaml: String): String {
    val lines = yaml.lines()
    if (lines.isEmpty()) return yaml

    val result = mutableListOf<String>()
    
    // Track indentation context: stack of (indent level, is list item context)
    val indentStack = mutableListOf<Int>()
    var lastNonEmptyIndent = 0
    var lastLineWasListItem = false
    var lastLineEndedWithColon = false

    for (line in lines) {
      // Preserve empty lines and comments
      if (line.isBlank()) {
        result.add(line)
        continue
      }

      val trimmed = line.trimStart()
      val currentIndent = line.length - trimmed.length
      
      // Detect line type
      val isListItem = trimmed.startsWith("- ")
      val isKeyLine = trimmed.contains(":") && !trimmed.startsWith("#")
      val keyPart = if (isKeyLine) trimmed.substringBefore(":").trim() else ""
      val endsWithColon = trimmed.endsWith(":") || (isKeyLine && trimmed.substringAfter(":").isBlank())
      
      // Calculate expected indent
      val expectedIndent = when {
        // Top-level list items should have no indent
        isListItem && indentStack.isEmpty() -> 0
        
        // After a line ending with colon, expect +2 indent
        lastLineEndedWithColon -> lastNonEmptyIndent + 2
        
        // List item content after "- " should be at list item indent + 2
        lastLineWasListItem && !isListItem -> lastNonEmptyIndent + 2
        
        // Same-level siblings should match
        else -> {
          // Find appropriate indent from stack or use last known
          if (indentStack.isNotEmpty() && currentIndent > indentStack.last() + 4) {
            // Line is way too indented - it's probably a sibling that got misaligned
            // Find the right level by looking at what makes sense
            findAppropriateIndent(indentStack, currentIndent, lastNonEmptyIndent, lastLineEndedWithColon)
          } else {
            currentIndent // Keep current if it seems reasonable
          }
        }
      }
      
      // Determine the actual indent to use
      val fixedIndent = if (currentIndent > expectedIndent + 4 && !trimmed.startsWith("#")) {
        // Line is significantly over-indented (more than 4 spaces off)
        // This is likely a misaligned sibling
        expectedIndent
      } else if (currentIndent < expectedIndent - 2 && lastLineEndedWithColon) {
        // Line is under-indented after a colon
        expectedIndent
      } else {
        // Indent seems reasonable, normalize to even number
        (currentIndent / 2) * 2
      }
      
      // Build the fixed line
      val fixedLine = " ".repeat(fixedIndent) + trimmed
      result.add(fixedLine)
      
      // Update tracking state
      lastNonEmptyIndent = fixedIndent
      lastLineWasListItem = isListItem
      lastLineEndedWithColon = endsWithColon
      
      // Update indent stack
      when {
        endsWithColon -> {
          // Push new indent level
          while (indentStack.isNotEmpty() && indentStack.last() >= fixedIndent) {
            indentStack.removeLast()
          }
          indentStack.add(fixedIndent)
        }
        fixedIndent == 0 -> {
          // Back to top level
          indentStack.clear()
        }
        indentStack.isNotEmpty() && fixedIndent <= indentStack.last() -> {
          // Popping back to a previous level
          while (indentStack.isNotEmpty() && indentStack.last() >= fixedIndent) {
            indentStack.removeLast()
          }
        }
      }
    }

    return result.joinToString("\n")
  }
  
  /**
   * Find the appropriate indent level for a line that seems misaligned.
   */
  private fun findAppropriateIndent(
    indentStack: List<Int>,
    currentIndent: Int,
    lastNonEmptyIndent: Int,
    lastLineEndedWithColon: Boolean
  ): Int {
    // If the last line ended with colon, the content should be indented by 2
    if (lastLineEndedWithColon) {
      return lastNonEmptyIndent + 2
    }
    
    // The line is likely a sibling to the previous line - use the same indent
    // This handles cases like over-indented fields within a config block
    return lastNonEmptyIndent
  }
}
