package xyz.block.trailblaze.api

import xyz.block.trailblaze.util.escapeForIdentifier
import xyz.block.trailblaze.util.escapeForSelector

// ---------------------------------------------------------------------------
// iOS Maestro strategies (mirrors Android Maestro with iOS-specific fields)
// ---------------------------------------------------------------------------

internal fun iosMaestroStrategies(
  root: TrailblazeNode,
  target: TrailblazeNode,
  detail: DriverNodeDetail.IosMaestro,
  parentMap: Map<Long, TrailblazeNode>,
): List<Pair<String, () -> TrailblazeNodeSelector?>> = listOf(
  "Resource ID" to {
    detail.resourceId?.let { rid ->
      selectorWith(DriverNodeMatch.IosMaestro(resourceIdRegex = escapeForIdentifier(rid)))
    }
  },
  "Accessibility text" to {
    if (detail.text == null && detail.accessibilityText != null) {
      detail.accessibilityText.takeIf { it.isNotBlank() }?.let { a11y ->
        selectorWith(DriverNodeMatch.IosMaestro(accessibilityTextRegex = escapeForSelector(a11y)))
      }
    } else {
      null
    }
  },
  "Text" to {
    detail.text?.takeIf { it.isNotBlank() }?.let { text ->
      selectorWith(DriverNodeMatch.IosMaestro(textRegex = escapeForSelector(text)))
    }
  },
  "Hint text" to {
    if (detail.text.isNullOrBlank() && detail.hintText != null) {
      detail.hintText.takeIf { it.isNotBlank() }?.let { hint ->
        selectorWith(DriverNodeMatch.IosMaestro(hintTextRegex = escapeForSelector(hint)))
      }
    } else {
      null
    }
  },
  "Text + class" to {
    val text = detail.text?.takeIf { it.isNotBlank() }
    val className = detail.className
    if (text != null && className != null) {
      selectorWith(
        DriverNodeMatch.IosMaestro(
          textRegex = escapeForSelector(text),
          classNameRegex = escapeForIdentifier(className),
        ),
      )
    } else {
      null
    }
  },
  "Hint text + class" to {
    if (detail.text.isNullOrBlank() && detail.hintText != null && detail.className != null) {
      selectorWith(
        DriverNodeMatch.IosMaestro(
          hintTextRegex = escapeForSelector(detail.hintText),
          classNameRegex = escapeForIdentifier(detail.className),
        ),
      )
    } else {
      null
    }
  },
  "Resource ID + text" to {
    val rid = detail.resourceId
    val text = detail.text?.takeIf { it.isNotBlank() }
    if (rid != null && text != null) {
      selectorWith(
        DriverNodeMatch.IosMaestro(
          resourceIdRegex = escapeForIdentifier(rid),
          textRegex = escapeForSelector(text),
        ),
      )
    } else {
      null
    }
  },
  "Class name" to {
    detail.className?.let { cn ->
      selectorWith(DriverNodeMatch.IosMaestro(classNameRegex = escapeForIdentifier(cn)))
    }
  },
  childOfUniqueParentStrategy(root, target, detail, parentMap),
  containsUniqueChildStrategy(root, target, detail),
  spatialStrategy(root, target, parentMap),
  indexFallbackStrategy(root, target, detail),
)

// ---------------------------------------------------------------------------
// iOS Maestro structural strategies
// ---------------------------------------------------------------------------

internal fun namedStructuralIosMaestroStrategies(
  root: TrailblazeNode,
  target: TrailblazeNode,
  detail: DriverNodeDetail.IosMaestro,
  parentMap: Map<Long, TrailblazeNode>,
): List<Pair<String, () -> TrailblazeNodeSelector?>> = listOf(
  "Structural: resource ID" to {
    detail.resourceId?.let { rid ->
      selectorWith(DriverNodeMatch.IosMaestro(resourceIdRegex = escapeForIdentifier(rid)))
    }
  },
  "Structural: class name" to {
    detail.className?.let { cn ->
      selectorWith(DriverNodeMatch.IosMaestro(classNameRegex = escapeForIdentifier(cn)))
    }
  },
  structuralChildOfParentStrategy(root, target, detail, parentMap),
  structuralChildOfLabeledParentStrategy(root, target, detail, parentMap),
  structuralContainsChildStrategy(root, target),
  structuralContentAnchoredSpatialStrategy(root, target, parentMap),
  structuralScopedIndexStrategy(root, target, detail, parentMap),
  structuralIndexFallbackStrategy(root, target, detail, name = "Structural: class + index"),
)
