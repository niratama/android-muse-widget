package jp.poi.widget

import jp.poi.widget.data.WidgetDataParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

class WidgetDataTest {

    private val sampleJson = """
        {
          "updated_at": "2026-11-10T13:21:00+09:00",
          "date": "2026-11-10",
          "sleep": { "duration_min": 393, "score": null, "deep_min": 190, "rem_min": 149, "end_time": "09:21" },
          "weight": { "kg": 65.0, "delta_kg": 0.5 },
          "weather": { "area": "テスト市", "summary": "晴れのち曇", "max_c": 18, "min_c": 10, "rain_pct": 20 },
          "today": { "count": 0, "next": null },
          "today_garbage": "可燃ごみ",
          "tomorrow_garbage": "資源ごみ"
        }
    """.trimIndent()

    @Test
    fun testSampleJsonParsingAndFormatting() {
        val data = WidgetDataParser.parse(sampleJson)

        assertEquals("11/10（火） テスト 晴れのち曇 18/10℃ 雨20%", data.formatWeatherLine())
        // score is null, so score should be omitted:
        assertEquals("睡眠 6時間33分（深い 3:10 / REM 2:29）", data.formatSleepLine())
        assertEquals("体重 65.0kg（前日比 +0.5）", data.formatWeightLine())
        assertEquals("予定 0件 ／ 今日のゴミ: 可燃ごみ ／ 明日: 資源ごみ", data.formatScheduleAndGarbageLine())

        // Test 24 hours check:
        // 2026-11-10T13:21:00+09:00 vs 2026-11-10T15:00:00+09:00 (less than 24h)
        val nowFresh = OffsetDateTime.parse("2026-11-10T15:00:00+09:00")
        assertFalse(data.isOlderThan24Hours(nowFresh))

        // 2026-11-10T13:21:00+09:00 vs 2026-11-11T14:00:00+09:00 (more than 24h)
        val nowOld = OffsetDateTime.parse("2026-11-11T14:00:00+09:00")
        assertTrue(data.isOlderThan24Hours(nowOld))
    }

    @Test
    fun testSleepScorePresent() {
        val json = """
            {
              "updated_at": "2026-11-10T13:21:00+09:00",
              "date": "2026-11-10",
              "sleep": { "duration_min": 393, "score": 82, "deep_min": 190, "rem_min": 149, "end_time": "09:21" }
            }
        """.trimIndent()
        val data = WidgetDataParser.parse(json)
        assertEquals("睡眠 6時間33分 スコア82（深い 3:10 / REM 2:29）", data.formatSleepLine())
    }

    @Test
    fun testMissingWeightOmitted() {
        val json = """
            {
              "updated_at": "2026-11-10T13:21:00+09:00",
              "date": "2026-11-10"
            }
        """.trimIndent()
        val data = WidgetDataParser.parse(json)
        assertNull("Weight line should be null if weight is missing", data.formatWeightLine())
    }

    @Test
    fun testTodayNextSchedule() {
        val json = """
            {
              "updated_at": "2026-11-10T13:21:00+09:00",
              "date": "2026-11-10",
              "today": {
                "count": 2,
                "next": { "time": "19:00", "title": "会議" }
              },
              "today_garbage": "可燃ごみ",
              "tomorrow_garbage": null
            }
        """.trimIndent()
        val data = WidgetDataParser.parse(json)
        assertEquals("予定 2件（次: 19:00 会議） ／ 今日のゴミ: 可燃ごみ", data.formatScheduleAndGarbageLine())
    }
}
