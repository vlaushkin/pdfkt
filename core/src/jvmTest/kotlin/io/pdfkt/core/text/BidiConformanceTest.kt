/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.text

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue
import kotlin.test.fail

// Unicode Standard Annex #9 §4.2 "Conformance Testing" — BidiCharacterTest.txt,
// "a test of the Unicode Bidirectional Algorithm … for sequences of
// characters", published with the Unicode Character Database.

/**
 * [BidiParagraph] against every case of the conformance test the Unicode
 * Consortium publishes: the paragraph level, the level of every character,
 * and the visual order of the characters.
 *
 * The file is read from `ucd/` under `PDFKT_TEST_FONT_DIR`, where the other
 * Unicode data this library is generated from is kept; a missing file fails
 * the test.
 */
class BidiConformanceTest {
    @Test
    @DisplayName("every case of BidiCharacterTest.txt resolves as the standard says")
    fun everyConformanceCasePasses() {
        val dir =
            System.getenv("PDFKT_TEST_FONT_DIR")
                ?: fail("PDFKT_TEST_FONT_DIR is not set; it holds ucd/BidiCharacterTest.txt")
        val file = Path.of(dir, "ucd", "BidiCharacterTest.txt")
        assertTrue(
            Files.isReadable(file),
            "$file is missing: download it from https://www.unicode.org/Public/17.0.0/ucd/",
        )
        val failures = ArrayList<String>()
        var cases = 0
        for (line in Files.readAllLines(file)) {
            if (line.isBlank() || line.startsWith("#")) continue
            val fields = line.split(';')
            cases++
            val codePoints = fields[0].trim().split(' ').map { it.toInt(16) }.toIntArray()
            val requested = when (fields[1].trim()) {
                "0" -> 0
                "1" -> 1
                else -> null
            }
            val paragraph = BidiParagraph(codePoints, requested)
            val levels = paragraph.lineLevels(0, codePoints.size)
            val expectedLevels = fields[3].trim().split(' ')
            val expectedOrder = fields[4].trim().split(' ').filter { it.isNotEmpty() }.map { it.toInt() }
            val levelsMatch = expectedLevels.withIndex().all { (i, level) ->
                level == "x" || level.toInt() == levels[i]
            }
            val order = BidiParagraph.visualOrder(levels).filter { !paragraph.removed[it] }
            if (paragraph.paragraphLevel != fields[2].trim().toInt() || !levelsMatch || order != expectedOrder) {
                if (failures.size < MAX_REPORTED) {
                    failures +=
                        "$line\n   got level ${paragraph.paragraphLevel}, levels ${levels.joinToString(" ")}, " +
                        "order ${order.joinToString(" ")}"
                } else {
                    failures += ""
                }
            }
        }
        assertTrue(
            failures.isEmpty(),
            "${failures.size} of $cases cases fail:\n" + failures.take(MAX_REPORTED).joinToString("\n"),
        )
    }

    private companion object {
        const val MAX_REPORTED = 15
    }
}
