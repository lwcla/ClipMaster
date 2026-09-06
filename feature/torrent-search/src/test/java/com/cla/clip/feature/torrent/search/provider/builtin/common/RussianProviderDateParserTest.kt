package com.cla.clip.feature.torrent.search.provider.builtin.common

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 俄文 Provider 共享短日期的固定协议测试。 */
class RussianProviderDateParserTest {
    /** 十二个月与五月属格均映射为固定 UTC 日期。 */
    @Test
    fun parsesEveryReviewedMonthAndMayaAlias() {
        /** tokens 是固定上源会返回的十二个俄文短月。 */
        val tokens = listOf(
            "Янв", "Фев", "Мар", "Апр", "Май", "Июн",
            "Июл", "Авг", "Сен", "Окт", "Ноя", "Дек",
        )
        /** token 是当前月份输入，index 是对应零基月份。 */
        tokens.forEachIndexed { index, token ->
            /** expected 是 2024 年对应月份首日 UTC。 */
            val expected = LocalDate.of(2024, index + 1, 1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()
            assertEquals(
                expected,
                RussianProviderDateParser.parseShortYearEpochMillis("1 $token 24"),
            )
        }
        assertEquals(
            RussianProviderDateParser.parseShortYearEpochMillis("1 Май 24"),
            RussianProviderDateParser.parseShortYearEpochMillis("1 Мая 24"),
        )
    }

    /** 两位年固定扩展为 2000..2099，并按真实日历拒绝非法闰日。 */
    @Test
    fun fixesCenturyAndRejectsInvalidCalendarDates() {
        assertEquals(
            951782400000L,
            RussianProviderDateParser.parseShortYearEpochMillis("29 Фев 00"),
        )
        assertEquals(
            3134592000000L,
            RussianProviderDateParser.parseShortYearEpochMillis("1 Май 69"),
        )
        assertEquals(
            4102358400000L,
            RussianProviderDateParser.parseShortYearEpochMillis("31 Дек 99"),
        )
        assertEquals(
            3981312000000L,
            RussianProviderDateParser.parseShortYearEpochMillis("29 Фев 96"),
        )
        assertNull(RussianProviderDateParser.parseShortYearEpochMillis("29 Фев 99"))
    }

    /** 未知月、缺字段、多字段和非两位数字年必须返回 null。 */
    @Test
    fun rejectsUnknownMonthAndWrongFieldWidth() {
        /** invalidInputs 是共享协议明确拒绝的边界。 */
        val invalidInputs = listOf(
            "1 Неиз 24",
            "1 Янв",
            "1 Янв 2024",
            "1 Янв 2",
            "1 Янв aa",
            "1 Янв 24 extra",
            "",
        )
        /** rawDate 是当前非法输入。 */
        invalidInputs.forEach { rawDate ->
            assertNull(RussianProviderDateParser.parseShortYearEpochMillis(rawDate))
        }
    }
}
