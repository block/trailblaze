package xyz.block.trailblaze.android.test.hierarchy

/**
 * Whether a string `View` tag was written by the build tools rather than by a developer.
 *
 * Android Data Binding stores its own bookkeeping in `View.setTag`: the root of an inflated
 * binding layout gets `layout/<layout_name>_<n>` (or `layout-<qualifier>/<name>_<n>`), and every
 * view the generated binding needs a reference to gets `binding_<n>`. `ViewDataBinding.mapBindings`
 * reads them back by exactly those prefixes.
 *
 * They are excluded because a tag is treated as a developer-assigned identifier — it is reported
 * to the agent as `[tag=…]` and is matchable as `tagRegex`. `binding_7` names a slot in generated
 * code, identifies nothing on screen, and in a Data Binding app appears on a large share of the
 * tree, which would bury the tags that do mean something.
 */
internal fun isGeneratedViewTag(tag: String): Boolean =
  BINDING_TAG.matches(tag) || LAYOUT_TAG.matches(tag)

/** `binding_7`. The suffix is the index the generated binding reads back, so it is always numeric. */
private val BINDING_TAG = Regex("""binding_\d+""")

/**
 * `layout/activity_main_0`, or `layout-land/activity_main_0` for a qualified variant.
 *
 * Matched as a whole grammar rather than by prefix: `layout-header` and `layout_selector` are
 * perfectly ordinary tags a developer would write, and dropping them would make those views
 * unselectable by `tagRegex` with nothing to explain why. The `/` is what a hand-written tag
 * essentially never has.
 */
private val LAYOUT_TAG = Regex("""layout(-[^/]+)?/.+_\d+""")
