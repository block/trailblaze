package xyz.block.trailblaze.toolcalls.commands

import maestro.ScrollDirection
import maestro.SwipeDirection
import xyz.block.trailblaze.yaml.serializers.CaseInsensitiveEnumSerializer

// Maestro's direction enums are third-party, so the serializer can't be nested inside the
// enum per CaseInsensitiveEnumSerializer's documented pattern — these standalone objects are
// applied at the property site via @Serializable(with = ...) instead.

object LenientScrollDirectionSerializer : CaseInsensitiveEnumSerializer<ScrollDirection>(ScrollDirection::class)

object LenientSwipeDirectionSerializer : CaseInsensitiveEnumSerializer<SwipeDirection>(SwipeDirection::class)
