package radio.ks3ckc.ft8af.bandadvisor.model

import org.json.JSONArray
import org.json.JSONObject
import radio.ks3ckc.ft8af.flags.parseIso8601UtcMs

/**
 * Parse + validate a recommendation JSON document from the band-advisor
 * service (or a fixture). Returns null for anything that fails validation —
 * a recommendation with a bogus frequency or out-of-range score must never
 * reach the UI or, worse, the tuning path. Never throws.
 */
fun parseBandRecommendation(json: String): BandRecommendation? {
    return try {
        val root = JSONObject(json)
        val generatedAtMs = parseIso8601UtcMs(root.optString("generatedAt")) ?: return null
        val validUntilMs = parseIso8601UtcMs(root.optString("validUntil")) ?: return null
        if (validUntilMs <= generatedAtMs) return null
        val grid = normalizeAdvisorGrid(root.optString("grid")) ?: return null
        val band = root.optString("recommendedBand")
        if (band.isBlank()) return null
        val frequencyHz = root.optLong("recommendedFrequencyHz", -1)
        // Sanity bounds: all amateur HF/VHF FT8 dials live well inside this.
        if (frequencyHz < 100_000 || frequencyHz > 1_500_000_000) return null
        val score = root.optDouble("score", Double.NaN)
        val confidence = root.optDouble("confidence", Double.NaN)
        if (!score.isFinite() || score < 0.0 || score > 1.0) return null
        if (!confidence.isFinite() || confidence < 0.0 || confidence > 1.0) return null

        val callsign = root.optString("callsign").takeIf { it.isNotBlank() }
        val sourcesObj = root.optJSONObject("sources")
        val sources = SourceAvailability(
            voacapAvailable = sourcesObj?.optBoolean("voacapAvailable", false) ?: false,
            regionalPskReporterAvailable =
                sourcesObj?.optBoolean("regionalPskReporterAvailable", false) ?: false,
            personalPskReporterAvailable =
                sourcesObj?.optBoolean("personalPskReporterAvailable", false) ?: false,
        )

        BandRecommendation(
            generatedAtMs = generatedAtMs,
            validUntilMs = validUntilMs,
            grid = grid,
            callsign = callsign,
            mode = root.optString("mode", "FT8").ifBlank { "FT8" },
            goal = OperatingGoal.fromWireName(root.optString("goal")),
            recommendedBand = band,
            recommendedFrequencyHz = frequencyHz,
            score = score,
            confidence = confidence,
            summary = root.optString("summary"),
            destinations = stringList(root.optJSONArray("destinations")),
            evidence = parseEvidence(root.optJSONArray("evidence")),
            scoreComponents = parseScoreComponents(root.optJSONArray("scoreComponents")),
            personalAnalytics = parsePersonalAnalytics(root.optJSONObject("personalAnalytics")),
            alternatives = parseAlternatives(root.optJSONArray("alternatives")),
            sources = sources,
        )
    } catch (_: Exception) {
        null
    }
}

private fun stringList(array: JSONArray?): List<String> {
    array ?: return emptyList()
    return (0 until array.length()).mapNotNull { i ->
        (array.opt(i) as? String)?.takeIf { it.isNotBlank() }
    }
}

private fun parseEvidence(array: JSONArray?): List<Evidence> {
    array ?: return emptyList()
    return (0 until array.length()).mapNotNull { i ->
        val obj = array.optJSONObject(i) ?: return@mapNotNull null
        val message = obj.optString("message")
        if (message.isBlank()) return@mapNotNull null
        Evidence(EvidenceType.fromWireName(obj.optString("type")), message)
    }
}

private fun parseScoreComponents(array: JSONArray?): List<ScoreComponent> {
    array ?: return emptyList()
    return (0 until array.length()).mapNotNull { i ->
        val obj = array.optJSONObject(i) ?: return@mapNotNull null
        val name = obj.optString("name")
        val weight = obj.optDouble("weight", Double.NaN)
        val value = obj.optDouble("value", Double.NaN)
        val contribution = obj.optDouble("contribution", Double.NaN)
        if (name.isBlank() || !weight.isFinite() || !value.isFinite() || !contribution.isFinite()) {
            return@mapNotNull null
        }
        ScoreComponent(name, weight, value, contribution)
    }
}

private fun parsePersonalAnalytics(obj: JSONObject?): PersonalAnalyticsSummary? {
    obj ?: return null
    val baseline = obj.optDouble("performanceComparedToBaseline", Double.NaN)
    return PersonalAnalyticsSummary(
        enabled = obj.optBoolean("enabled", false),
        reportsReceived = obj.optInt("reportsReceived", 0).coerceAtLeast(0),
        uniqueReceivers = obj.optInt("uniqueReceivers", 0).coerceAtLeast(0),
        countriesReached = obj.optInt("countriesReached", 0).coerceAtLeast(0),
        maximumDistanceKm = obj.optInt("maximumDistanceKm", 0).coerceAtLeast(0),
        medianSnrDb = if (obj.has("medianSnrDb")) obj.optInt("medianSnrDb") else null,
        bestSnrDb = if (obj.has("bestSnrDb")) obj.optInt("bestSnrDb") else null,
        performanceComparedToBaseline = baseline.takeIf { it.isFinite() },
    )
}

private fun parseAlternatives(array: JSONArray?): List<BandAlternative> {
    array ?: return emptyList()
    return (0 until array.length()).mapNotNull { i ->
        val obj = array.optJSONObject(i) ?: return@mapNotNull null
        val band = obj.optString("band")
        val freq = obj.optLong("frequencyHz", -1)
        val score = obj.optDouble("score", Double.NaN)
        if (band.isBlank() || freq <= 0 || !score.isFinite()) return@mapNotNull null
        BandAlternative(band, freq, score.coerceIn(0.0, 1.0), obj.optString("reason"))
    }
}
