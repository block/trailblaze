package xyz.block.trailblaze.decision

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/*
 * A decision request: a primitive request type beside the LLM chat request. Instead of asking a
 * model to generate text or a tool call, it asks typed questions about a piece of state and reads
 * the answer straight off the model's output distribution, so every answer carries its
 * probabilities and a confidence.
 *
 * The wire shape follows the TypeSafe `POST /v1/systemone` contract field for field
 * (https://docs.typesafe.ai/api), the emerging shared shape for this class of "decision model".
 * Keeping it verbatim means any engine that speaks that contract (hosted or self-hosted) can back a
 * [DecisionEngine] without translation, and a session log's decision entries are valid requests
 * and responses of that API.
 *
 * The types are deliberately flat (a `type` field, not a sealed hierarchy): session logs encode
 * polymorphism with a `class` discriminator, and a flat shape keeps this JSON identical to the
 * contract under every Json configuration.
 */

/** The three question types of the contract. */
@Serializable
enum class DecisionQuestionType {
  /** Pick one of up to 255 named options. */
  @SerialName("choice") CHOICE,

  /** Rate along an ordered rubric of 2 to 10 levels. */
  @SerialName("score") SCORE,

  /** Yes or no, answered as the probability of yes. */
  @SerialName("noul") NOUL,
}

/**
 * One question. [criteria] holds the contract's per-type criteria exactly: an option-to-description
 * object for [DecisionQuestionType.CHOICE], an ordered array of level descriptions for
 * [DecisionQuestionType.SCORE], and an optional `{"true": …, "false": …}` object for
 * [DecisionQuestionType.NOUL]. Build one with [choice], [score] or [noul].
 */
@Serializable
data class DecisionQuestion(
  val type: DecisionQuestionType,
  val instructions: String,
  val criteria: JsonElement? = null,
) {
  /** Options and their descriptions, in order, for a choice question; empty otherwise. */
  val choiceOptions: Map<String, String>
    get() = if (type == DecisionQuestionType.CHOICE) (criteria as? JsonObject).stringValues() else emptyMap()

  /** Rubric levels, lowest first, for a score question; empty otherwise. */
  val scoreLevels: List<String>
    get() =
      if (type == DecisionQuestionType.SCORE) {
        (criteria as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
      } else {
        emptyList()
      }

  /** What "yes" and "no" mean for a noul question, when given. */
  val noulMeanings: Map<String, String>
    get() = if (type == DecisionQuestionType.NOUL) (criteria as? JsonObject).stringValues() else emptyMap()

  companion object {
    const val MAX_CHOICE_OPTIONS = 255
    const val MIN_SCORE_LEVELS = 2
    const val MAX_SCORE_LEVELS = 10

    fun choice(instructions: String, options: Map<String, String>): DecisionQuestion {
      require(options.isNotEmpty() && options.size <= MAX_CHOICE_OPTIONS) {
        "a choice question needs 1 to $MAX_CHOICE_OPTIONS options, got ${options.size}"
      }
      return DecisionQuestion(
        type = DecisionQuestionType.CHOICE,
        instructions = instructions,
        criteria = JsonObject(options.mapValues { JsonPrimitive(it.value) }),
      )
    }

    fun score(instructions: String, levels: List<String>): DecisionQuestion {
      require(levels.size in MIN_SCORE_LEVELS..MAX_SCORE_LEVELS) {
        "a score question needs $MIN_SCORE_LEVELS to $MAX_SCORE_LEVELS levels, got ${levels.size}"
      }
      return DecisionQuestion(
        type = DecisionQuestionType.SCORE,
        instructions = instructions,
        criteria = JsonArray(levels.map { JsonPrimitive(it) }),
      )
    }

    fun noul(instructions: String, yes: String? = null, no: String? = null): DecisionQuestion =
      DecisionQuestion(
        type = DecisionQuestionType.NOUL,
        instructions = instructions,
        criteria =
          if (yes == null && no == null) {
            null
          } else {
            JsonObject(
              buildMap {
                yes?.let { put("true", JsonPrimitive(it)) }
                no?.let { put("false", JsonPrimitive(it)) }
              }
            )
          },
      )
  }
}

/**
 * The request body. [state] is whatever is being judged: a string, object or array. Question keys
 * are the caller's own ids and come back as the keys of [DecisionResponse.answers]; one request
 * carries every question about the same state, so they are answered against one read of it.
 */
@Serializable
data class DecisionRequest(
  val state: JsonElement,
  val model: String,
  val questions: Map<String, DecisionQuestion>,
) {
  constructor(
    state: String,
    model: String,
    questions: Map<String, DecisionQuestion>,
  ) : this(JsonPrimitive(state), model, questions)
}

/**
 * One answer, shaped by its [type]:
 * - choice: [choice], [probabilities] per option, [confidence].
 * - score: [score] (the probability-weighted level, so possibly fractional), [legend] and
 *   [probabilities] keyed by level index (`"0"`, `"1"`, …), [confidence].
 * - noul: [noul], the probability of yes.
 *
 * [confidence] is the peak of the distribution rescaled so a uniform guess reads 0 and certainty
 * reads 1; see [DecisionConfidence].
 */
@Serializable
data class DecisionAnswer(
  val type: DecisionQuestionType,
  val choice: String? = null,
  val score: Double? = null,
  val legend: Map<String, String>? = null,
  val noul: Double? = null,
  val probabilities: Map<String, Double>? = null,
  val confidence: Double? = null,
) {
  /** A short human reading, e.g. `billing (confidence 0.81)` or `yes 0.95`. */
  fun describe(): String {
    val conf = confidence?.let { " (confidence ${it.format2()})" }.orEmpty()
    return when (type) {
      DecisionQuestionType.CHOICE -> "${choice ?: "?"}$conf"
      DecisionQuestionType.SCORE -> {
        val nearest = score?.let { legend?.get(kotlin.math.round(it).toInt().toString()) }
        "${score?.format2() ?: "?"}${nearest?.let { " ~ $it" }.orEmpty()}$conf"
      }
      DecisionQuestionType.NOUL -> "yes ${noul?.format2() ?: "?"}"
    }
  }
}

private fun Double.format2(): String {
  val hundredths = kotlin.math.round(this * 100).toLong()
  val whole = hundredths / 100
  val frac = kotlin.math.abs(hundredths % 100)
  return "${if (hundredths < 0 && whole == 0L) "-" else ""}$whole.${frac.toString().padStart(2, '0')}"
}

@Serializable
data class DecisionUsage(
  @SerialName("input_tokens") val inputTokens: Int,
  @SerialName("output_tokens") val outputTokens: Int,
)

/** The response body: [model] is the exact model that answered, which may differ from the alias asked for. */
@Serializable
data class DecisionResponse(
  val model: String,
  val answers: Map<String, DecisionAnswer>,
  val usage: DecisionUsage? = null,
)

/**
 * Anything that answers a [DecisionRequest]: a hosted decision API, a self-hosted server speaking
 * the same contract, or an adapter that reads the answers out of a general LLM's token
 * probabilities. Callers depend on this, never on an engine, so engines swap without code changes.
 */
interface DecisionEngine {
  /** Short stable id for logs and reports, e.g. `"systemone:https://host"`. */
  val name: String

  /**
   * The most options this engine can weigh in one choice question. The contract allows
   * [DecisionQuestion.MAX_CHOICE_OPTIONS]; an adapter over a general LLM is bounded by how many
   * alternatives that LLM reports probabilities for. Callers size their option lists to this.
   */
  val maxChoiceOptions: Int
    get() = DecisionQuestion.MAX_CHOICE_OPTIONS

  suspend fun decide(request: DecisionRequest): DecisionResponse

  /**
   * What [response] cost, when this engine knows its price. The contract carries token usage but
   * no price, so an engine that can price its answers says so here; the default is unknown.
   */
  fun costOf(response: DecisionResponse): DecisionCost? = null
}

/** What one decision cost, in US dollars. Logged beside the contract, never sent on the wire. */
@Serializable
data class DecisionCost(val inputCost: Double, val outputCost: Double) {
  val totalCost: Double
    get() = inputCost + outputCost
}

/** The contract's confidence: the distribution's peak, rescaled so uniform is 0 and certain is 1. */
object DecisionConfidence {
  fun of(probabilities: Collection<Double>): Double {
    val n = probabilities.size
    if (n < 2) return if (n == 1) 1.0 else 0.0
    val total = probabilities.sum()
    if (total <= 0.0) return 0.0
    val peak = probabilities.max() / total
    return ((n * peak - 1.0) / (n - 1.0)).coerceIn(0.0, 1.0)
  }
}

private fun JsonObject?.stringValues(): Map<String, String> =
  this?.entries?.associate { (k, v) -> k to ((v as? JsonPrimitive)?.contentOrNull ?: v.toString()) }
    .orEmpty()
