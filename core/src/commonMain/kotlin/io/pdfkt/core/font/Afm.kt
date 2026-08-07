/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.font

// Adobe Font Metrics File Format Specification, technical note #5004
// (7 October 1998): §3 "Parsing Details", §4 "AFM File Structure",
// §7.1 "Global Font Information", §8 "Individual Character Metrics".
// ISO 32000-2:2020 §9.2.4 "Glyph positioning and metrics" for what the
// numbers mean once they are read.

/**
 * The font bounding box of §7.1:
 *
 * > FontBBox number number number number
 * >
 * > (Required.) Four numbers giving the lower left corner and the upper right
 * > corner of the font bounding box, in the sequence llx lly urx ury.
 *
 * In the units every AFM number is in — "units equal to 1/1000 of the scale
 * factor (point size) of the font being used" (#5004 §3.2), which is exactly the
 * glyph space of ISO 32000-2:2020 §9.2.4.
 */
internal class AfmBBox(val llx: Double, val lly: Double, val urx: Double, val ury: Double) {
    override fun toString(): String = "AfmBBox($llx $lly $urx $ury)"
}

/**
 * One line of the character metrics section (#5004 §8), reduced to the three
 * fields this library reads:
 *
 * > C 102 ; WX 333 ; N f ; B 20 0 383 682 ; L i fi ; L l fl ;
 *
 * @property code `C integer` — "Decimal value of default character code (−1 if
 *   not encoded)". Also the value of a `CH <hex>` field, which #5004 defines as
 *   "Same as C, but the character code is given in hexadecimal".
 * @property width `WX number` — "Character width in x for writing direction 0",
 *   the displacement §9.2.4 calls the glyph's width. Optional in the format, and
 *   so nullable here: a file may describe a glyph without saying how far it
 *   advances, and inventing a number for it is the silent loss this library
 *   forbids. Whoever measures with it raises instead, naming the character.
 * @property glyphName `N name` — "PostScript language character name". Optional
 *   in the format. It is how a byte encoding (ISO 32000-2:2020 §9.6.5, Annex D)
 *   reaches a width: an encoding maps a code to a glyph *name*, not to a code of
 *   this font's own default encoding.
 */
internal class AfmCharMetric(val code: Int, val width: Double?, val glyphName: String?) {
    override fun toString(): String = "AfmCharMetric(C $code ; WX $width ; N $glyphName)"
}

/**
 * An AFM file, parsed — the metrics of one font program as Adobe's format
 * carries them.
 *
 * This library needs it for the fourteen fonts of ISO 32000-2:2020 §9.6.2.2,
 * whose metrics are deliberately absent from every PDF file that uses them:
 * `Widths` and `FontDescriptor` "shall either all be present or all be absent",
 * ordinarily absent, and it is the reader that "shall provide glyph widths and
 * font descriptor data for those standard fonts". A writer needs the same
 * numbers earlier still — it cannot decide where a line breaks without them.
 *
 * ## What is read, and what is skipped
 *
 * Read: the global keys of §7.1 that describe the shape of the font
 * ([fontBBox], [capHeight], [xHeight], [ascender], [descender]) and the `C`,
 * `WX` and `N` fields of each character metric (§8).
 *
 * Skipped, deliberately: kerning (§9) and composite characters (§10) — the
 * layout applies neither, and §9.2.4 NOTE 4 puts kerning beside the standard
 * widths rather than inside them: "The operators for showing text are designed
 * on the assumption that glyphs are ordinarily positioned according to their
 * standard widths." The per-character bounding box `B` is skipped for the same
 * reason: a line breaks against the advance, not against the ink.
 *
 * Skipped by the rule of the format itself: everything else. "The Adobe Systems
 * standard keys are described below, but other keys are allowed and ignored by
 * parsers not recognizing them" (#5004 §3) — so an unknown key is not an error,
 * and neither is a key this parser has no use for.
 *
 * ## What is an error
 *
 * The structure the format calls required, and nothing else: the first line
 * must be `StartFontMetrics`, the last non-empty line must be `EndFontMetrics`,
 * `FontName` and `FontBBox` must be present, a character metric must carry a
 * `C` or a `CH`, and — where the format offers a count — the count must be the
 * truth. `StartCharMetrics integer` says how many entries follow ("The integer
 * value indicates how many entries to expect"); a file that disagrees with its
 * own count is a width table with a hole in it, and a hole in a width table is a
 * document whose lines are quietly the wrong length. It raises here instead.
 */
internal class AfmFont(
    val fontName: String,
    val fontBBox: AfmBBox,
    val capHeight: Double?,
    val xHeight: Double?,
    val ascender: Double?,
    val descender: Double?,
    val charMetrics: List<AfmCharMetric>,
) {
    /**
     * By `N`, for the encodings of ISO 32000-2:2020 Annex D, which name glyphs
     * rather than number them. Characters without an `N` are not here.
     */
    private val byGlyphName: Map<String, AfmCharMetric> =
        buildMap {
            for (metric in charMetrics) {
                val name = metric.glyphName ?: continue
                require(put(name, metric) == null) {
                    "AFM for $fontName: two character metrics name the glyph \"$name\". " +
                        "A name identifies one glyph, so one of the two widths would silently win"
                }
            }
        }

    /**
     * By `C`, for the font's own default encoding — the one `EncodingScheme`
     * names. Unencoded characters, which #5004 §8 gives the code −1, are not
     * here: "Characters not encoded follow the encoded characters and are
     * identified by character codes of –1", and −1 is not a code anything can
     * select.
     */
    private val byCode: Map<Int, AfmCharMetric> =
        buildMap {
            for (metric in charMetrics) {
                if (metric.code < 0) continue
                require(put(metric.code, metric) == null) {
                    "AFM for $fontName: two character metrics claim the code ${metric.code}. " +
                        "\"Byte codes for which no character is defined are not listed\" and a listed " +
                        "code belongs to one character"
                }
            }
        }

    /** The width of the glyph called [name], or `null` if this font has no such glyph. */
    fun widthOfGlyph(name: String): Double? = byGlyphName[name]?.width

    /**
     * The width of the character [code] selects in this font's own default
     * encoding, or `null` if the encoding does not define that code.
     */
    fun widthOfCode(code: Int): Double? = byCode[code]?.width

    /** The glyph [code] selects in this font's own default encoding, if any. */
    fun glyphNameOfCode(code: Int): String? = byCode[code]?.glyphName

    override fun toString(): String = "AfmFont($fontName, ${charMetrics.size} characters)"

    companion object {
        /**
         * Parses [text] as an AFM file, splitting it into the lines the format is
         * made of: "The file format is line-oriented, each line beginning with a
         * property (key) name, followed by the values for that property" (#5004
         * §3).
         *
         * Both line terminators are accepted — Adobe's own files use CR LF —
         * because the format defines white space, not a terminator: "White space
         * characters are ASCII space, newline, and tab."
         */
        fun parse(text: String): AfmFont = parse(text.split("\r\n", "\n", "\r"))

        /** Parses an AFM file already split into lines. */
        fun parse(lines: List<String>): AfmFont {
            var fontName: String? = null
            var fontBBox: AfmBBox? = null
            var capHeight: Double? = null
            var xHeight: Double? = null
            var ascender: Double? = null
            var descender: Double? = null
            var charMetrics: List<AfmCharMetric>? = null
            var announcedCount = 0
            var sawEnd = false

            require(lines.isNotEmpty() && keyOf(lines[0]) == START_FONT_METRICS) {
                "not an AFM file: \"$START_FONT_METRICS version\" must be the first line " +
                    "(#5004 §4), found \"${lines.firstOrNull()?.trim() ?: ""}\""
            }

            var index = 1
            while (index < lines.size) {
                val line = lines[index]
                val lineNumber = index + 1
                index++
                when (val key = keyOf(line)) {
                    // "Empty lines, or lines containing white space characters
                    // only, can occur anywhere in the file after the first line
                    // and are ignored" (#5004 §3).
                    "" -> Unit

                    // "The text is arbitrary and should be ignored" (#5004 §3.1).
                    "Comment" -> Unit

                    // Strings "are terminated by the end of line. They begin with
                    // the first non-white-space character after the keyword"
                    // (#5004 §3).
                    "FontName" -> fontName = stringValueOf(line, key)

                    "FontBBox" -> {
                        val numbers = numbersOf(line, key, lineNumber, count = 4)
                        fontBBox = AfmBBox(numbers[0], numbers[1], numbers[2], numbers[3])
                    }

                    "CapHeight" -> capHeight = numbersOf(line, key, lineNumber, count = 1)[0]
                    "XHeight" -> xHeight = numbersOf(line, key, lineNumber, count = 1)[0]
                    "Ascender" -> ascender = numbersOf(line, key, lineNumber, count = 1)[0]
                    "Descender" -> descender = numbersOf(line, key, lineNumber, count = 1)[0]

                    START_CHAR_METRICS -> {
                        require(charMetrics == null) {
                            "line $lineNumber: a second $START_CHAR_METRICS section; " +
                                "an AFM file describes one set of character metrics (#5004 §4.4)"
                        }
                        announcedCount = countOf(line, key, lineNumber)
                        val section = charMetricsSection(lines, from = index, lineNumber = lineNumber)
                        charMetrics = section.metrics
                        index = section.nextIndex
                    }

                    END_FONT_METRICS -> {
                        sawEnd = true
                        // "the EndFontMetrics keyword must be the last non-empty
                        // line in the file" (#5004 §4).
                        for (rest in index until lines.size) {
                            require(keyOf(lines[rest]) == "") {
                                "line ${rest + 1}: \"${lines[rest].trim()}\" follows $END_FONT_METRICS, " +
                                    "which must be the last non-empty line of an AFM file (#5004 §4)"
                            }
                        }
                        index = lines.size
                    }

                    // "other keys are allowed and ignored by parsers not
                    // recognizing them" (#5004 §3).
                    else -> Unit
                }
            }

            require(sawEnd) { "unterminated AFM file: no $END_FONT_METRICS line (#5004 §4)" }
            val name = requireNotNull(fontName) {
                "AFM file with no FontName, which #5004 §7.1 makes required"
            }
            val boundingBox = requireNotNull(fontBBox) {
                "AFM for $name: no FontBBox, which #5004 §7.1 makes required"
            }
            val metrics = charMetrics.orEmpty()
            require(charMetrics == null || metrics.size == announcedCount) {
                "AFM for $name: $START_CHAR_METRICS announces $announcedCount entries and the " +
                    "section holds ${metrics.size}. The count is the file's own check on its width " +
                    "table, and a width table with a hole in it lays every line out wrong"
            }

            return AfmFont(
                fontName = name,
                fontBBox = boundingBox,
                capHeight = capHeight,
                xHeight = xHeight,
                ascender = ascender,
                descender = descender,
                charMetrics = metrics,
            )
        }

        /**
         * The character metrics between `StartCharMetrics` and `EndCharMetrics`
         * (#5004 §4.4, §8), and where the file continues after it.
         */
        private fun charMetricsSection(lines: List<String>, from: Int, lineNumber: Int): CharMetricsSection {
            val metrics = mutableListOf<AfmCharMetric>()
            var index = from
            while (index < lines.size) {
                val line = lines[index]
                index++
                when (keyOf(line)) {
                    "" -> Unit
                    "Comment" -> Unit
                    END_CHAR_METRICS -> return CharMetricsSection(metrics, index)
                    // The file ends while the section is still open. Read as a
                    // character metric this would complain about a missing `C`,
                    // which is true and useless; what happened is that the
                    // section was never closed.
                    END_FONT_METRICS -> throw IllegalArgumentException(unclosedSection(lineNumber))
                    else -> metrics += charMetricOf(line, index)
                }
            }
            throw IllegalArgumentException(unclosedSection(lineNumber))
        }

        private class CharMetricsSection(val metrics: List<AfmCharMetric>, val nextIndex: Int)

        private fun unclosedSection(lineNumber: Int): String =
            "line $lineNumber: $START_CHAR_METRICS is never closed by $END_CHAR_METRICS (#5004 §4.4)"

        /**
         * One character metric line: "a list of keys and values separated by
         * semicolons, on one line" (#5004 §4.4).
         *
         * Every field the line carries beyond `C`/`CH`, `WX` and `N` is passed
         * over by the same rule that passes over an unknown global key — `B` and
         * `L` in Adobe's own files, `VV`, the writing-direction-1 widths, and
         * whatever a later version of the format adds.
         */
        private fun charMetricOf(line: String, lineNumber: Int): AfmCharMetric {
            var code: Int? = null
            var width: Double? = null
            var glyphName: String? = null

            for (field in line.split(FIELD_SEPARATOR)) {
                val tokens = field.split(*WHITE_SPACE).filter { it.isNotEmpty() }
                if (tokens.isEmpty()) continue
                val values = tokens.drop(1)
                when (tokens[0]) {
                    // "C integer — Decimal value of default character code
                    // (−1 if not encoded)" (#5004 §8).
                    "C" -> code = integerField("C", values, line, lineNumber)

                    // "CH <hex> — Same as C, but the character code is given in
                    // hexadecimal. Example: <84AF>" (#5004 §8).
                    "CH" -> code = hexField(values, line, lineNumber)

                    // "WX number — Character width in x for writing direction 0"
                    // (#5004 §8); W0X is the same field under its explicit name.
                    "WX", "W0X" -> width = numberField(tokens[0], values, line, lineNumber)

                    // "N name — PostScript language character name" (#5004 §8).
                    "N" -> glyphName = nameField(values, line, lineNumber)

                    else -> Unit
                }
            }

            // "(Either C or CH is required.)" (#5004 §8).
            requireNotNull(code) {
                "line $lineNumber: character metric with neither C nor CH, one of which #5004 §8 " +
                    "requires: \"${line.trim()}\""
            }
            return AfmCharMetric(code, width, glyphName)
        }

        /**
         * The key a line begins with, or `""` for a line that is empty or all
         * white space. "Keys and values are separated by one or more white space
         * characters (space or tab)" and "Names … are terminated by white space
         * characters" (#5004 §3).
         */
        private fun keyOf(line: String): String = line.split(*WHITE_SPACE).firstOrNull { it.isNotEmpty() } ?: ""

        /**
         * A string value: everything after the key, since "Strings are terminated
         * by the end of line. They begin with the first non-white-space character
         * after the keyword" (#5004 §3).
         */
        private fun stringValueOf(line: String, key: String): String = line.substringAfter(key).trim()

        private fun valuesOf(line: String, key: String): List<String> =
            stringValueOf(line, key).split(*WHITE_SPACE).filter { it.isNotEmpty() }

        private fun numbersOf(line: String, key: String, lineNumber: Int, count: Int): List<Double> {
            val values = valuesOf(line, key)
            require(values.size == count) {
                "line $lineNumber: $key takes $count number(s) (#5004 §7.1), found ${values.size} " +
                    "in \"${line.trim()}\""
            }
            return values.map { numberOf(it, key, line, lineNumber) }
        }

        private fun countOf(line: String, key: String, lineNumber: Int): Int {
            val values = valuesOf(line, key)
            require(values.size == 1) {
                "line $lineNumber: $key takes one integer (#5004 §4.4), found \"${line.trim()}\""
            }
            return integerOf(values[0], key, line, lineNumber)
        }

        private fun integerField(key: String, values: List<String>, line: String, lineNumber: Int): Int {
            require(values.size == 1) {
                "line $lineNumber: $key takes one integer (#5004 §8), found \"${line.trim()}\""
            }
            return integerOf(values[0], key, line, lineNumber)
        }

        private fun numberField(key: String, values: List<String>, line: String, lineNumber: Int): Double {
            require(values.size == 1) {
                "line $lineNumber: $key takes one number (#5004 §8), found \"${line.trim()}\""
            }
            return numberOf(values[0], key, line, lineNumber)
        }

        private fun nameField(values: List<String>, line: String, lineNumber: Int): String {
            require(values.size == 1) {
                "line $lineNumber: N takes one name, and a name \"might not contain any white space " +
                    "characters\" (#5004 §3); found \"${line.trim()}\""
            }
            return values[0]
        }

        private fun hexField(values: List<String>, line: String, lineNumber: Int): Int {
            require(values.size == 1 && values[0].startsWith("<") && values[0].endsWith(">")) {
                "line $lineNumber: CH takes one hexadecimal code in angle brackets, as in <84AF> " +
                    "(#5004 §8); found \"${line.trim()}\""
            }
            val digits = values[0].substring(1, values[0].length - 1)
            return requireNotNull(digits.toIntOrNull(radix = 16)) {
                "line $lineNumber: \"$digits\" is not a hexadecimal character code (#5004 §8) " +
                    "in \"${line.trim()}\""
            }
        }

        /**
         * "A number can be either a real number or an integer, and signed or
         * unsigned, that is, it may or may not contain a decimal point or a
         * leading minus sign" (#5004 §3) — and nothing else. The spelling is
         * checked before conversion rather than left to the platform, because
         * what the platform accepts beyond this differs between the targets this
         * library builds for.
         */
        private fun numberOf(token: String, key: String, line: String, lineNumber: Int): Double {
            require(NUMBER.matches(token)) {
                "line $lineNumber: $key expects a number (#5004 §3), found \"$token\" in " +
                    "\"${line.trim()}\""
            }
            return token.toDouble()
        }

        private fun integerOf(token: String, key: String, line: String, lineNumber: Int): Int {
            require(INTEGER.matches(token)) {
                "line $lineNumber: $key expects an integer (#5004 §3), found \"$token\" in " +
                    "\"${line.trim()}\""
            }
            return requireNotNull(token.toIntOrNull()) {
                "line $lineNumber: $key expects an integer that fits, found \"$token\""
            }
        }

        /** #5004 §4: the keywords that delimit the file and its sections. */
        private const val START_FONT_METRICS = "StartFontMetrics"
        private const val END_FONT_METRICS = "EndFontMetrics"
        private const val START_CHAR_METRICS = "StartCharMetrics"
        private const val END_CHAR_METRICS = "EndCharMetrics"

        /**
         * "White space characters are ASCII space, newline, and tab" (#5004 §3).
         * A line has already been cut at its newline; carriage return is here
         * because Adobe's files terminate lines with CR LF and a lone CR must not
         * become part of a glyph name.
         */
        private val WHITE_SPACE: Array<String> = arrayOf(" ", "\t", "\r")

        /** "keys and values separated by semicolons" (#5004 §4.4). */
        private const val FIELD_SEPARATOR = ";"

        private val NUMBER = Regex("[-+]?[0-9]*\\.?[0-9]+")
        private val INTEGER = Regex("[-+]?[0-9]+")
    }
}
