package jp.poi.widget.data

import org.json.JSONObject
import java.time.LocalDate
import java.time.OffsetDateTime

object WidgetDataParser {

    fun parse(jsonString: String): WidgetData {
        val root = JSONObject(jsonString)

        val updatedAt = root.optStringOrNull("updated_at")?.let {
            try {
                OffsetDateTime.parse(it)
            } catch (e: Exception) {
                null
            }
        }

        val date = root.optStringOrNull("date")?.let {
            try {
                LocalDate.parse(it)
            } catch (e: Exception) {
                null
            }
        }

        val sleep = if (root.has("sleep") && !root.isNull("sleep")) {
            val obj = root.getJSONObject("sleep")
            SleepData(
                durationMin = obj.optIntOrNull("duration_min"),
                score = obj.optIntOrNull("score"),
                deepMin = obj.optIntOrNull("deep_min"),
                remMin = obj.optIntOrNull("rem_min"),
                endTime = obj.optStringOrNull("end_time")
            )
        } else null

        val weight = if (root.has("weight") && !root.isNull("weight")) {
            val obj = root.getJSONObject("weight")
            WeightData(
                kg = obj.optDoubleOrNull("kg"),
                deltaKg = obj.optDoubleOrNull("delta_kg")
            )
        } else null

        val weather = if (root.has("weather") && !root.isNull("weather")) {
            val obj = root.getJSONObject("weather")
            WeatherData(
                area = obj.optStringOrNull("area"),
                summary = obj.optStringOrNull("summary"),
                maxC = obj.optIntOrNull("max_c"),
                minC = obj.optIntOrNull("min_c"),
                rainPct = obj.optIntOrNull("rain_pct")
            )
        } else null

        val today = if (root.has("today") && !root.isNull("today")) {
            val obj = root.getJSONObject("today")
            val nextObj = if (obj.has("next") && !obj.isNull("next")) obj.optJSONObject("next") else null
            val next = nextObj?.let {
                NextSchedule(
                    time = it.optStringOrNull("time"),
                    title = it.optStringOrNull("title")
                )
            }
            TodaySchedule(
                count = obj.optIntOrNull("count"),
                next = next
            )
        } else null

        val todayGarbage = root.optStringOrNull("today_garbage")
        val tomorrowGarbage = root.optStringOrNull("tomorrow_garbage")

        return WidgetData(
            rawJson = jsonString,
            updatedAt = updatedAt,
            date = date,
            sleep = sleep,
            weight = weight,
            weather = weather,
            today = today,
            todayGarbage = todayGarbage,
            tomorrowGarbage = tomorrowGarbage
        )
    }

    private fun JSONObject.optIntOrNull(name: String): Int? {
        return if (has(name) && !isNull(name)) optInt(name) else null
    }

    private fun JSONObject.optDoubleOrNull(name: String): Double? {
        return if (has(name) && !isNull(name)) optDouble(name) else null
    }

    private fun JSONObject.optStringOrNull(name: String): String? {
        if (!has(name) || isNull(name)) return null
        val s = optString(name)
        return if (s.isBlank()) null else s
    }
}
