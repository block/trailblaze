package xyz.block.trailblaze.decision

/**
 * Words that scope a step or claim to one platform or device ("on iOS", "on tablets", "in the
 * browser"), as a regex alternation. Neither the decision engine nor the verify fast path knows
 * which device a run is on, so both leave such text to the LLM. Quoted labels are the callers'
 * concern: the fast path strips them before matching.
 */
internal const val PLATFORM_SCOPE_WORDS = "ios|androids?|tablets?|ipads?|iphones?|phones?|web|browsers?"
