package xyz.block.trailblaze.toolcalls

/**
 * "Did you mean …" for a tool name nobody recognizes.
 *
 * Shared so that every surface that can reject a name answers the same way. The two that matter
 * disagree about which names exist — the agent's tool repo knows the registered dispatch surface,
 * while `toolbox <name>` knows the descriptor surface — and a reader who typed `tapOn` and got a
 * bare "not found" from one of them has no way to learn that `tap` is the name they wanted.
 *
 * Matching is deliberately conservative: a prefix relationship in either direction, or an edit
 * distance that scales with the requested name's length. Offering a far-fetched guess is worse
 * than offering none, because the reader will try it.
 */
object ToolNameSuggestions {

  /** Most suggestions any one message should carry. */
  const val MAX_SUGGESTIONS: Int = 3

  /**
   * Up to [limit] of [candidates] that plausibly repair [requestedName], best first. Empty when
   * nothing is close enough — callers must render no "did you mean" tail at all in that case.
   */
  fun suggestionsFor(
    requestedName: String,
    candidates: Iterable<String>,
    limit: Int = MAX_SUGGESTIONS,
  ): List<String> = candidates
    .asSequence()
    .distinct()
    .filter { candidate -> isHighConfidenceMatch(requestedName, candidate) }
    .sortedWith(compareBy({ matchRank(requestedName, it) }, { it }))
    .take(limit)
    .toList()

  /**
   * The "did you mean" clause to append to a rejection message, ready to concatenate: it opens
   * with a space and is empty when nothing is close enough. Shared so the CLI and the MCP tool
   * word the same answer the same way.
   */
  fun didYouMeanSuffix(requestedName: String, candidates: Iterable<String>): String {
    val suggestions = suggestionsFor(requestedName, candidates)
    return when {
      suggestions.isEmpty() -> ""
      suggestions.size == 1 -> " Did you mean '${suggestions.single()}'?"
      else -> " Did you mean ${suggestions.joinToString(", ") { "'$it'" }}?"
    }
  }

  fun isHighConfidenceMatch(requestedName: String, candidateName: String): Boolean {
    val requested = requestedName.lowercase()
    val candidate = candidateName.lowercase()
    return candidate.startsWith(requested) ||
      requested.startsWith(candidate) ||
      editDistance(requested, candidate) <= minOf(3, maxOf(1, requested.length / 3))
  }

  fun matchRank(requestedName: String, candidateName: String): Int {
    val requested = requestedName.lowercase()
    val candidate = candidateName.lowercase()
    return if (candidate.startsWith(requested) || requested.startsWith(candidate)) {
      0
    } else {
      editDistance(requested, candidate)
    }
  }

  fun editDistance(left: String, right: String): Int {
    var previous = IntArray(right.length + 1) { it }
    for (leftIndex in left.indices) {
      val current = IntArray(right.length + 1)
      current[0] = leftIndex + 1
      for (rightIndex in right.indices) {
        current[rightIndex + 1] = minOf(
          previous[rightIndex + 1] + 1,
          current[rightIndex] + 1,
          previous[rightIndex] + if (left[leftIndex] == right[rightIndex]) 0 else 1,
        )
      }
      previous = current
    }
    return previous[right.length]
  }
}
