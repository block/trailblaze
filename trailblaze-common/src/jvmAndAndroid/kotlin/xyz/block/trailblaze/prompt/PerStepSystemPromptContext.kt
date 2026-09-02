package xyz.block.trailblaze.prompt

/** Appends session state that must be re-read at each prompt-step boundary. */
fun String.withPerStepSystemPromptContext(context: String?): String =
  if (context.isNullOrBlank()) this else "$this\n\n$context"
