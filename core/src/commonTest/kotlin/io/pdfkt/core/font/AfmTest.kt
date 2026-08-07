/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.font

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Adobe Font Metrics File Format Specification, technical note #5004
// (7 October 1998): §3 "Parsing Details", §4 "AFM File Structure",
// §7.1 "Global Font Information", §8 "Individual Character Metrics",
// §11.1 "AFM File for Times Roman", §11.4 "AFM File for Ryumin Light Ext
// Horizontal"

/**
 * The parser against the format's own document, example first.
 *
 * #5004 §11 prints complete files as examples; §11.1 is a roman base font and is
 * the shape every one of the standard 14 fonts has. It is used here as it is
 * printed, including its truncated `Comment`, its kerning section and its
 * composite characters — the parts a reader is meant to walk past.
 */
class AfmTest {
    /**
     * The example of #5004 §11.1, verbatim except for two mechanical changes the
     * page forces: the lines the document elides with
     * `. . . -- lines omitted for brevity --` are simply absent, and
     * `StartCharMetrics` therefore announces the eighteen entries actually
     * printed rather than the 228 of the whole font. The count is not decoration
     * — "The integer value indicates how many entries to expect" — so a fixture
     * that kept 228 while printing 18 would be a broken AFM file, and the parser
     * is supposed to say so. That it does is asserted separately below.
     */
    private val timesRomanExample =
        """
        StartFontMetrics 3.0
        Comment Copyright 1985, 1987, 1989, 1990 Adobe Systems ...[truncated]
        FontName Times-Roman
        FullName Times Roman
        FamilyName Times
        Weight Roman
        ItalicAngle 0
        IsFixedPitch false
        FontBBox -170 -223 1024 896
        UnderlinePosition -109
        UnderlineThickness 49
        Version 001.004
        Notice Copyright 1985, 1987, 1989, 1990 Adobe Systems ...[truncated]
        EncodingScheme AdobeStandardEncoding
        CapHeight 662
        XHeight 448
        Ascender 682
        Descender -217
        StartCharMetrics 18
        C 32 ; WX 250 ; N space ; B 0 0 0 0 ;
        C 33 ; WX 333 ; N exclam ; B 109 -14 224 676 ;
        C 34 ; WX 408 ; N quotedbl ; B 70 445 337 685 ;
        C 35 ; WX 500 ; N numbersign ; B 4 0 495 662 ;
        C 36 ; WX 500 ; N dollar ; B 44 -87 456 727 ;
        C 37 ; WX 833 ; N percent ; B 61 -14 772 676 ;
        C 101 ; WX 444 ; N e ; B 22 -10 421 458 ;
        C 102 ; WX 333 ; N f ; B 20 0 383 682 ; L i fi ; L l fl ;
        C 103 ; WX 500 ; N g ; B 27 -217 470 458 ;
        C 104 ; WX 500 ; N h ; B 9 0 490 682 ;
        C 105 ; WX 278 ; N i ; B 22 0 259 682 ;
        C 249 ; WX 500 ; N oslash ; B 30 -108 470 549 ;
        C 250 ; WX 722 ; N oe ; B 30 -10 690 458 ;
        C 251 ; WX 500 ; N germandbls ; B 12 -10 468 682 ;
        C -1 ; WX 611 ; N Zcaron ; B 7 0 597 888 ;
        C -1 ; WX 444 ; N ccedilla ; B 25 -215 412 458 ;
        C -1 ; WX 500 ; N ydieresis ; B 15 -217 476 623 ;
        C -1 ; WX 750 ; N onehalf ; B 30 -14 720 676 ;
        EndCharMetrics
        StartKernData
        StartTrackKern 3
        Comment Light kerning
        TrackKern -1 14 0 72 -1.89
        Comment Medium kerning
        TrackKern -2 8 0 72 -3.2
        Comment Tight kerning
        TrackKern -3 6 -.1 72 -3.78
        EndTrackKern
        StartKernPairs 113
        KPX A y -92
        KPX A w -92
        KPX y period -65
        KPX y comma -65
        EndKernPairs
        EndKernData
        StartComposites 58
        CC Aacute 2 ; PCC A 0 0 ; PCC acute 195 214 ;
        CC Acircumflex 2 ; PCC A 0 0 ; PCC circumflex 195 214 ;
        CC zcaron 2 ; PCC z 0 0 ; PCC caron 56 0 ;
        EndComposites
        EndFontMetrics
        """.trimIndent()

    /**
     * The global keys of #5004 §7.1 that describe the shape of the font — the
     * ones ISO 32000-2:2020 Table 120 asks a font descriptor for, and the ones a
     * line of text is measured against.
     */
    @Test
    fun `the global font information of the specification example`() {
        val afm = AfmFont.parse(timesRomanExample)

        assertEquals("Times-Roman", afm.fontName)
        assertEquals(-170.0, afm.fontBBox.llx)
        assertEquals(-223.0, afm.fontBBox.lly)
        assertEquals(1024.0, afm.fontBBox.urx)
        assertEquals(896.0, afm.fontBBox.ury)
        assertEquals(662.0, afm.capHeight)
        assertEquals(448.0, afm.xHeight)
        assertEquals(682.0, afm.ascender)
        assertEquals(-217.0, afm.descender)
    }

    /**
     * "C 102 ; WX 333 ; N f ; B 20 0 383 682 ; L i fi ; L l fl ;" is the line
     * #5004 §4.4 prints as *the* example of a character metric. Its three fields
     * this library reads come out; `B` and the two `L` entries are walked past,
     * which is the same rule that walks past an unknown key.
     */
    @Test
    fun `a character metric yields its code width and glyph name`() {
        val afm = AfmFont.parse(timesRomanExample)

        assertEquals(18, afm.charMetrics.size)
        assertEquals(333.0, afm.widthOfCode(102))
        assertEquals(333.0, afm.widthOfGlyph("f"))
        assertEquals("f", afm.glyphNameOfCode(102))
        assertEquals(250.0, afm.widthOfGlyph("space"))
        assertEquals(833.0, afm.widthOfCode(37))
    }

    /**
     * "Characters not encoded follow the encoded characters and are identified by
     * character codes of −1" (#5004 §8). They have widths and are reachable by
     * name — an encoding of ISO 32000-2:2020 Annex D will put `Zcaron` at a code
     * of its own — but −1 is not a code, and nothing may select a glyph with it.
     */
    @Test
    fun `unencoded characters are reachable by name and not by code`() {
        val afm = AfmFont.parse(timesRomanExample)

        assertEquals(611.0, afm.widthOfGlyph("Zcaron"))
        assertEquals(750.0, afm.widthOfGlyph("onehalf"))
        assertNull(afm.widthOfCode(-1))
        assertNull(afm.glyphNameOfCode(-1))
    }

    /** A glyph the font does not have is absent, not zero-width. */
    @Test
    fun `a glyph the font does not have is absent`() {
        val afm = AfmFont.parse(timesRomanExample)

        assertNull(afm.widthOfGlyph("afii10017"))
        assertNull(afm.widthOfCode(200))
    }

    /**
     * Kerning (§9) and composite characters (§10) are sections this library does
     * not read, and `TrackKern`, `KPX`, `CC` and `PCC` are keys it does not know.
     * Both cases are the same rule of #5004 §3: "other keys are allowed and
     * ignored by parsers not recognizing them". The example carries all of them,
     * and none of them becomes a character.
     */
    @Test
    fun `kerning and composite sections are skipped`() {
        val afm = AfmFont.parse(timesRomanExample)

        assertEquals(18, afm.charMetrics.size)
        assertNull(afm.widthOfGlyph("Aacute"))
        assertNull(afm.widthOfGlyph("zcaron"))
    }

    /**
     * The other spelling of a character code, from #5004 §8 — "CH <hex> — Same as
     * C, but the character code is given in hexadecimal" — with the width under
     * its explicit writing-direction-0 name. The lines are those of the example
     * in §11.4, which is where both spellings appear.
     */
    @Test
    fun `a hexadecimal character code and an explicit direction-0 width`() {
        val afm =
            AfmFont.parse(
                """
                StartFontMetrics 3.0
                FontName Ryumin-Light-Ext-H
                FontBBox -10 -305 1000 903
                StartCharMetrics 3
                CH <2121> ; W0X 1000 ; B 0 0 0 0 ;
                CH <2122> ; W0X 1000 ; B 80 -75 329 170 ;
                CH <2123> ; W0X 1000 ; B 80 -75 380 225 ;
                EndCharMetrics
                EndFontMetrics
                """.trimIndent(),
            )

        assertEquals(3, afm.charMetrics.size)
        assertEquals(1000.0, afm.widthOfCode(0x2121))
        assertEquals(1000.0, afm.widthOfCode(0x2123))
    }

    /**
     * "Empty lines, or lines containing white space characters only, can occur
     * anywhere in the file after the first line and are ignored", and "Keys and
     * values are separated by one or more white space characters (space or tab)"
     * (#5004 §3). Adobe's own files end their lines with CR LF, so a stray
     * carriage return must not become part of a glyph name.
     */
    @Test
    fun `blank lines tabs and CR LF terminators are white space`() {
        val afm =
            AfmFont.parse(
                "StartFontMetrics 4.1\r\n" +
                    "FontName Test\r\n" +
                    "\r\n" +
                    "\tFontBBox\t-10\t-20\t30\t40\r\n" +
                    "   \r\n" +
                    "StartCharMetrics 1\r\n" +
                    "C 65 ;  WX  722  ;  N  A  ;\r\n" +
                    "EndCharMetrics\r\n" +
                    "EndFontMetrics\r\n",
            )

        assertEquals("Test", afm.fontName)
        assertEquals(-10.0, afm.fontBBox.llx)
        assertEquals(40.0, afm.fontBBox.ury)
        assertEquals(722.0, afm.widthOfGlyph("A"))
    }

    /**
     * The character metrics section is optional — "This section appears in the
     * AFM file only. It is optional." (#5004 §8) — and a file without one is a
     * file that states no widths, not a broken file.
     */
    @Test
    fun `a file without character metrics parses to no characters`() {
        val afm =
            AfmFont.parse(
                """
                StartFontMetrics 4.1
                FontName Test
                FontBBox 0 0 1000 1000
                EndFontMetrics
                """.trimIndent(),
            )

        assertTrue(afm.charMetrics.isEmpty())
        assertNull(afm.widthOfGlyph("A"))
        assertNull(afm.capHeight)
    }

    /**
     * Numbers are "either a real number or an integer, and signed or unsigned"
     * (#5004 §3), which is what the multiple-master example of §11.2 states its
     * global metrics in: `FontBBox -55.16 -250.00 1143.86 834.91`.
     */
    @Test
    fun `numbers may be real and signed`() {
        val afm =
            AfmFont.parse(
                """
                StartFontMetrics 4.0
                FontName MyriadMM
                FontBBox -55.16 -250.00 1143.86 834.91
                CapHeight 674
                XHeight 483.72
                Ascender 710.00
                Descender -198.00
                EndFontMetrics
                """.trimIndent(),
            )

        assertEquals(-55.16, afm.fontBBox.llx)
        assertEquals(834.91, afm.fontBBox.ury)
        assertEquals(483.72, afm.xHeight)
        assertEquals(710.0, afm.ascender)
        assertEquals(-198.0, afm.descender)
    }

    /**
     * "The StartFontMetrics keyword must be the first line in the file" (#5004
     * §4). Something else in that position is not an AFM file, and reading it as
     * one would be reading a width table out of whatever it actually is.
     */
    @Test
    fun `a file that does not begin with StartFontMetrics is refused`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                AfmFont.parse("FontName Test\nStartFontMetrics 4.1\nEndFontMetrics")
            }

        assertTrue(failure.message!!.contains("StartFontMetrics"), failure.message)
    }

    /**
     * "the EndFontMetrics keyword must be the last non-empty line in the file"
     * (#5004 §4). Both halves are checked: a file that never ends, and a file
     * that goes on after ending — a truncated download and a concatenation are
     * how each of them arrives.
     */
    @Test
    fun `a file must end with EndFontMetrics and nothing after it`() {
        val unterminated =
            assertFailsWith<IllegalArgumentException> {
                AfmFont.parse("StartFontMetrics 4.1\nFontName Test\nFontBBox 0 0 1 1")
            }
        assertTrue(unterminated.message!!.contains("EndFontMetrics"), unterminated.message)

        val trailing =
            assertFailsWith<IllegalArgumentException> {
                AfmFont.parse(
                    "StartFontMetrics 4.1\nFontName Test\nFontBBox 0 0 1 1\nEndFontMetrics\nFontName Other",
                )
            }
        assertTrue(trailing.message!!.contains("EndFontMetrics"), trailing.message)
    }

    /** `FontName` and `FontBBox` are the two keys #5004 §7.1 marks "(Required.)". */
    @Test
    fun `the required global keys are required`() {
        val noName =
            assertFailsWith<IllegalArgumentException> {
                AfmFont.parse("StartFontMetrics 4.1\nFontBBox 0 0 1 1\nEndFontMetrics")
            }
        assertTrue(noName.message!!.contains("FontName"), noName.message)

        val noBox =
            assertFailsWith<IllegalArgumentException> {
                AfmFont.parse("StartFontMetrics 4.1\nFontName Test\nEndFontMetrics")
            }
        assertTrue(noBox.message!!.contains("FontBBox"), noBox.message)
    }

    /**
     * "The integer value indicates how many entries to expect" (#5004 §8). A file
     * that disagrees with its own count has lost characters somewhere, and a
     * width table with a hole in it lays out every line that uses the missing
     * glyph at the wrong length — silently, which the no-silent-loss policy
     * forbids. The count is free, so it is checked.
     */
    @Test
    fun `a character metrics count that does not match is an error`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                AfmFont.parse(
                    """
                    StartFontMetrics 4.1
                    FontName Test
                    FontBBox 0 0 1 1
                    StartCharMetrics 228
                    C 32 ; WX 250 ; N space ;
                    EndCharMetrics
                    EndFontMetrics
                    """.trimIndent(),
                )
            }

        assertTrue(failure.message!!.contains("228"), failure.message)
        assertTrue(failure.message!!.contains("1"), failure.message)
    }

    /** "(Either C or CH is required.)" — #5004 §8. */
    @Test
    fun `a character metric without a code is an error`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                AfmFont.parse(
                    """
                    StartFontMetrics 4.1
                    FontName Test
                    FontBBox 0 0 1 1
                    StartCharMetrics 1
                    WX 250 ; N space ;
                    EndCharMetrics
                    EndFontMetrics
                    """.trimIndent(),
                )
            }

        assertTrue(failure.message!!.contains("C"), failure.message)
    }

    /**
     * An unclosed section swallows the rest of the file, so the count check would
     * not catch it and the widths would silently come from kerning data. It is
     * refused by the delimiter rule instead (#5004 §4.4).
     */
    @Test
    fun `an unclosed character metrics section is an error`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                AfmFont.parse(
                    """
                    StartFontMetrics 4.1
                    FontName Test
                    FontBBox 0 0 1 1
                    StartCharMetrics 1
                    C 32 ; WX 250 ; N space ;
                    EndFontMetrics
                    """.trimIndent(),
                )
            }

        assertTrue(failure.message!!.contains("EndCharMetrics"), failure.message)
    }

    /**
     * A name identifies one glyph and a listed code identifies one character —
     * "Byte codes for which no character is defined are not listed" (#5004 §8).
     * Two entries claiming either would make one of the two widths win by
     * accident of order.
     */
    @Test
    fun `a repeated glyph name or code is an error`() {
        val repeatedName =
            assertFailsWith<IllegalArgumentException> {
                AfmFont.parse(
                    """
                    StartFontMetrics 4.1
                    FontName Test
                    FontBBox 0 0 1 1
                    StartCharMetrics 2
                    C 65 ; WX 722 ; N A ;
                    C -1 ; WX 600 ; N A ;
                    EndCharMetrics
                    EndFontMetrics
                    """.trimIndent(),
                )
            }
        assertTrue(repeatedName.message!!.contains("A"), repeatedName.message)

        val repeatedCode =
            assertFailsWith<IllegalArgumentException> {
                AfmFont.parse(
                    """
                    StartFontMetrics 4.1
                    FontName Test
                    FontBBox 0 0 1 1
                    StartCharMetrics 2
                    C 65 ; WX 722 ; N A ;
                    C 65 ; WX 600 ; N Agrave ;
                    EndCharMetrics
                    EndFontMetrics
                    """.trimIndent(),
                )
            }
        assertTrue(repeatedCode.message!!.contains("65"), repeatedCode.message)
    }

    /**
     * A value that is not the type the keyword takes is a corrupt file, not a
     * zero. #5004 §3 fixes the spelling of a number — sign, digits, an optional
     * decimal point — and anything else is refused rather than coerced, since the
     * platforms this library builds for do not agree on what else they would
     * accept.
     */
    @Test
    fun `a malformed number is an error`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                AfmFont.parse(
                    "StartFontMetrics 4.1\nFontName Test\nFontBBox 0 0 1e3 1\nEndFontMetrics",
                )
            }

        assertTrue(failure.message!!.contains("1e3"), failure.message)
    }

    /** `FontBBox` takes four numbers, in the sequence llx lly urx ury (#5004 §7.1). */
    @Test
    fun `a FontBBox that is not four numbers is an error`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                AfmFont.parse(
                    "StartFontMetrics 4.1\nFontName Test\nFontBBox 0 0 1000\nEndFontMetrics",
                )
            }

        assertTrue(failure.message!!.contains("FontBBox"), failure.message)
    }
}
