package com.example.airemote

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class OneWinPreSeatJoinEvidenceTest {
    private fun line(text: String, x: Int, y: Int, width: Int = 260,
                     height: Int = 24) = PositionedOcrLine(
        text, NormalizedTextBox(x, y, x + width, y + height))

    private fun buyInLines(): List<PositionedOcrLine> = listOf(
        line("Бай-Ин", 45, 58),
        line("Тип игры: Холдем NL 0.02 $/0.04 $", 50, 127, 880),
        line("Доступно средств: 60.36 $", 50, 176, 880),
        line("С вашего счета будет списано: 1.60 $", 50, 232, 900),
        line("Мин 1.60 $", 60, 308, 260),
        line("1.60 $", 420, 308, 170),
        line("Макс 4 $", 740, 308, 230),
        line("Авто бай-ин и авто ребай", 60, 446, 840),
        line("Ок", 465, 945, 72)
    )

    @Test fun parsesThreeDifferentAmountsWithoutConfusingBlindBankrollAndDebit() {
        val d = OneWinBuyInDialogParser.read(buyInLines())
        assertEquals(OneWinBuyInReadingStatus.VERIFIED, d.status)
        assertEquals(2L, d.smallBlindCents)
        assertEquals(4L, d.bigBlindCents)
        assertEquals(6_036L, d.availableCents)
        assertEquals(160L, d.debitCents)
        assertEquals(160L, d.minimumCents)
        assertEquals(400L, d.maximumCents)
        assertEquals(160L, d.inputCents)
        assertNotNull(d.okBox)
        assertTrue(d.autoRebuyControlVisible)
        assertTrue(d.withinLimits(OneWinCashTableLimits(4L, 160L)))
        assertFalse(d.withinLimits(OneWinCashTableLimits(3L, 160L)))
        assertFalse(d.withinLimits(OneWinCashTableLimits(4L, 100L)))
    }

    @Test fun splitOcrButtonLabelsStillRequireUniqueAmounts() {
        val labels = buyInLines().filterNot {
            it.text == "Мин 1.60 $" || it.text == "Макс 4 $"
        } + listOf(
            line("Мин", 60, 308, 90),
            line("1.60 $", 160, 308, 130),
            line("Макс", 740, 308, 90),
            line("4 $", 835, 308, 100)
        )
        val result = OneWinBuyInDialogParser.read(labels)
        assertEquals(OneWinBuyInReadingStatus.VERIFIED, result.status)
        assertEquals(160L, result.minimumCents)
        assertEquals(400L, result.maximumCents)
        assertEquals(160L, result.debitCents)
        assertEquals(160L, result.inputCents)
        assertEquals(6_036L, result.availableCents)
    }

    @Test fun rechecksUnreadableOrUnexpectedBuyInInsteadOfAssumingApproved() {
        val raw = buyInLines()
        assertEquals(OneWinBuyInReadingStatus.NOT_DIALOG,
            OneWinBuyInDialogParser.read(raw.filterNot { it.text == "Бай-Ин" }).status)
        assertEquals(OneWinBuyInReadingStatus.INCOMPLETE,
            OneWinBuyInDialogParser.read(raw.filterNot { it.text == "Ок" }).status)
        assertEquals(OneWinBuyInReadingStatus.INCOMPLETE,
            OneWinBuyInDialogParser.read(raw.map {
                if (it.text == "1.60 $") it.copy(text = "2.00 $") else it
            }).status)
        assertEquals(OneWinBuyInReadingStatus.INCOMPLETE,
            OneWinBuyInDialogParser.read(raw.map {
                if (it.text.contains("Холдем")) it.copy(text = "Омаха PL 0.02 $/0.04 $")
                else it
            }).status)
        assertEquals(OneWinBuyInReadingStatus.INCOMPLETE,
            OneWinBuyInDialogParser.read(raw.filterNot { it.text.startsWith("Макс") }).status)
    }

    @Test fun secondIndependentOcrFrameMustAgreeOnTheSameBuyIn() {
        val gate = OneWinBuyInAgreement()
        val r = OneWinBuyInDialogParser.read(buyInLines())
        val t1 = OcrFrameTicket(4, 1, 1000, "com.example.game", 2)
        assertNull(gate.observe(t1, r))
        assertNotNull(gate.observe(t1.copy(frameSequence=2, capturedAtMs=2400),r))
        assertNull(gate.observe(t1.copy(frameSequence=3, capturedAtMs=3500,
            windowGeneration=3),r))
        assertNull(gate.observe(t1.copy(frameSequence=4, capturedAtMs=4900,
            windowGeneration=3),r.copy(inputCents=300)))
    }

    private fun arrowPixels(vararg points: Pair<Int,Int>): IntArray {
        val w = 354
        val h = 768
        val image = IntArray(w*h) { 0xff303037.toInt() }
        // Independent small icon-only fixture: contains NO screenshots or player data.
        val raw = Base64.getDecoder().decode(
            "Oz1CRUVIS0xMSUdESj5BO0NEREhNTkhMSklFSEBAO0FERkdKUExNTUlDRUI8O0FEREVNSkpMTUVDRUI8OT9CREJFaq5bRkVBQ0A6OD5BP0NGh/9zPUhAQj85ODw/Oz9Gg/huOkZAQD05Njs9PkxQf+VrWkw5Pjs3NDg7OXqnq96ZuV0/PDk1MTY4OUaj28PQmDc7OTYyMDQ2NjREj8SKPDk0NzUwLzMzNTQtOk09NDIxNTMuLTAwNEdXU1BRWEkvMzAtKy0sN2J2cXdxb2Q6MC4sKSopKy8wMC8vOC8pLSwrJywoJikqLC0sKyssLikoKSssKioqKywpLS4sKioxNDImJSoqKiktLSsoKDFDO0M1LCwqKSgsJyguO0BJPVBGPzkzMDA3OjxHUkpG")
        for ((cx,cy) in points) {
            for (row in 0 until 20) for (col in 0 until 15) {
                val x = cx - 13 + ((col * 2 + 1) * 27 / 30)
                val y = cy - 15 + ((row * 2 + 1) * 36 / 40)
                val bright = raw[row * 15 + col].toInt() and 255
                image[y*w+x] = (0xff000000L or
                    (bright.toLong() shl 16) or (bright.toLong() shl 8) or
                    bright.toLong()).toInt()
            }
        }
        return image
    }

    @Test fun findsOneOrTwoVacanciesRegardlessOfTheirLocation() {
        val one = OneWinFreeSeatArrowDetector.detect(
            arrowPixels(47 to 292),354,768)
        assertEquals(1, one.size)
        val two = OneWinFreeSeatArrowDetector.detect(
            arrowPixels(47 to 292,177 to 138),354,768)
        assertEquals(2, two.size)
        assertTrue(two.any { it.bounds.centerX() in 100..175 &&
            it.bounds.centerY() in 330..430 })
        assertTrue(two.any { it.bounds.centerX() in 465..530 &&
            it.bounds.centerY() in 150..220 })
    }

    @Test fun iconLocationIsNotHeroUntilSeatingIsIndependentlyConfirmed() {
        val arrows = OneWinFreeSeatArrowDetector.detect(
            arrowPixels(47 to 292,177 to 138),354,768)
        val gate = OneWinFreeSeatAgreement()
        val t1 = OcrFrameTicket(3,1,1000,"com.example.game",1)
        assertTrue(gate.observe(t1,arrows).isEmpty())
        assertEquals(2, gate.observe(t1.copy(frameSequence=2,
            capturedAtMs=2400),arrows).size)
        assertTrue(gate.observe(t1.copy(frameSequence=3,
            capturedAtMs=3800,windowGeneration=2),arrows).isEmpty())
        assertTrue(gate.observe(t1.copy(frameSequence=4,
            capturedAtMs=5200,windowGeneration=2),emptyList()).isEmpty())
    }
}
