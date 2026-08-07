/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.obj

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// ISO 32000-2:2020 §7.9.4 "Dates", §14.3.3 "Document information dictionary"

/**
 * The written form of a date, against the clause that defines it and the two
 * examples the standard writes one out in.
 */
class PdfDateTest {
    /**
     * The example of §14.3.3, which is a date with every field present:
     *
     * > 101 0 obj                    %document information dictionary
     * >       <</CreationDate (D:20140314124211+01'00)
     * >          /ModDate (D:20140924212303+02'00)
     * >       >>
     */
    @Test
    fun `the dates of the example information dictionary are written as the clause writes them`() {
        assertEquals(
            "D:20140314124211+01'00",
            PdfDate(2014, 3, 14, 12, 42, 11, offsetMinutes = 60).format(),
        )
        assertEquals(
            "D:20140924212303+02'00",
            PdfDate(2014, 9, 24, 21, 23, 3, offsetMinutes = 120).format(),
        )
    }

    /**
     * The example of §7.9.4 itself — "December 23, 1998, at 7:52 PM, U.S.
     * Pacific Standard Time, is represented by the string: D:199812231952-08'00"
     * — which is the same instant this writes, with the optional seconds field
     * omitted. The clause allows the omission and gives the omitted field a
     * default of zero ("all other numerical fields shall default to zero
     * values"), so the two strings denote one date; every field is written here.
     */
    @Test
    fun `the example of the dates clause is that date with its seconds written out`() {
        val written = PdfDate(1998, 12, 23, 19, 52, 0, offsetMinutes = -8 * 60).format()
        assertEquals("D:19981223195200-08'00", written)
        assertEquals("D:199812231952" + "00" + "-08'00", written)
    }

    /**
     * Local time equal to UT is the LATIN CAPITAL LETTER Z, and NOTE 3 lets the
     * two zero offset fields follow it: "The letter Z can optionally be followed
     * by hour and minute offsets, which are zero in this case."
     */
    @Test
    fun `an offset of zero is written with Z and the zero offset fields`() {
        assertEquals("D:20260805143000Z00'00", PdfDate(2026, 8, 5, 14, 30, 0).format())
    }

    /** Every field is fixed-width, so the year 7 is `0007` and not `7`. */
    @Test
    fun `every field is written with its leading zeros`() {
        assertEquals("D:00070102030405Z00'00", PdfDate(7, 1, 2, 3, 4, 5).format())
    }

    /** An offset with minutes in it: "mm shall be the absolute value of the offset from UT in minutes". */
    @Test
    fun `an offset that is not a whole hour writes both of its fields`() {
        assertEquals("D:20260805143000+05'45", PdfDate(2026, 8, 5, 14, 30, 0, offsetMinutes = 5 * 60 + 45).format())
        assertEquals("D:20260805143000-03'30", PdfDate(2026, 8, 5, 14, 30, 0, offsetMinutes = -(3 * 60 + 30)).format())
    }

    /**
     * The string object is the literal form, and its bytes are the date: "A date
     * string can be any valid PDF string object as described in 7.3.4, 'String
     * objects'. The description above relates to the text string value after
     * appropriate processing" (NOTE 1).
     */
    @Test
    fun `the string object carries the written form and no other bytes`() {
        val date = PdfDate(2014, 3, 14, 12, 42, 11, offsetMinutes = 60)
        val string = date.toPdfString()
        assertEquals(date.format(), string.bytes.decodeToString())
        assertTrue(!string.hex, "a date is printable ASCII and reads in the file as it reads here")
    }

    /** The ranges the clause gives each field, refused rather than written out of range. */
    @Test
    fun `a field outside the range the clause gives it is refused`() {
        assertFailsWith<IllegalArgumentException> { PdfDate(2026, 13, 1) }
        assertFailsWith<IllegalArgumentException> { PdfDate(2026, 0, 1) }
        assertFailsWith<IllegalArgumentException> { PdfDate(2026, 1, 32) }
        assertFailsWith<IllegalArgumentException> { PdfDate(2026, 1, 0) }
        assertFailsWith<IllegalArgumentException> { PdfDate(2026, 1, 1, hour = 24) }
        assertFailsWith<IllegalArgumentException> { PdfDate(2026, 1, 1, minute = 60) }
        assertFailsWith<IllegalArgumentException> { PdfDate(2026, 1, 1, second = 60) }
        assertFailsWith<IllegalArgumentException> { PdfDate(10000, 1, 1) }
        assertFailsWith<IllegalArgumentException> { PdfDate(-1, 1, 1) }
        // "HH … the absolute value of the offset from UT in hours (00–23)".
        assertFailsWith<IllegalArgumentException> { PdfDate(2026, 1, 1, offsetMinutes = 24 * 60) }
        assertFailsWith<IllegalArgumentException> { PdfDate(2026, 1, 1, offsetMinutes = -(24 * 60)) }
    }

    /** A day the calendar does not have is not a date, whatever the field widths allow. */
    @Test
    fun `a day its month does not have is refused`() {
        assertFailsWith<IllegalArgumentException> { PdfDate(2026, 2, 29) }
        assertFailsWith<IllegalArgumentException> { PdfDate(2026, 4, 31) }
        assertEquals("D:20240229000000Z00'00", PdfDate(2024, 2, 29).format())
    }

    // ------------------------------------------------------- from a clock

    /** The epoch itself, and the second before it. */
    @Test
    fun `the epoch and the second before it`() {
        assertEquals(PdfDate(1970, 1, 1, 0, 0, 0), PdfDate.ofEpochSeconds(0))
        assertEquals(PdfDate(1969, 12, 31, 23, 59, 59), PdfDate.ofEpochSeconds(-1))
    }

    /**
     * Dates whose epoch seconds are counted independently of the code under
     * test: each is a plain multiplication of days by 86 400, with the days
     * counted from the calendar.
     */
    @Test
    fun `known instants convert to the dates they are`() {
        // 1 000 000 000 seconds after the epoch.
        assertEquals(PdfDate(2001, 9, 9, 1, 46, 40), PdfDate.ofEpochSeconds(1_000_000_000))
        // 25 567 days before the epoch: 70 years of 365 days and the 17 leap
        // days of 1904…1968 — 1900 is not one of them, the century rule holding.
        assertEquals(PdfDate(1900, 1, 1, 0, 0, 0), PdfDate.ofEpochSeconds(-25_567L * 86_400))
        // 2000 is a leap year: divisible by 400.
        assertEquals(PdfDate(2000, 2, 29, 12, 0, 0), PdfDate.ofEpochSeconds(951_825_600))
        assertEquals(PdfDate(2024, 2, 29, 0, 0, 0), PdfDate.ofEpochSeconds(1_709_164_800))
        assertEquals(PdfDate(2026, 8, 5, 0, 0, 0), PdfDate.ofEpochSeconds(1_785_888_000))
    }

    /**
     * The offset shifts the local time and, when it crosses midnight, the date
     * with it — "Regardless of whether the time zone is specified, the rest of
     * the date shall be specified in local time."
     */
    @Test
    fun `the offset moves the local time and can move the date`() {
        assertEquals(PdfDate(1970, 1, 1, 1, 0, 0, offsetMinutes = 60), PdfDate.ofEpochSeconds(0, offsetMinutes = 60))
        assertEquals(
            PdfDate(1969, 12, 31, 16, 0, 0, offsetMinutes = -8 * 60),
            PdfDate.ofEpochSeconds(0, offsetMinutes = -8 * 60),
        )
        assertEquals(
            "D:19981223195200-08'00",
            PdfDate.ofEpochSeconds(914_471_520, offsetMinutes = -8 * 60).format(),
        )
    }

    /**
     * A century and a half of days, one after another: each is one day later
     * than the last, and the roll-over of a month or a year lands on the first.
     *
     * The spot checks above say the arithmetic is anchored correctly; this says
     * it stays anchored — a leap rule applied one day early, or a month length
     * taken from the wrong month, breaks the sequence somewhere in the fifty
     * thousand days rather than at the dates a test happened to pick.
     */
    @Test
    fun `every day from 1900 to 2100 is one day after the last`() {
        val start = -25_567L * 86_400 // 1900-01-01
        val days = 73_050 // through 2100
        var previous = PdfDate.ofEpochSeconds(start)
        for (index in 1..days) {
            val date = PdfDate.ofEpochSeconds(start + index * 86_400L)
            val expected = dayAfter(previous)
            assertEquals(expected, date, "day $index after 1900-01-01")
            previous = date
        }
    }

    /**
     * The day after [date], from the calendar rules written out here rather than
     * taken from the code under test.
     */
    private fun dayAfter(date: PdfDate): PdfDate {
        val leap = date.year % 4 == 0 && (date.year % 100 != 0 || date.year % 400 == 0)
        val lengths = intArrayOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        return when {
            date.day < lengths[date.month - 1] -> PdfDate(date.year, date.month, date.day + 1)
            date.month < 12 -> PdfDate(date.year, date.month + 1, 1)
            else -> PdfDate(date.year + 1, 1, 1)
        }
    }

    /** An instant outside the four digits of the year field is refused, not written wrong. */
    @Test
    fun `an instant outside the years the clause can write is refused`() {
        assertFailsWith<IllegalArgumentException> { PdfDate.ofEpochSeconds(Long.MAX_VALUE / 2) }
        assertFailsWith<IllegalArgumentException> { PdfDate.ofEpochSeconds(Long.MIN_VALUE / 2) }
        assertFailsWith<IllegalArgumentException> { PdfDate.ofEpochSeconds(0, offsetMinutes = 24 * 60) }
    }
}
