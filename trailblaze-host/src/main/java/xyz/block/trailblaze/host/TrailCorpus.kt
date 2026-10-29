package xyz.block.trailblaze.host

import java.io.File
import xyz.block.trailblaze.yaml.TrailYamlValidator
import xyz.block.trailblaze.yaml.TrailblazeYaml
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.yaml.unified.TrailDocument

/**
 * Every trail under one trails root, found by one walk and each read and decoded at most once, for
 * the per-trail phases of `trailblaze check`: recording type-validation, arg tokens, and the trail
 * lints. Each phase used to walk the tree and decode every trail itself, so a large workspace paid
 * for three walks and three full decodes per trail.
 *
 * Reading and decoding are lazy and per trail, so a phase that is switched off costs nothing, and
 * the first phase to reach a trail does the work on whichever worker thread it runs. A failure is
 * kept and handed to every phase that asks, so each still reports it its own way.
 */
class TrailCorpus private constructor(
  val trailsRoot: File,
  val trails: List<Trail>,
) {

  class Trail internal constructor(
    val file: File,
    private val decoder: ThreadLocal<TrailblazeYaml>,
  ) {
    /** The file's text, or what reading it threw. */
    val text: Result<String> by lazy { runCatching { file.readText() } }

    /**
     * The decoded trail, or what reading or decoding threw. Throwable, not Exception: the decoder
     * reports an unknown tool as a non-Exception Throwable, and one bad trail must not end a phase.
     */
    val document: Result<TrailDocument> by lazy {
      text.mapCatching { decoder.get().decodeTrailDocument(it) }
    }
  }

  /** The trails a recording can live in: `<stem>.trail.yaml` and the unified `trail.yaml`. */
  val recordingTrails: List<Trail> by lazy { trails.filter { TrailTscValidator.isTrailFile(it.file.name) } }

  companion object {
    /**
     * Walks [trailsRoot] once with the canonical trail discovery ([TrailYamlValidator.findAllTrailYamlFiles]).
     * A missing root is an empty corpus.
     */
    fun load(trailsRoot: File): TrailCorpus {
      // One decoder per worker thread, shared by every trail: building one wires the whole tool
      // registry, and decoders are not thread-safe.
      val decoder = ThreadLocal.withInitial { createTrailblazeYaml() }
      return TrailCorpus(
        trailsRoot = trailsRoot,
        trails = TrailYamlValidator.findAllTrailYamlFiles(trailsRoot).map { Trail(it, decoder) },
      )
    }
  }
}
