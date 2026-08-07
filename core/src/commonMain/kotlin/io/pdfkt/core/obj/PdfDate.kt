/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.obj

// ISO 32000-2:2020 §7.9.4 "Dates"

/**
 * A date, in the fields §7.9.4 writes one out of.
 *
 * > Date values used in a PDF shall conform to a standard date format, which
 * > closely follows that of the international standard ASN.1 (Abstract Syntax
 * > Notation One), defined in ISO/IEC 8824-1. A date shall be a text string
 * > value containing no white-space, of the form:
 * >
 * >     (D:YYYYMMDDHHmmSSOHH'mm)
 *
 * The type is the fields and not an instant, because the clause's form is a
 * *local* time with the offset beside it — "Regardless of whether the time zone
 * is specified, the rest of the date shall be specified in local time" — and an
 * instant does not say which local time it is to be written in. What produces
 * one is a `Clock`;
 * [ofEpochSeconds] is the arithmetic between the two for a clock that counts
 * seconds, which is what every platform's clock does.
 *
 * The ranges of the fields are the clause's own, and the day is checked against
 * the length of its month, which the clause does not ask for and a calendar
 * does: §7.9.4 admits "DD shall be the day (01–31)" for every month because it
 * is describing a written form, and a 31st of February is a date no clock can
 * reach.
 *
 * @property year "YYYY shall be the year", so a value of four digits.
 * @property month "MM shall be the month (01–12)".
 * @property day "DD shall be the day (01–31)".
 * @property hour "HH shall be the hour (00–23)".
 * @property minute "mm shall be the minute (00–59)".
 * @property second "SS shall be the second (00–59)".
 * @property offsetMinutes "the relationship of local time to Universal Time
 *   (UT)", as minutes: positive when "local time is now and later than UT",
 *   negative when it "is earlier than UT", and zero when the two are equal. Its
 *   magnitude is bounded by the two fields it is written in, "the absolute value
 *   of the offset from UT in hours (00–23)" and "in minutes (00–59)".
 */
public data class PdfDate(
    public val year: Int,
    public val month: Int,
    public val day: Int,
    public val hour: Int = 0,
    public val minute: Int = 0,
    public val second: Int = 0,
    public val offsetMinutes: Int = 0,
) {
    init {
        require(year in MIN_YEAR..MAX_YEAR) {
            "the year of a date is written in four digits (YYYY), which holds $MIN_YEAR..$MAX_YEAR; " +
                "got $year (ISO 32000-2:2020 §7.9.4)"
        }
        require(month in MIN_MONTH..MONTHS_PER_YEAR) {
            "MM shall be the month (01–12); got $month (ISO 32000-2:2020 §7.9.4)"
        }
        require(day in MIN_DAY..daysInMonth(year, month)) {
            "DD shall be the day (01–31), and month $month of $year has ${daysInMonth(year, month)} " +
                "days; got $day (ISO 32000-2:2020 §7.9.4)"
        }
        require(hour in 0..MAX_HOUR) {
            "HH shall be the hour (00–23); got $hour (ISO 32000-2:2020 §7.9.4)"
        }
        require(minute in 0..MAX_MINUTE) {
            "mm shall be the minute (00–59); got $minute (ISO 32000-2:2020 §7.9.4)"
        }
        require(second in 0..MAX_SECOND) {
            "SS shall be the second (00–59); got $second (ISO 32000-2:2020 §7.9.4)"
        }
        require(offsetMinutes in -MAX_OFFSET_MINUTES..MAX_OFFSET_MINUTES) {
            "the offset from UT is written as hours (00–23) and minutes (00–59), which holds " +
                "±$MAX_OFFSET_MINUTES minutes; got $offsetMinutes (ISO 32000-2:2020 §7.9.4)"
        }
    }

    /**
     * The date in the written form of §7.9.4, every field present.
     *
     * The clause lets fields be dropped from the right — "the year field (YYYY)
     * shall be present and all other fields may be present but only if all of
     * their preceding fields are also present" — and none is dropped here. A
     * missing field is not an unknown one but a defaulted one ("The default
     * values for MM and DD shall be both 01; all other numerical fields shall
     * default to zero values"), so writing the value in full says exactly what
     * omitting it would, and says it to a reader that does not implement the
     * defaults.
     *
     * The offset is written with the LATIN CAPITAL LETTER Z when local time is
     * UT — "the LATIN CAPITAL LETTER Z signifies that local time is equal to UT"
     * — followed by the two zero fields NOTE 3 admits: "The letter Z can
     * optionally be followed by hour and minute offsets, which are zero in this
     * case." Every date this library writes is therefore the same width.
     *
     * No terminating APOSTROPHE is written. It belongs to the form of an earlier
     * version, which this one keeps only as something to accept: "PDF versions
     * up to and including 1.7 defined a date string to include a terminating
     * apostrophe. PDF processors are recommended to accept date strings that
     * still follow that convention" (NOTE 2).
     */
    public fun format(): String {
        val absolute = if (offsetMinutes < 0) -offsetMinutes else offsetMinutes
        val zone = when {
            offsetMinutes > 0 -> PLUS_SIGN
            offsetMinutes < 0 -> HYPHEN_MINUS
            else -> UNIVERSAL_TIME
        }
        return PREFIX +
            pad(year, YEAR_DIGITS) +
            pad(month, FIELD_DIGITS) +
            pad(day, FIELD_DIGITS) +
            pad(hour, FIELD_DIGITS) +
            pad(minute, FIELD_DIGITS) +
            pad(second, FIELD_DIGITS) +
            zone +
            pad(absolute / MINUTES_PER_HOUR, FIELD_DIGITS) +
            APOSTROPHE +
            pad(absolute % MINUTES_PER_HOUR, FIELD_DIGITS)
    }

    /**
     * The date as the string object it is written as.
     *
     * "A date string can be any valid PDF string object as described in 7.3.4"
     * (NOTE 1), and the literal form is used: every byte of [format] is a
     * printable ASCII character, which is a strict subset of PDFDocEncoding
     * (§7.11.5), so the string reads in the file as it reads here.
     */
    public fun toPdfString(): PdfString = PdfString(format().encodeToByteArray())

    public companion object {
        /**
         * The date [epochSeconds] after 1970-01-01T00:00:00Z, written in the
         * local time that is [offsetMinutes] from UT.
         *
         * This is the one piece of calendar arithmetic in the library, and it is
         * here so that a clock can be what every platform actually offers — a
         * count of seconds — rather than a broken-down local time each target
         * would have to assemble with a platform API of its own
         *
         * The calendar is the proleptic Gregorian one: a year is a leap year
         * when it is divisible by 4, except a century that is not divisible by
         * 400. The walk is a year at a time and then a month at a time, which
         * for a date near this one is some sixty steps — the cost of a rule that
         * can be read and checked against the calendar rather than a closed
         * formula that cannot.
         *
         * @throws IllegalArgumentException if the instant falls outside the
         *   four-digit years §7.9.4 can write, or if [offsetMinutes] is not an
         *   offset the clause can write.
         */
        public fun ofEpochSeconds(epochSeconds: Long, offsetMinutes: Int = 0): PdfDate {
            require(offsetMinutes in -MAX_OFFSET_MINUTES..MAX_OFFSET_MINUTES) {
                "the offset from UT is written as hours (00–23) and minutes (00–59), which holds " +
                    "±$MAX_OFFSET_MINUTES minutes; got $offsetMinutes (ISO 32000-2:2020 §7.9.4)"
            }
            val local = epochSeconds + offsetMinutes.toLong() * SECONDS_PER_MINUTE
            var day = local / SECONDS_PER_DAY
            var secondOfDay = local % SECONDS_PER_DAY
            // Truncating division rounds towards zero and a day before the epoch
            // needs it rounded down: -1 second is the last second of 1969-12-31,
            // not the -1st second of 1970-01-01.
            if (secondOfDay < 0) {
                secondOfDay += SECONDS_PER_DAY
                day -= 1
            }
            var year = EPOCH_YEAR
            while (day < 0) {
                year = stepYear(year, -1)
                day += daysInYear(year)
            }
            while (day >= daysInYear(year)) {
                day -= daysInYear(year)
                year = stepYear(year, 1)
            }
            var month = MIN_MONTH
            var dayOfMonth = day.toInt()
            while (dayOfMonth >= daysInMonth(year, month)) {
                dayOfMonth -= daysInMonth(year, month)
                month += 1
            }
            val time = secondOfDay.toInt()
            return PdfDate(
                year = year,
                month = month,
                day = dayOfMonth + MIN_DAY,
                hour = time / SECONDS_PER_HOUR,
                minute = time / SECONDS_PER_MINUTE % MINUTES_PER_HOUR,
                second = time % SECONDS_PER_MINUTE,
                offsetMinutes = offsetMinutes,
            )
        }

        /** "The prefix 'D:' shall be present." */
        private const val PREFIX: String = "D:"

        /** "HH followed by APOSTROPHE (U+0027) (')". */
        private const val APOSTROPHE: String = "'"

        /** "A PLUS SIGN as the value of the O field signifies that local time is now and later than UT". */
        private const val PLUS_SIGN: String = "+"

        /** "a HYPHEN-MINUS signifies that local time is earlier than UT". */
        private const val HYPHEN_MINUS: String = "-"

        /** "the LATIN CAPITAL LETTER Z signifies that local time is equal to UT". */
        private const val UNIVERSAL_TIME: String = "Z"

        private const val YEAR_DIGITS: Int = 4
        private const val FIELD_DIGITS: Int = 2

        private const val MIN_YEAR: Int = 0
        private const val MAX_YEAR: Int = 9999
        private const val MIN_MONTH: Int = 1
        private const val MIN_DAY: Int = 1
        private const val MAX_HOUR: Int = 23
        private const val MAX_MINUTE: Int = 59
        private const val MAX_SECOND: Int = 59

        private const val MONTHS_PER_YEAR: Int = 12
        private const val MINUTES_PER_HOUR: Int = 60
        private const val SECONDS_PER_MINUTE: Int = 60
        private const val SECONDS_PER_HOUR: Int = 3600
        private const val SECONDS_PER_DAY: Long = 86400

        /** The largest offset the two fields hold: 23 hours and 59 minutes. */
        private const val MAX_OFFSET_MINUTES: Int = MAX_HOUR * MINUTES_PER_HOUR + MAX_MINUTE

        /** The year [ofEpochSeconds] counts from. */
        private const val EPOCH_YEAR: Int = 1970

        private const val DAYS_PER_COMMON_YEAR: Int = 365
        private const val DAYS_PER_LEAP_YEAR: Int = 366

        private const val FEBRUARY: Int = 2
        private const val FEBRUARY_LEAP_DAYS: Int = 29

        /** January to December, in a year that is not a leap year. */
        private val MONTH_LENGTHS: IntArray =
            intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)

        private fun pad(value: Int, digits: Int): String = value.toString().padStart(digits, '0')

        private fun isLeapYear(year: Int): Boolean = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)

        private fun daysInYear(year: Int): Int = if (isLeapYear(year)) DAYS_PER_LEAP_YEAR else DAYS_PER_COMMON_YEAR

        private fun daysInMonth(year: Int, month: Int): Int =
            if (month == FEBRUARY && isLeapYear(year)) FEBRUARY_LEAP_DAYS else MONTH_LENGTHS[month - MIN_MONTH]

        /**
         * One year on or back, refusing to leave the range §7.9.4 can write —
         * which is also what bounds the walk of [ofEpochSeconds] for an instant
         * far outside it.
         */
        private fun stepYear(year: Int, by: Int): Int {
            val next = year + by
            require(next in MIN_YEAR..MAX_YEAR) {
                "the instant falls in the year $next, and a date is written with four digits for the " +
                    "year ($MIN_YEAR..$MAX_YEAR) (ISO 32000-2:2020 §7.9.4)"
            }
            return next
        }
    }
}
