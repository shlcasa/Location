package com.geoint.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

data class GeoResult(
    val country: String?,
    val countryCode: String?,
    val region: String?,
    val city: String?,
    val place: String?,
    val latitude: Double?,
    val longitude: Double?,
    val radiusKm: Double?,
    val confidence: String?,
    val level: String?,
    val summary: String?,
) {
    val hasPin: Boolean
        get() = latitude != null && longitude != null &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0

    /** "Fes, Fes-Meknes, Morocco" without empty parts. */
    val headline: String
        get() = listOfNotNull(place, city, region, country)
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            .joinToString(", ").ifEmpty { "Unknown" }

    companion object {
        private val mapper = ObjectMapper()
        private val FENCE = Regex("```(?:json)?\\s*(\\{[\\s\\S]*?\\})\\s*```", RegexOption.IGNORE_CASE)

        /** Takes the last fenced JSON object in the report; null when there is none or it is malformed. */
        fun parse(report: String): GeoResult? {
            val json = FENCE.findAll(report).lastOrNull()?.groupValues?.get(1) ?: return null
            val node = try {
                mapper.readTree(json)
            } catch (e: Exception) {
                return null
            }
            if (!node.isObject) return null
            return GeoResult(
                country = node.str("country"),
                countryCode = node.str("country_code"),
                region = node.str("region"),
                city = node.str("city"),
                place = node.str("place"),
                latitude = node.num("latitude"),
                longitude = node.num("longitude"),
                radiusKm = node.num("radius_km"),
                confidence = node.str("confidence")?.lowercase(),
                level = node.str("level")?.lowercase(),
                summary = node.str("summary"),
            )
        }

        /** The report without the trailing machine-readable block, for display. */
        fun stripJson(report: String): String {
            val last = FENCE.findAll(report).lastOrNull() ?: return report.trim()
            return report.removeRange(last.range).trim()
        }

        private fun JsonNode.str(key: String): String? =
            get(key)?.takeIf { it.isTextual }?.asText()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

        private fun JsonNode.num(key: String): Double? {
            val v = get(key) ?: return null
            return when {
                v.isNumber -> v.asDouble()
                v.isTextual -> v.asText().trim().toDoubleOrNull()
                else -> null
            }
        }
    }
}

/** Decimal degrees to 34°03'42.1"N 4°59'00.9"W. */
fun toDms(lat: Double, lon: Double): String {
    fun part(value: Double, pos: Char, neg: Char): String {
        val tenths = Math.round(kotlin.math.abs(value) * 36_000) // tenths of an arc-second, rounded once
        val deg = tenths / 36_000
        val min = (tenths % 36_000) / 600
        val sec = (tenths % 600) / 10.0
        return "%d°%02d'%04.1f\"%c".format(java.util.Locale.US, deg, min, sec, if (value >= 0) pos else neg)
    }
    return part(lat, 'N', 'S') + " " + part(lon, 'E', 'W')
}
