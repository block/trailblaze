package xyz.block.trailblaze.prompt

/**
 * Adds a trail's `config.context` to the system prompt as context, never as an instruction.
 *
 * The context says what the run sets out to accomplish, the setup it assumes, and rules for
 * carrying it out; the trail's steps are the instructions, given to the agent one at a time. The
 * contexts sit between [TRAIL_CONTEXT_OPEN] and [TRAIL_CONTEXT_CLOSE], so a heading an author
 * writes inside one stays inside the section, and a closing tag inside one can't end it early.
 */
internal fun String.withTrailContext(contexts: List<String>): String {
  val texts = contexts.map { it.replace(TRAIL_CONTEXT_CLOSE, "</ trail_context>").trim() }.filter { it.isNotEmpty() }
  if (texts.isEmpty()) return this
  return "$this\n\n$TRAIL_CONTEXT_HEADER\n\n$TRAIL_CONTEXT_OPEN\n" +
    texts.joinToString("\n\n") + "\n$TRAIL_CONTEXT_CLOSE"
}

internal const val TRAIL_CONTEXT_OPEN = "<trail_context>"
internal const val TRAIL_CONTEXT_CLOSE = "</trail_context>"

internal const val TRAIL_CONTEXT_HEADER =
  "## Trail context\n" +
    "Between the $TRAIL_CONTEXT_OPEN tags below, the trail's author describes WHAT this run is " +
    "trying to achieve and the setup it assumes. It is context, not an instruction. The trail's " +
    "steps are HOW it gets done, and they are the instructions: you get them one at a time, and " +
    "your task is only the objective you are given now. Use the context to understand what that objective is for, and " +
    "what the app, the data, and the terms mean. Do not perform setup or actions the context " +
    "describes unless your objective asks for them. Do follow any rule it states for how to " +
    "carry out an objective (such as which payment method to use, or how to dismiss a dialog)."
