/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.font.truetype

// OpenType 1.9.1 §"OpenType font file" — "Table Directory",
// "Calculating Checksums"; §"glyf", §"loca", §"hmtx", §"head", §"hhea", §"maxp"
// ISO 32000-2:2020 §9.9 "Embedded font programs" (Table 124), §9.9.2 "Font subsets"

/** One table of a subset under construction: its tag and its finished bytes. */
private class SubsetTable(val tag: String, val bytes: ByteArray)

/**
 * A font program cut down to the glyphs a document draws, ready to be embedded
 * as a `FontFile2` stream.
 *
 * ## What a subset is, and what it is not
 *
 * It is not a font file. ISO 32000-2 Table 124 says what an embedded TrueType
 * font program has to be:
 *
 * > The font program shall include these tables: "glyf", "head", "hhea", "hmtx",
 * > "loca", and "maxp". The "cvt " (notice the trailing SPACE), "fpgm", and
 * > "prep" tables shall also be included if they are required by the font
 * > instructions.
 *
 * Those, and nothing else. The `name`, `OS/2`, `post` and `cmap` tables that
 * OpenType requires of a font *file* are not in that list and are not written:
 * the glyphs of a `CIDFontType2` font are selected by index through the PDF's
 * own encoding, so a character map inside the program would state a mapping
 * nothing consults. `cvt `, `fpgm` and `prep` are copied across whenever the
 * font has them, because the glyph descriptions are copied with their
 * instructions in them and those instructions are what the three tables serve.
 *
 * ## Glyphs are renumbered
 *
 * The subset holds its glyphs at [glyphIds]`.indexOf(originalId)`, not at the id
 * the font gave them, which is the whole point: `loca` has one entry per glyph
 * up to the last one present and `hmtx` one record per glyph, so keeping the
 * original numbering would keep two arrays the size of the whole font however
 * few glyphs were drawn. [subsetGlyphIdOf] is the translation, and the caller
 * writing the content stream is the one that needs it.
 *
 * Renumbering is why glyph descriptions cannot be copied quite verbatim: a
 * composite glyph "describes an outline indirectly by referencing other glyphs"
 * by index, and every one of those indices moves. The `glyphIndex` field of each
 * component record is rewritten in place ([GlyphComponent.glyphIdOffset]) and
 * the rest of the description — flags, arguments, transformations, instructions
 * — is the font's own bytes.
 *
 * Glyph 0 is always kept. "The glyph name .notdef shall be defined in the font
 * subset" (§9.9.2), and it is the glyph the format reserves for "a special glyph
 * representing a missing character" whether or not the document ever asked for
 * it.
 *
 * ## The closure is not optional
 *
 * The glyphs kept are [GlyfTable.componentClosureOf] of the ones asked for: a
 * composite and everything it is built out of, transitively. A subset that kept
 * й and dropped и would leave the viewer drawing й out of whatever glyph the
 * renumbered index happened to land on.
 */
internal class TrueTypeSubset private constructor(
    /** The subset font program: a table directory and the tables Table 124 lists. */
    val bytes: ByteArray,
    /**
     * The glyphs the subset keeps, named by their id in the font it was cut
     * from, in the order the subset numbers them: index 0 is `.notdef`, and the
     * rest ascend, so a subset does not depend on the order a document happened
     * to use its glyphs in.
     */
    val glyphIds: List<Int>,
) {
    /** How many glyphs the subset has — its `maxp` numGlyphs. */
    val numGlyphs: Int get() = glyphIds.size

    private val subsetIds: Map<Int, Int> = buildMap(glyphIds.size) {
        glyphIds.forEachIndexed { subsetId, glyphId -> put(glyphId, subsetId) }
    }

    /** Whether the glyph [glyphId] of the original font is in the subset. */
    operator fun contains(glyphId: Int): Boolean = glyphId in subsetIds

    /**
     * The index the glyph [glyphId] of the original font has in the subset.
     *
     * @throws IllegalArgumentException if the glyph was not kept. The alternative
     *   would be returning `.notdef` for a glyph the document draws, which is the
     *   silent loss this library forbids: the page would come out with
     *   a missing-character box on it and every test still green.
     */
    fun subsetGlyphIdOf(glyphId: Int): Int = requireNotNull(subsetIds[glyphId]) {
        "glyph $glyphId of the font is not in this subset of $numGlyphs glyphs; a subset is built " +
            "from the glyphs a document draws, so a glyph asked for after it was built is one the " +
            "document collected too late"
    }

    override fun toString(): String = "TrueTypeSubset($numGlyphs glyphs, ${bytes.size} bytes)"

    companion object {
        /** "a special glyph representing a missing character, commonly known as .notdef". */
        const val NOTDEF: Int = 0

        /**
         * The tables Table 124 requires "if they are required by the font
         * instructions": the control value table, the font program and the
         * control value program. Copied across untouched — none of them is
         * indexed by glyph id.
         */
        val INSTRUCTION_TABLES: List<String> = listOf("cvt ", "fpgm", "prep")

        /** "Values are multiplied by 16, which is the size of each TableRecord." */
        private const val RECORD_SIZE: Int = 16

        /** sfntVersion, numTables, searchRange, entrySelector, rangeShift. */
        private const val DIRECTORY_HEADER_SIZE: Int = 12

        private const val FOUR_BYTE_BOUNDARY: Int = 4

        /** The `head` table, through `glyphDataFormat`. */
        private const val HEAD_SIZE: Int = 54

        /** `checksumAdjustment` follows majorVersion, minorVersion and fontRevision. */
        private const val CHECKSUM_ADJUSTMENT_OFFSET: Int = 8

        /** `indexToLocFormat` is the second-to-last field of `head`. */
        private const val INDEX_TO_LOC_FORMAT_OFFSET: Int = 50

        /** The `hhea` table, through `numberOfHMetrics`. */
        private const val HHEA_SIZE: Int = 36

        /** `numberOfHMetrics` is the last field of `hhea`. */
        private const val NUMBER_OF_H_METRICS_OFFSET: Int = 34

        /** The `maxp` table of version 1.0, through `maxComponentDepth`. */
        private const val MAXP_1_0_SIZE: Int = 32

        /** `numGlyphs` follows the `Version16Dot16` the table opens with. */
        private const val NUM_GLYPHS_OFFSET: Int = 4

        /** "The local offset divided by 2 is stored", so an `Offset16` reaches this far. */
        private const val MAX_SHORT_LOCA_OFFSET: Int = 0xFFFF * 2

        /** "0 for short offsets (Offset16), 1 for long (Offset32)." */
        private const val SHORT_LOCA_OFFSETS: Int = 0
        private const val LONG_LOCA_OFFSETS: Int = 1

        /**
         * Cuts [font] down to [glyphIds], the glyphs the components of those are
         * drawn from, and `.notdef`.
         *
         * @throws IllegalArgumentException if [font] has no glyph descriptions in
         *   this format, or if any of [glyphIds] is not a glyph of it.
         */
        fun of(font: TrueTypeFont, glyphIds: Iterable<Int>): TrueTypeSubset {
            val glyf = requireNotNull(font.glyf) {
                "the font program has no 'glyf' table and so no glyph descriptions to subset; a font " +
                    "with CFF outlines keeps its glyphs in a 'CFF ' table, which is a different format " +
                    "and a different subset"
            }
            require(font.maxp.describesTrueTypeOutlines) {
                "the font's 'maxp' table is version ${hex(font.maxp.version)} while \"fonts with " +
                    "TrueType outlines must use Version 1.0 of this table, where all data is required\"; " +
                    "the subset carries that table's limits across, and version 0.5 states none of them"
            }
            val kept = glyf.componentClosureOf(glyphIds.toList() + NOTDEF).toList()
            val subsetIds = buildMap(kept.size) {
                kept.forEachIndexed { subsetId, glyphId -> put(glyphId, subsetId) }
            }

            val glyphData = glyphDataOf(glyf, kept, subsetIds)
            val longOffsets = glyphData.end > MAX_SHORT_LOCA_OFFSET
            val tables = listOf(
                SubsetTable("glyf", glyphData.glyf),
                SubsetTable("loca", locaOf(glyphData.offsets, longOffsets)),
                SubsetTable("hmtx", hmtxOf(font, kept)),
                SubsetTable("head", headOf(font, longOffsets)),
                SubsetTable("hhea", hheaOf(font, kept.size)),
                SubsetTable("maxp", maxpOf(font, kept.size)),
            ) + INSTRUCTION_TABLES.mapNotNull { tag ->
                font.directory.readerOrNull(tag)?.let { SubsetTable(tag, it.toByteArray()) }
            }
            return TrueTypeSubset(assemble(tables.sortedBy { it.tag }), kept)
        }

        /** The rebuilt `glyf` table and the offsets a `loca` table locates it by. */
        private class GlyphData(val glyf: ByteArray, val offsets: IntArray) {
            /** "the number of elements in the offsets array is numGlyphs + 1". */
            val end: Int get() = offsets[offsets.size - 1]
        }

        /**
         * Copies the descriptions of [kept] into a new `glyf` table, in subset
         * glyph id order — "glyph data blocks within the 'glyf' table must be in
         * glyph ID order" — renumbering the components of every composite among
         * them.
         *
         * A glyph with no description contributes no bytes, which is what makes
         * `loca[n] = loca[n+1]` for it: "if a glyph has no outline or
         * instructions, then loca[n] = loca[n+1]". Every description that does
         * contribute bytes is padded to a four-byte boundary, so that every
         * offset is even and the short `loca` format — which "stores the local
         * offset divided by 2" and therefore cannot express an odd one — stays
         * available.
         */
        private fun glyphDataOf(glyf: GlyfTable, kept: List<Int>, subsetIds: Map<Int, Int>): GlyphData {
            val writer = BigEndianWriter()
            val offsets = IntArray(kept.size + 1)
            kept.forEachIndexed { subsetId, glyphId ->
                offsets[subsetId] = writer.size
                val description = glyf.readerOrNullOf(glyphId)?.toByteArray() ?: return@forEachIndexed
                renumberComponents(description, glyf.glyphOf(glyphId), subsetIds)
                writer.bytes(description)
                writer.padToFourByteBoundary()
            }
            offsets[kept.size] = writer.size
            return GlyphData(writer.toByteArray(), offsets)
        }

        /**
         * Rewrites the `glyphIndex` field of every component record of a
         * composite [glyph] in its copied [description].
         *
         * Every component is in [subsetIds] by construction: the glyphs kept are
         * the closure over the components, so a lookup that missed would mean the
         * closure and this walk disagreed about what the glyph is made of.
         */
        private fun renumberComponents(description: ByteArray, glyph: Glyph, subsetIds: Map<Int, Int>) {
            if (glyph !is Glyph.Composite) return
            for (component in glyph.components) {
                val subsetId = requireNotNull(subsetIds[component.glyphId]) {
                    "a composite glyph references glyph ${component.glyphId}, which the closure over " +
                        "its components did not keep"
                }
                description.putUint16(component.glyphIdOffset, subsetId)
            }
        }

        /**
         * The `loca` table over [offsets], in the format [longOffsets] says: "In
         * the short format, the actual local offset divided by 2 is stored … In
         * the long format, the actual local offset is stored."
         */
        private fun locaOf(offsets: IntArray, longOffsets: Boolean): ByteArray {
            val writer = BigEndianWriter(offsets.size * if (longOffsets) 4 else 2)
            for (offset in offsets) {
                if (longOffsets) writer.u32(offset.toLong()) else writer.u16(offset / 2)
            }
            return writer.toByteArray()
        }

        /**
         * The `hmtx` table of the subset: one `LongHorMetric` per glyph.
         *
         * The optimisation the clause allows — "the number of records can be less
         * than the number of glyphs, in which case the advance width value of the
         * last record applies to all remaining glyph IDs" — is not taken. It only
         * saves anything when the glyphs that share a width are the last ones, and
         * the glyphs of a subset arrive in whatever order the document drew them.
         */
        private fun hmtxOf(font: TrueTypeFont, kept: List<Int>): ByteArray {
            val writer = BigEndianWriter(kept.size * 4)
            for (glyphId in kept) {
                writer.u16(font.advanceWidthOf(glyphId)) // UFWORD advanceWidth
                writer.i16(font.leftSideBearingOf(glyphId)) // FWORD lsb
            }
            return writer.toByteArray()
        }

        /**
         * The font's own `head` table with two fields corrected: the
         * `indexToLocFormat` the rebuilt `loca` is actually in, and a
         * `checksumAdjustment` of zero, which is where the calculation of the
         * real one starts ("Set the checksumAdjustment field to 0").
         *
         * Everything else is carried across, including the fields nothing here
         * parses: the subset is the same font, drawn at the same size, in the same
         * units, with the same bounding box.
         */
        private fun headOf(font: TrueTypeFont, longOffsets: Boolean): ByteArray {
            val head = font.directory.readerOf("head").toByteArray()
            require(head.size >= HEAD_SIZE) {
                "the font's 'head' table is ${head.size} bytes and the table is $HEAD_SIZE"
            }
            head.putUint32(CHECKSUM_ADJUSTMENT_OFFSET, 0)
            head.putUint16(
                INDEX_TO_LOC_FORMAT_OFFSET,
                if (longOffsets) LONG_LOCA_OFFSETS else SHORT_LOCA_OFFSETS,
            )
            return head
        }

        /**
         * The font's own `hhea` table with `numberOfHMetrics` set to the number of
         * records the subset's `hmtx` holds. The ascender, descender and line gap
         * are the font's and do not change with the glyphs kept.
         */
        private fun hheaOf(font: TrueTypeFont, numGlyphs: Int): ByteArray {
            val hhea = font.directory.readerOf("hhea").toByteArray()
            require(hhea.size >= HHEA_SIZE) {
                "the font's 'hhea' table is ${hhea.size} bytes and the table is $HHEA_SIZE"
            }
            hhea.putUint16(NUMBER_OF_H_METRICS_OFFSET, numGlyphs)
            return hhea
        }

        /**
         * The font's own `maxp` table with `numGlyphs` set to the size of the
         * subset.
         *
         * The rest of version 1.0 is what an interpreter has to allocate — maximum
         * points, contours, component depth — and every one of those fields is a
         * maximum: the font's own value stays an upper bound over any subset of
         * its glyphs, and lowering them would mean walking every contour of every
         * glyph kept to compute numbers nothing in this library reads.
         */
        private fun maxpOf(font: TrueTypeFont, numGlyphs: Int): ByteArray {
            val maxp = font.directory.readerOf("maxp").toByteArray()
            require(maxp.size >= MAXP_1_0_SIZE) {
                "the font's 'maxp' table is ${maxp.size} bytes and version 1.0 of it is $MAXP_1_0_SIZE"
            }
            maxp.putUint16(NUM_GLYPHS_OFFSET, numGlyphs)
            return maxp
        }

        /**
         * Writes the table directory and [tables] into one font program, then
         * fills in the two checksums that can only be computed once the bytes
         * exist.
         *
         * [tables] arrives sorted, which the format requires: "The records in the
         * array must be sorted in ascending order by tag."
         *
         * The checksum procedure is the clause's, in its order:
         *
         * > 1. Set the checksumAdjustment field to 0.
         * > 2. Calculate the checksum for all tables including the 'head' table
         * >    and enter the value for each table into the corresponding record in
         * >    the table directory.
         * > 3. Calculate the checksum for the entire font.
         * > 4. Subtract that value from 0xB1B0AFBA.
         * > 5. Store the result in the 'head' table checksumAdjustment field.
         *
         * Step 1 happened in [headOf]. Steps 2 to 5 are here, and the reason they
         * have to be in that order is that step 5 changes bytes step 3 summed:
         * the `head` record's checksum stays the one computed with a zero
         * adjustment, which is exactly what a reader is told to assume —
         * "An application attempting to verify that the 'head' table has not
         * changed should calculate the checksum for that table assuming that the
         * checksumAdjustment value is zero".
         */
        private fun assemble(tables: List<SubsetTable>): ByteArray {
            val offsets = IntArray(tables.size)
            var next = DIRECTORY_HEADER_SIZE + RECORD_SIZE * tables.size
            tables.forEachIndexed { index, table ->
                offsets[index] = next
                next = alignToFourBytes(next + table.bytes.size)
            }

            val file = BigEndianWriter(next)
            // "OpenType fonts that contain TrueType outlines should use the value
            // of 0x00010000 for sfntVersion" — and a subset has a 'glyf' table.
            file.u32(TableDirectory.TRUE_TYPE_OUTLINES)
            file.u16(tables.size)
            val searchRange = largestPowerOfTwoAtMost(tables.size) * RECORD_SIZE
            file.u16(searchRange) // "(2**floor(log2(numTables))) * 16"
            file.u16(log2Of(searchRange / RECORD_SIZE)) // "log2(searchRange/16)"
            file.u16(tables.size * RECORD_SIZE - searchRange) // "(numTables * 16) - searchRange"
            tables.forEachIndexed { index, table ->
                file.tag(table.tag)
                file.u32(tableChecksum(table.bytes))
                file.u32(offsets[index].toLong())
                // "The length of each table should be recorded in the table record
                // with the actual length of data, not the padded length."
                file.u32(table.bytes.size.toLong())
            }
            tables.forEachIndexed { index, table ->
                file.zeros(offsets[index] - file.size)
                file.bytes(table.bytes)
            }
            file.padToFourByteBoundary()

            val bytes = file.toByteArray()
            val head = offsets[tables.indexOfFirst { it.tag == "head" }]
            bytes.putUint32(
                head + CHECKSUM_ADJUSTMENT_OFFSET,
                (CHECKSUM_ADJUSTMENT_MAGIC - tableChecksum(bytes)) and 0xFFFFFFFFL,
            )
            return bytes
        }

        private fun alignToFourBytes(offset: Int): Int =
            offset + (FOUR_BYTE_BOUNDARY - offset % FOUR_BYTE_BOUNDARY) % FOUR_BYTE_BOUNDARY

        /** "Maximum power of 2 less than or equal to numTables". */
        private fun largestPowerOfTwoAtMost(count: Int): Int {
            var power = 1
            while (power * 2 <= count) power *= 2
            return power
        }

        /** "Log 2 of the maximum power of 2 less than or equal to numTables". */
        private fun log2Of(powerOfTwo: Int): Int {
            var remaining = powerOfTwo
            var log = 0
            while (remaining > 1) {
                remaining /= 2
                log++
            }
            return log
        }
    }
}
