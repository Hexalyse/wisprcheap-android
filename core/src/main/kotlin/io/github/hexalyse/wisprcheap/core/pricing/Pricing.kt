package io.github.hexalyse.wisprcheap.core.pricing

import io.github.hexalyse.wisprcheap.core.settings.PriceOverride

/** Price estimates in USD (desktop `pricing.rs`, September 2026 list prices), with user overrides. */
class Pricing(private val overrides: Map<String, PriceOverride> = emptyMap()) {
    fun transcriptionPerMinute(model: String): Double? =
        overrides[model]?.perMinute ?: TRANSCRIPTION_PER_MINUTE[model]

    /** (input, output) USD per million tokens. */
    fun llmPerMillion(model: String): Pair<Double, Double>? {
        val o = overrides[model]
        if (o?.inputPerM != null && o.outputPerM != null) return o.inputPerM to o.outputPerM
        return LLM_PER_MILLION[model]
    }

    fun transcriptionCost(model: String, durationSec: Double, keytermCount: Int): Double? {
        var rate = transcriptionPerMinute(model) ?: return null
        var billed = durationSec
        if (model.startsWith("scribe") && keytermCount > 0) {
            rate += SCRIBE_KEYTERMS_PER_MINUTE
            // Above 100 keyterms, ElevenLabs bills each request at least 20 s.
            if (keytermCount > 100) billed = maxOf(billed, 20.0)
        }
        return billed / 60.0 * rate
    }

    fun llmCost(model: String, inputTokens: Long, outputTokens: Long): Double? {
        val (i, o) = llmPerMillion(model) ?: return null
        return (inputTokens * i + outputTokens * o) / 1e6
    }

    /** Models with a known price, for suggestions in the settings. */
    val knownTranscriptionModels: List<String> get() = TRANSCRIPTION_PER_MINUTE.keys.toList()
    val knownLlmModels: List<String> get() = LLM_PER_MILLION.keys.toList()

    companion object {
        /** Scribe keyterm surcharge per audio minute. */
        const val SCRIBE_KEYTERMS_PER_MINUTE = 0.05 / 60.0

        val TRANSCRIPTION_PER_MINUTE: Map<String, Double> = linkedMapOf(
            "scribe_v2" to 0.22 / 60.0,
            "scribe_v1" to 0.22 / 60.0,
            "gpt-4o-transcribe" to 0.006,
            "gpt-4o-mini-transcribe" to 0.003,
            "gpt-transcribe" to 0.0045,
            "whisper-1" to 0.006,
        )

        val LLM_PER_MILLION: Map<String, Pair<Double, Double>> = linkedMapOf(
            "gpt-6-luna" to (0.1 to 0.5),
            "gpt-6-sol" to (2.0 to 10.0),
            "gpt-5.6-luna" to (0.2 to 1.2),
            "gpt-5.4-nano" to (0.2 to 1.25),
            "gpt-5.4-mini" to (0.75 to 4.5),
            "gpt-5-nano" to (0.05 to 0.4),
            "gpt-5-mini" to (0.25 to 2.0),
            "gpt-4.1-nano" to (0.1 to 0.4),
            "gpt-4.1-mini" to (0.4 to 1.6),
            "gpt-4o-mini" to (0.15 to 0.6),
        )
    }
}
