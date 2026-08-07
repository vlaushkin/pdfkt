/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.font

// ISO 32000-2:2020 Annex D "Character sets and encodings" (D.1 "General",
// Table D.2 "Latin character set and encodings", Table D.3 "PDFDocEncoding
// character set"), §9.6.5.1 "Character encoding — General",
// §7.9.2.2 "Text string type"

/**
 * WinAnsiEncoding: the association between the character codes of a simple font
 * and the glyphs they select, as Table D.2 defines it.
 *
 * §9.6.5.1 says what an encoding is — "A font's encoding is the association
 * between character codes (obtained from text strings that are shown) and glyph
 * descriptions" — and Table D.1 says what this one is:
 *
 * > **WinAnsiEncoding** Windows Code Page 1252, often called the "Windows ANSI"
 * > encoding. This is the standard Microsoft Windows™ specific encoding for
 * > Latin text in Western writing systems. PDF processors shall have a
 * > predefined encoding named WinAnsiEncoding that may be used with both Type 1
 * > and TrueType fonts.
 *
 * It is predefined, so a document names it and writes nothing else: "This annex
 * lists the character sets and encodings that shall be predefined in any PDF
 * processor" (D.1). That is what makes it the encoding of the simple path
 * — the fourteen fonts of §9.6.2.2 carry no font program and
 * no width array, and a byte in a shown string means the same glyph to every
 * reader without the file saying so.
 *
 * ## The direction a writer needs
 *
 * The clause defines code → glyph name; a writer holding a `String` needs
 * character → code, which is the same table read backwards ([codeOf]). Both
 * directions are here and both come from one transcription of Table D.2, so
 * they cannot drift apart.
 *
 * ## Where the numbers come from
 *
 * Table D.2 lists, for each named character, an octal code under four
 * encodings; [TABLE_D_2] is its `WIN` column, transcribed with the octal
 * spelling the annex uses. What the annex does not state directly is the
 * Unicode value of a named character — it prints the glyph. Each row's Unicode
 * value here is instead read off Table D.3, by way of the `PDF` column of the
 * same row of Table D.2: Table D.3 gives PDFDocEncoding as
 * `Character / Dec / Hex / Octal / Unicode / Unicode character name`, so the
 * `PDF` code of a row names the row of Table D.3 that states its code point.
 * Every one of the 216 characters below resolves that way, which is also what
 * makes the transcription checkable against the specification rather than
 * against a belief about Windows Code Page 1252.
 *
 * ## What is deliberately absent
 *
 * - **The unassigned codes.** Forty of the 256 codes name no character in
 *   Table D.2: 000–037, 177, 201, 215, 217, 220, 235, 240 and 255. Note 3 of
 *   the annex says a reader will show most of them as something — "In
 *   WinAnsiEncoding, all unused codes greater than 40 map to the bullet
 *   character. However, only code 225 is specifically assigned to the bullet
 *   character; other codes are subject to future reassignment" — which is a
 *   rule for reading a file, not a licence for writing one. This library never
 *   emits a code the annex leaves unassigned.
 * - **The second code for space and for hyphen.** Notes 5 and 6 add that "The
 *   hyphen (U+002D) character is also encoded as 255 (octal) in
 *   WinAnsiEncoding" and "The space (U+0020) character is also encoded as 312
 *   (octal) in MacRomanEncoding and as 240 (octal) in WinAnsiEncoding". Those
 *   are alternative codes for characters that already have one; [codeOf]
 *   answers the code Table D.2 assigns, and 240 and 255 are never written.
 * - **U+00A0 and U+00AD.** The same two notes are explicit that the code page
 *   and the encoding disagree there — "Windows Code Page 1252 associates this
 *   character code with the non-breaking space (U+00A0) character. If the PDF
 *   producer intends to map this character code to the "nonbreakingspace"
 *   character from the Adobe Glyph List, this may be specified using a
 *   Differences array in the encoding dictionary". WinAnsiEncoding as such has
 *   no code for a no-break space or a soft hyphen, so this table has none
 *   either and a text containing one is refused rather than quietly turned into
 *   an ordinary space.
 *
 * ## Symbolic fonts
 *
 * This encoding is for Latin text and does not apply to every standard font:
 * "Symbolic fonts contain other character sets, to which the encodings
 * mentioned previously ordinarily do not apply. Such font programs have
 * built-in encodings that are usually unique to each font. The standard 14
 * fonts include two symbolic fonts, Symbol and ZapfDingbats" (§9.6.5.1). See
 * [Standard14Name.symbolic].
 */
internal object WinAnsiEncoding {
    /**
     * The name §9.6.5.1 gives this encoding, as a font dictionary's `/Encoding`
     * spells it: "The regular encodings used for Latin-text fonts on Mac OS and
     * Microsoft Windows™ systems shall be named MacRomanEncoding and
     * WinAnsiEncoding, respectively."
     */
    const val NAME: String = "WinAnsiEncoding"

    /**
     * The code Table D.2 assigns to the character [codePoint], or `null` when
     * the annex assigns it none — the whole of "can this font draw it", since a
     * simple font reaches a glyph only through a code.
     *
     * `null` is not a failure here: it is the question [encode] turns into an
     * error with a position and a remedy attached, and the answer a caller
     * choosing between the simple and the embedded path wants plainly.
     */
    fun codeOf(codePoint: Int): Int? = codeByCodePoint[codePoint]

    /**
     * The PostScript language name of the glyph that [code] selects, or `null`
     * for a code Table D.2 leaves unassigned.
     *
     * This is the direction the clause defines, and the one a width lookup
     * needs: an AFM file identifies a glyph by name (#5004 §8, `N name`), never
     * by a code of some other encoding, so a code becomes a width only by way
     * of the name in between.
     */
    fun glyphNameOf(code: Int): String? = glyphNameByCode.getOrNull(code)

    /**
     * The single-byte codes that select the characters of [text], as they will
     * be written into a string operand of a text-showing operator.
     *
     * One byte per character: a simple font's codes are bytes, which is the
     * whole difference between this path and the composite one, where "a
     * sequence of one or more bytes are decoded to select a glyph" (§9.7.1).
     *
     * @param fontName the font this is being encoded for, as `/BaseFont` spells
     *   it. It appears in the error and nowhere else — the table does not vary
     *   by font — because "which of my fonts was this?" is the first thing the
     *   error has to answer.
     * @throws UnencodableCharacterException at the first character Table D.2
     *   assigns no code to, naming it, where it is, and what to do instead.
     *   Never a substitute glyph, never a dropped one: the "no silent loss"
     *   policy — substitution is how blank pages get produced that every test
     *   calls a success.
     */
    fun encode(text: String, fontName: String): ByteArray {
        val codes = ByteArray(codePointCount(text))
        var written = 0
        forEachCodePoint(text) { index, codePoint ->
            val code = codeByCodePoint[codePoint]
                ?: throw UnencodableCharacterException.outsideEncodingTable(text, index, codePoint, fontName)
            codes[written++] = code.toByte()
        }
        return codes
    }

    /**
     * Table D.2 — the `WIN` column, in code order, one row per character:
     * the octal code the annex prints, the PostScript language name of the
     * glyph, and the code point Table D.3 gives for the same row's `PDF` code.
     *
     * Octal because that is how the annex states these numbers, and a
     * transcription is worth as much as it is easy to check: "EXAMPLE Encoding
     * dictionaries use decimal numbers for character codes, instead of octal
     * values" is the annex noting that its own column is octal. The two
     * footnote marks the annex prints in the name column — `Euro1`, `bullet3`,
     * `currency1`, `guillemotleft4`, `guillemotright4`, `hyphen5`, `space6`,
     * `Zcaron2`, `zcaron2` — are footnote references, not part of a glyph name,
     * and are not transcribed.
     */
    private val TABLE_D_2: List<String> = listOf(
        "040 space           U+0020",
        "041 exclam          U+0021",
        "042 quotedbl        U+0022",
        "043 numbersign      U+0023",
        "044 dollar          U+0024",
        "045 percent         U+0025",
        "046 ampersand       U+0026",
        "047 quotesingle     U+0027",
        "050 parenleft       U+0028",
        "051 parenright      U+0029",
        "052 asterisk        U+002A",
        "053 plus            U+002B",
        "054 comma           U+002C",
        "055 hyphen          U+002D",
        "056 period          U+002E",
        "057 slash           U+002F",
        "060 zero            U+0030",
        "061 one             U+0031",
        "062 two             U+0032",
        "063 three           U+0033",
        "064 four            U+0034",
        "065 five            U+0035",
        "066 six             U+0036",
        "067 seven           U+0037",
        "070 eight           U+0038",
        "071 nine            U+0039",
        "072 colon           U+003A",
        "073 semicolon       U+003B",
        "074 less            U+003C",
        "075 equal           U+003D",
        "076 greater         U+003E",
        "077 question        U+003F",
        "100 at              U+0040",
        "101 A               U+0041",
        "102 B               U+0042",
        "103 C               U+0043",
        "104 D               U+0044",
        "105 E               U+0045",
        "106 F               U+0046",
        "107 G               U+0047",
        "110 H               U+0048",
        "111 I               U+0049",
        "112 J               U+004A",
        "113 K               U+004B",
        "114 L               U+004C",
        "115 M               U+004D",
        "116 N               U+004E",
        "117 O               U+004F",
        "120 P               U+0050",
        "121 Q               U+0051",
        "122 R               U+0052",
        "123 S               U+0053",
        "124 T               U+0054",
        "125 U               U+0055",
        "126 V               U+0056",
        "127 W               U+0057",
        "130 X               U+0058",
        "131 Y               U+0059",
        "132 Z               U+005A",
        "133 bracketleft     U+005B",
        "134 backslash       U+005C",
        "135 bracketright    U+005D",
        "136 asciicircum     U+005E",
        "137 underscore      U+005F",
        "140 grave           U+0060",
        "141 a               U+0061",
        "142 b               U+0062",
        "143 c               U+0063",
        "144 d               U+0064",
        "145 e               U+0065",
        "146 f               U+0066",
        "147 g               U+0067",
        "150 h               U+0068",
        "151 i               U+0069",
        "152 j               U+006A",
        "153 k               U+006B",
        "154 l               U+006C",
        "155 m               U+006D",
        "156 n               U+006E",
        "157 o               U+006F",
        "160 p               U+0070",
        "161 q               U+0071",
        "162 r               U+0072",
        "163 s               U+0073",
        "164 t               U+0074",
        "165 u               U+0075",
        "166 v               U+0076",
        "167 w               U+0077",
        "170 x               U+0078",
        "171 y               U+0079",
        "172 z               U+007A",
        "173 braceleft       U+007B",
        "174 bar             U+007C",
        "175 braceright      U+007D",
        "176 asciitilde      U+007E",
        "200 Euro            U+20AC",
        "202 quotesinglbase  U+201A",
        "203 florin          U+0192",
        "204 quotedblbase    U+201E",
        "205 ellipsis        U+2026",
        "206 dagger          U+2020",
        "207 daggerdbl       U+2021",
        "210 circumflex      U+02C6",
        "211 perthousand     U+2030",
        "212 Scaron          U+0160",
        "213 guilsinglleft   U+2039",
        "214 OE              U+0152",
        "216 Zcaron          U+017D",
        "221 quoteleft       U+2018",
        "222 quoteright      U+2019",
        "223 quotedblleft    U+201C",
        "224 quotedblright   U+201D",
        "225 bullet          U+2022",
        "226 endash          U+2013",
        "227 emdash          U+2014",
        "230 tilde           U+02DC",
        "231 trademark       U+2122",
        "232 scaron          U+0161",
        "233 guilsinglright  U+203A",
        "234 oe              U+0153",
        "236 zcaron          U+017E",
        "237 Ydieresis       U+0178",
        "241 exclamdown      U+00A1",
        "242 cent            U+00A2",
        "243 sterling        U+00A3",
        "244 currency        U+00A4",
        "245 yen             U+00A5",
        "246 brokenbar       U+00A6",
        "247 section         U+00A7",
        "250 dieresis        U+00A8",
        "251 copyright       U+00A9",
        "252 ordfeminine     U+00AA",
        "253 guillemotleft   U+00AB",
        "254 logicalnot      U+00AC",
        "256 registered      U+00AE",
        "257 macron          U+00AF",
        "260 degree          U+00B0",
        "261 plusminus       U+00B1",
        "262 twosuperior     U+00B2",
        "263 threesuperior   U+00B3",
        "264 acute           U+00B4",
        "265 mu              U+00B5",
        "266 paragraph       U+00B6",
        "267 periodcentered  U+00B7",
        "270 cedilla         U+00B8",
        "271 onesuperior     U+00B9",
        "272 ordmasculine    U+00BA",
        "273 guillemotright  U+00BB",
        "274 onequarter      U+00BC",
        "275 onehalf         U+00BD",
        "276 threequarters   U+00BE",
        "277 questiondown    U+00BF",
        "300 Agrave          U+00C0",
        "301 Aacute          U+00C1",
        "302 Acircumflex     U+00C2",
        "303 Atilde          U+00C3",
        "304 Adieresis       U+00C4",
        "305 Aring           U+00C5",
        "306 AE              U+00C6",
        "307 Ccedilla        U+00C7",
        "310 Egrave          U+00C8",
        "311 Eacute          U+00C9",
        "312 Ecircumflex     U+00CA",
        "313 Edieresis       U+00CB",
        "314 Igrave          U+00CC",
        "315 Iacute          U+00CD",
        "316 Icircumflex     U+00CE",
        "317 Idieresis       U+00CF",
        "320 Eth             U+00D0",
        "321 Ntilde          U+00D1",
        "322 Ograve          U+00D2",
        "323 Oacute          U+00D3",
        "324 Ocircumflex     U+00D4",
        "325 Otilde          U+00D5",
        "326 Odieresis       U+00D6",
        "327 multiply        U+00D7",
        "330 Oslash          U+00D8",
        "331 Ugrave          U+00D9",
        "332 Uacute          U+00DA",
        "333 Ucircumflex     U+00DB",
        "334 Udieresis       U+00DC",
        "335 Yacute          U+00DD",
        "336 Thorn           U+00DE",
        "337 germandbls      U+00DF",
        "340 agrave          U+00E0",
        "341 aacute          U+00E1",
        "342 acircumflex     U+00E2",
        "343 atilde          U+00E3",
        "344 adieresis       U+00E4",
        "345 aring           U+00E5",
        "346 ae              U+00E6",
        "347 ccedilla        U+00E7",
        "350 egrave          U+00E8",
        "351 eacute          U+00E9",
        "352 ecircumflex     U+00EA",
        "353 edieresis       U+00EB",
        "354 igrave          U+00EC",
        "355 iacute          U+00ED",
        "356 icircumflex     U+00EE",
        "357 idieresis       U+00EF",
        "360 eth             U+00F0",
        "361 ntilde          U+00F1",
        "362 ograve          U+00F2",
        "363 oacute          U+00F3",
        "364 ocircumflex     U+00F4",
        "365 otilde          U+00F5",
        "366 odieresis       U+00F6",
        "367 divide          U+00F7",
        "370 oslash          U+00F8",
        "371 ugrave          U+00F9",
        "372 uacute          U+00FA",
        "373 ucircumflex     U+00FB",
        "374 udieresis       U+00FC",
        "375 yacute          U+00FD",
        "376 thorn           U+00FE",
        "377 ydieresis       U+00FF",
    )

    /** Table D.2 read as the clause defines an encoding: code → glyph name. */
    private val glyphNameByCode: Array<String?> = arrayOfNulls<String>(CODES_IN_A_BYTE).also { table ->
        for (row in TABLE_D_2) {
            val (code, glyphName, _) = parse(row)
            check(table[code] == null) {
                "WinAnsiEncoding: two glyphs at code $code (\"${table[code]}\", \"$glyphName\"). " +
                    "Table D.2 assigns a code to one character"
            }
            table[code] = glyphName
        }
    }

    /** The same table read the way a writer needs it: character → code. */
    private val codeByCodePoint: Map<Int, Int> = buildMap {
        for (row in TABLE_D_2) {
            val (code, glyphName, codePoint) = parse(row)
            val clash = put(codePoint, code)
            check(clash == null) {
                "WinAnsiEncoding: U+${codePoint.toString(HEX).uppercase()} is encoded twice, " +
                    "as $clash and as $code (\"$glyphName\"). A character this library writes has " +
                    "one code, or the widths it measures are not the widths it draws"
            }
        }
    }

    /** `"<octal code> <glyph name> U+<hex code point>"`, as [TABLE_D_2] spells a row. */
    private fun parse(row: String): Triple<Int, String, Int> {
        val fields = row.split(" ").filter { it.isNotEmpty() }
        check(fields.size == FIELDS_PER_ROW && fields[2].startsWith(CODE_POINT_PREFIX)) {
            "WinAnsiEncoding: \"$row\" is not a row of Table D.2"
        }
        val code = checkNotNull(fields[0].toIntOrNull(OCTAL)) { "not an octal code: \"$row\"" }
        val codePoint =
            checkNotNull(fields[2].removePrefix(CODE_POINT_PREFIX).toIntOrNull(HEX)) {
                "not a code point: \"$row\""
            }
        return Triple(code, fields[1], codePoint)
    }

    /** "a simple font's codes are bytes" — §9.6.5.1, and the size of the table. */
    private const val CODES_IN_A_BYTE: Int = 256
    private const val FIELDS_PER_ROW: Int = 3
    private const val CODE_POINT_PREFIX: String = "U+"
    private const val OCTAL: Int = 8
    private const val HEX: Int = 16
}
