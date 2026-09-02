package xyz.block.trailblaze.api

import xyz.block.trailblaze.util.escapeForIdentifier
import xyz.block.trailblaze.util.escapeForSelector

// ---------------------------------------------------------------------------
// Android View strategies (live android.view.View tree, captured in-process)
// ---------------------------------------------------------------------------

/**
 * Stability order: `resourceId` > `tag` > text-like fields > class + hierarchy > index.
 *
 * [DriverNodeDetail.AndroidView.tag] ranks second because it is developer-assigned like a
 * resource id but is invisible to the accessibility tree, so this is the only generator that can
 * offer it. It sits below `resourceId` only because a tag is untyped and apps sometimes reuse one
 * across a list.
 *
 * Without these strategies a recorded or LLM-authored tap on this driver would have no selector to
 * emit and would degrade to raw coordinates.
 */
internal fun androidViewStrategies(
  root: TrailblazeNode,
  target: TrailblazeNode,
  detail: DriverNodeDetail.AndroidView,
  parentMap: Map<Long, TrailblazeNode>,
): List<Pair<String, () -> TrailblazeNodeSelector?>> = listOf(
  // === Identity strategies ===

  "Resource ID" to {
    detail.resourceId?.let { rid ->
      selectorWith(DriverNodeMatch.AndroidView(resourceIdRegex = escapeForIdentifier(rid)))
    }
  },
  "View tag" to {
    detail.tag?.takeIf { it.isNotBlank() }?.let { tag ->
      selectorWith(DriverNodeMatch.AndroidView(tagRegex = escapeForIdentifier(tag)))
    }
  },

  // === Precise text strategies (specific fields, not resolveText) ===

  "Content description" to {
    if (detail.text == null && detail.contentDescription != null) {
      detail.contentDescription.takeIf { it.isNotBlank() }?.let { desc ->
        selectorWith(DriverNodeMatch.AndroidView(contentDescriptionRegex = stableTextAnchorRegex(desc)))
      }
    } else {
      null
    }
  },
  "Content description + class" to {
    if (detail.text == null && detail.contentDescription != null && detail.className != null) {
      selectorWith(
        DriverNodeMatch.AndroidView(
          contentDescriptionRegex = escapeForSelector(detail.contentDescription),
          classNameRegex = escapeForIdentifier(detail.className),
        ),
      )
    } else {
      null
    }
  },
  // An EditText built with View.generateViewId() has no resourceId and no text until something
  // types into it, so the hint is routinely the only handle that exists.
  "Hint text" to {
    if (detail.text.isNullOrBlank() && detail.hintText != null) {
      detail.hintText.takeIf { it.isNotBlank() }?.let { hint ->
        selectorWith(DriverNodeMatch.AndroidView(hintTextRegex = stableTextAnchorRegex(hint)))
      }
    } else {
      null
    }
  },
  "Hint text + class" to {
    if (detail.text.isNullOrBlank() && detail.hintText != null && detail.className != null) {
      selectorWith(
        DriverNodeMatch.AndroidView(
          hintTextRegex = escapeForSelector(detail.hintText),
          classNameRegex = escapeForIdentifier(detail.className),
        ),
      )
    } else {
      null
    }
  },
  // Skip text on editable fields — user-entered content is not stable identity.
  "Text" to {
    if (!detail.isEditable) {
      detail.text?.takeIf { it.isNotBlank() }?.let { text ->
        selectorWith(DriverNodeMatch.AndroidView(textRegex = stableTextAnchorRegex(text)))
      }
    } else {
      null
    }
  },
  "Text + class" to {
    if (!detail.isEditable) {
      val text = detail.text?.takeIf { it.isNotBlank() }
      val className = detail.className
      if (text != null && className != null) {
        selectorWith(
          DriverNodeMatch.AndroidView(
            textRegex = stableTextAnchorRegex(text),
            classNameRegex = escapeForIdentifier(className),
          ),
        )
      } else {
        null
      }
    } else {
      null
    }
  },
  // A custom view often publishes its only semantic handle here.
  "State description + class" to {
    val state = detail.stateDescription?.takeIf { it.isNotBlank() }
    val className = detail.className
    if (state != null && className != null) {
      selectorWith(
        DriverNodeMatch.AndroidView(
          stateDescriptionRegex = escapeForSelector(state),
          classNameRegex = escapeForIdentifier(className),
        ),
      )
    } else {
      null
    }
  },
  // The editable input on a screen with exactly one, where nothing else identifies it.
  "Class + editable" to {
    if (detail.isEditable) {
      detail.className?.let { className ->
        selectorWith(
          DriverNodeMatch.AndroidView(
            classNameRegex = escapeForIdentifier(className),
            isEditable = true,
          ),
        )
      }
    } else {
      null
    }
  },

  // === Hierarchy strategies (shared across all generators) ===

  childOfUniqueParentStrategy(root, target, detail, parentMap),
  containsUniqueChildStrategy(root, target, detail),
  spatialStrategy(root, target, parentMap),
  indexFallbackStrategy(root, target, detail),
)

// ---------------------------------------------------------------------------
// Android View structural strategies (no text/content properties)
// ---------------------------------------------------------------------------

internal fun namedStructuralAndroidViewStrategies(
  root: TrailblazeNode,
  target: TrailblazeNode,
  detail: DriverNodeDetail.AndroidView,
  parentMap: Map<Long, TrailblazeNode>,
): List<Pair<String, () -> TrailblazeNodeSelector?>> = listOf(
  "Structural: resource ID" to {
    detail.resourceId?.let { rid ->
      selectorWith(DriverNodeMatch.AndroidView(resourceIdRegex = escapeForIdentifier(rid)))
    }
  },
  "Structural: view tag" to {
    detail.tag?.takeIf { it.isNotBlank() }?.let { tag ->
      selectorWith(DriverNodeMatch.AndroidView(tagRegex = escapeForIdentifier(tag)))
    }
  },
  "Structural: class name" to {
    detail.className?.let { cn ->
      selectorWith(DriverNodeMatch.AndroidView(classNameRegex = escapeForIdentifier(cn)))
    }
  },
  structuralChildOfParentStrategy(root, target, detail, parentMap),
  structuralChildOfLabeledParentStrategy(root, target, detail, parentMap),
  structuralContainsChildStrategy(root, target),
  structuralContentAnchoredSpatialStrategy(root, target, parentMap),
  structuralScopedIndexStrategy(root, target, detail, parentMap),
  structuralIndexFallbackStrategy(root, target, detail, name = "Structural: class + index"),
)
