package jp.poi.widget.data

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class SleepData(
    val durationMin: Int?,
    val score: Int?,
    val deepMin: Int?,
    val remMin: Int?,
    val endTime: String?
)

data class WeightData(
    val kg: Double?,
    val deltaKg: Double?
)

data class WeatherData(
    val area: String?,
    val summary: String?,
    val maxC: Int?,
    val minC: Int?,
    val rainPct: Int?
)

data class NextSchedule(
    val time: String?,
    val title: String?
)

data class TodaySchedule(
    val count: Int?,
    val next: NextSchedule?
)

data class WidgetData(
    val rawJson: String,
    val updatedAt: OffsetDateTime?,
    val date: LocalDate?,
    val sleep: SleepData?,
    val weight: WeightData?,
    val weather: WeatherData?,
    val today: TodaySchedule?,
    val todayGarbage: String?,
    val tomorrowGarbage: String?,
    val fetchedAtEpochMs: Long = System.currentTimeMillis()
) {
    /**
     * 行1: 11/10（火） 川口 雨のち曇 13/9℃ 雨60%
     */
    fun formatWeatherLine(): String {
        val datePart = if (date != null) {
            val month = date.monthValue
            val day = date.dayOfMonth
            val dow = when (date.dayOfWeek) {
                DayOfWeek.MONDAY -> "月"
                DayOfWeek.TUESDAY -> "火"
                DayOfWeek.WEDNESDAY -> "水"
                DayOfWeek.THURSDAY -> "木"
                DayOfWeek.FRIDAY -> "金"
                DayOfWeek.SATURDAY -> "土"
                DayOfWeek.SUNDAY -> "日"
                null -> ""
            }
            "${month}/${day}（${dow}）"
        } else ""

        val areaPart = weather?.area?.removeSuffix("市") ?: ""
        val summaryPart = weather?.summary ?: ""
        val w = weather
        val tempPart = if (w?.maxC != null && w.minC != null) {
            "${w.maxC}/${w.minC}℃"
        } else ""
        val rainPart = if (w?.rainPct != null) {
            "雨${w.rainPct}%"
        } else ""

        return listOf(datePart, areaPart, summaryPart, tempPart, rainPart)
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }

    /**
     * 行2: 睡眠 6時間33分 スコア82（深い 3:10 / REM 2:29）
     * スコアが null のときは省く
     */
    fun formatSleepLine(): String? {
        val s = sleep ?: return null
        val duration = s.durationMin ?: return null

        val hours = duration / 60
        val mins = duration % 60
        val durationStr = "${hours}時間${mins}分"

        val scoreStr = if (s.score != null) " スコア${s.score}" else ""

        val details = mutableListOf<String>()
        if (s.deepMin != null) {
            val dH = s.deepMin / 60
            val dM = s.deepMin % 60
            details.add(String.format(Locale.US, "深い %d:%02d", dH, dM))
        }
        if (s.remMin != null) {
            val rH = s.remMin / 60
            val rM = s.remMin % 60
            details.add(String.format(Locale.US, "REM %d:%02d", rH, rM))
        }

        val detailStr = if (details.isNotEmpty()) {
            "（${details.joinToString(" / ")}）"
        } else ""

        return "睡眠 $durationStr$scoreStr$detailStr"
    }

    /**
     * 行3: 体重 77.0kg（前日比 +0.8）
     * 節が無ければ null を返し、行ごと省く
     */
    fun formatWeightLine(): String? {
        val w = weight ?: return null
        val kg = w.kg ?: return null

        val kgStr = String.format(Locale.US, "%.1fkg", kg)
        val deltaStr = if (w.deltaKg != null) {
            val sign = if (w.deltaKg >= 0) "+" else ""
            String.format(Locale.US, "（前日比 %s%.1f）", sign, w.deltaKg)
        } else ""

        return "体重 $kgStr$deltaStr"
    }

    /**
     * 行4: 予定 0件 ／ 今日のゴミ: 一般・有害ごみ ／ 明日: プラスチック製容器包装
     */
    fun formatScheduleAndGarbageLine(): String {
        val count = today?.count ?: 0
        val next = today?.next
        val schedPart = if (next != null && !next.title.isNullOrBlank()) {
            val time = if (!next.time.isNullOrBlank()) "${next.time} " else ""
            "予定 ${count}件（次: $time${next.title}）"
        } else {
            "予定 ${count}件"
        }

        val todayGPart = if (!todayGarbage.isNullOrBlank()) {
            "今日のゴミ: $todayGarbage"
        } else null

        val tomorrowGPart = if (!tomorrowGarbage.isNullOrBlank()) {
            "明日: $tomorrowGarbage"
        } else null

        return listOfNotNull(schedPart, todayGPart, tomorrowGPart).joinToString(" ／ ")
    }

    /**
     * 最終更新時刻が指定日時から24時間より古いかどうか
     */
    fun isOlderThan24Hours(now: OffsetDateTime = OffsetDateTime.now()): Boolean {
        if (updatedAt == null) return false
        val duration = Duration.between(updatedAt, now)
        return duration.toHours() >= 24
    }

    fun formatUpdatedAt(pattern: String = "M/d HH:mm"): String {
        if (updatedAt == null) return ""
        return try {
            updatedAt.format(DateTimeFormatter.ofPattern(pattern, Locale.JAPAN))
        } catch (e: Exception) {
            updatedAt.toString()
        }
    }
}
