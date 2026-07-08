package xyz.block.trailblaze.maestro

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.yaml.serializers.CaseInsensitiveEnumSerializer

@Serializable(with = TrailblazeScrollStartPosition.Serializer::class)
enum class TrailblazeScrollStartPosition {
  CENTER,
  TOP,
  BOTTOM,
  ;

  object Serializer :
    CaseInsensitiveEnumSerializer<TrailblazeScrollStartPosition>(TrailblazeScrollStartPosition::class)
}