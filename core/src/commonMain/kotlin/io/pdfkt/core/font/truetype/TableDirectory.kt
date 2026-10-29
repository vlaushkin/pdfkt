/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.font.truetype

// OpenType 1.9.1 §"OpenType font file" — "Organization of an OpenType Font",
// "Table Directory", "Calculating Checksums", "Font Collections"

/**
 * One entry of the table directory: where a table is in the file and how long
 * it is.
 *
 * @property tag "Table identifier", four characters (see [BigEndianReader.tag]).
 * @property checksum "Checksum for this table", carried but not verified — see
 *   [TableDirectory].
 * @property offset "Offset from beginning of font file".
 * @property length "Length of this table". The specification is explicit that
 *   this is the data and not the padding: "All tables must begin on four-byte
 *   boundaries, and any remaining space between tables must be padded with
 *   zeros. The length of each table should be recorded in the table record with
 *   the actual length of data, not the padded length."
 */
internal class TableRecord(val tag: String, val checksum: Long, val offset: Int, val length: Int) {
    override fun toString(): String = "TableRecord('$tag' at $offset, $length bytes)"
}

/**
 * The directory a font file starts with, and the way every table in it is
 * reached.
 *
 * > A key characteristic of the OpenType format is the TrueType sfnt “wrapper”,
 * > which provides organization for a collection of tables in a general and
 * > extensible manner.
 *
 * ## What is not read
 *
 * `searchRange`, `entrySelector` and `rangeShift` are read past and ignored.
 * They are a precomputed binary search over a directory this parser holds in a
 * map, and the specification asks for exactly that:
 *
 * > Since these values can be derived from the numTables field when the file is
 * > parsed, it is strongly recommended that parsing implementations not rely on
 * > the searchRange, entrySelector and rangeShift fields in the font but derive
 * > them independently from numTables. Font files, however, should continue to
 * > provide valid values for these fields to maintain compatibility with all
 * > existing implementations.
 *
 * Checksums are carried in [TableRecord.checksum] and not verified. A checksum
 * answers "did this file change", which is not a question a document generator
 * has to ask of a font its caller handed it, and the answer would be wrong in a
 * useful proportion of real fonts. Producing correct ones is a different matter
 * — it is required of the subset this library writes back out, where the
 * specification's `CalcTableChecksum` and the `head` table's
 * `checksumAdjustment` apply.
 *
 * @property sfntVersion the value the file opens with; see [TRUE_TYPE_OUTLINES]
 *   and [COMPACT_FONT_FORMAT_OUTLINES].
 */
internal class TableDirectory private constructor(
    val sfntVersion: Long,
    private val file: BigEndianReader,
    private val records: Map<String, TableRecord>,
) {
    /** The records, in the order the file lists them. */
    val tables: List<TableRecord> get() = records.values.toList()

    /** Whether the font carries a table called [tag]. */
    operator fun contains(tag: String): Boolean = tag in records

    /**
     * A reader over the table called [tag], or null if the font has no such
     * table. The window is the table alone, so the table's own fields are read
     * at the offsets the specification gives them.
     */
    fun readerOrNull(tag: String): BigEndianReader? {
        val record = records[tag] ?: return null
        return file.sliceAt(record.offset, record.length, "the '$tag' table")
    }

    /**
     * A reader over the table called [tag].
     *
     * @throws IllegalArgumentException if the font has no such table, listing
     *   the tables it does have. A missing `cmap`, `head`, `hhea`, `hmtx` or
     *   `maxp` is not a font this library can use: the specification lists all
     *   five under "Required Tables", and every one of them answers a question
     *   — which glyph, how wide, how many, how big is the em — that has no
     *   substitute and no default.
     */
    fun readerOf(tag: String): BigEndianReader = requireNotNull(readerOrNull(tag)) {
        "the font program has no '$tag' table; it has ${records.keys.joinToString(", ") { "'$it'" }}"
    }

    companion object {
        /** "OpenType fonts that contain TrueType outlines should use the value of 0x00010000". */
        const val TRUE_TYPE_OUTLINES: Long = 0x00010000L

        /** "OpenType fonts containing CFF data (version 1 or 2) should use 0x4F54544F ('OTTO'…)". */
        const val COMPACT_FONT_FORMAT_OUTLINES: Long = 0x4F54544FL

        /** "Font Collection ID string: 'ttcf'" — the first field of a TTC header, not of a font. */
        private const val FONT_COLLECTION: Long = 0x74746366L

        /** The size of the fixed part of the directory, before the records. */
        private const val HEADER_SIZE: Int = 12

        /** "Values are multiplied by 16, which is the size of each TableRecord." */
        private const val RECORD_SIZE: Int = 16

        /**
         * Reads the directory of font [index] of [file]: the directory the file
         * opens with, or — for a font collection — the one its TTC header points
         * at for that font.
         *
         * "The TTC Header … identifies the file as a TTC file … and provides
         * offsets to each of the individual fonts", each of which is "the
         * offset to the TableDirectory for each font from the beginning of the
         * file". The tables of a collection's fonts are at offsets "from the
         * beginning of the TTC file", which is what the records here are read
         * against, so a font of a collection is read without cutting it out.
         *
         * @throws IllegalArgumentException if the file does not open with a
         *   table directory or a TTC header, a collection has no font [index],
         *   [index] is not 0 for a file that is a single font, or a record does
         *   not describe bytes the file has.
         */
        fun parse(file: BigEndianReader, index: Int = 0): TableDirectory {
            require(index >= 0) { "a font index counts from 0; $index is not one" }
            file.seek(0)
            var sfntVersion = file.uint32()
            var directoryStart = 0
            if (sfntVersion == FONT_COLLECTION) {
                file.skip(4) // majorVersion, minorVersion: both versions start with the same fields
                val numFonts = file.uint32()
                require(index < numFonts) {
                    "the font collection holds $numFonts fonts, numbered from 0, so it has no font $index"
                }
                file.skip(index * 4)
                directoryStart = file.uint32().toInt()
                file.seek(directoryStart)
                sfntVersion = file.uint32()
            } else {
                require(index == 0) {
                    "the bytes are a single font, not a font collection ('ttcf'), so the only font in " +
                        "them is font 0; $index was asked for"
                }
            }
            require(sfntVersion == TRUE_TYPE_OUTLINES || sfntVersion == COMPACT_FONT_FORMAT_OUTLINES) {
                "the bytes do not begin with a table directory: the sfntVersion is ${hex(sfntVersion)}, " +
                    "and a font file opens with ${hex(TRUE_TYPE_OUTLINES)} for TrueType outlines, " +
                    "${hex(COMPACT_FONT_FORMAT_OUTLINES)} ('OTTO') for CFF outlines, or " +
                    "${hex(FONT_COLLECTION)} ('ttcf') for a collection of fonts"
            }

            val numTables = file.uint16()
            file.skip(6) // searchRange, entrySelector, rangeShift — derived, not trusted
            require(directoryStart + HEADER_SIZE + numTables * RECORD_SIZE <= file.size) {
                "the table directory claims $numTables tables, which needs " +
                    "${HEADER_SIZE + numTables * RECORD_SIZE} bytes of records, but the font program is " +
                    "${file.size} bytes"
            }

            val records = LinkedHashMap<String, TableRecord>(numTables)
            for (index in 0 until numTables) {
                val tag = file.tag()
                val checksum = file.uint32()
                val offset = file.uint32()
                val length = file.uint32()
                require(offset + length <= file.size) {
                    "the '$tag' table is recorded at offset $offset with $length bytes, past the end of " +
                        "the ${file.size}-byte font program"
                }
                val previous = records.put(tag, TableRecord(tag, checksum, offset.toInt(), length.toInt()))
                require(previous == null) {
                    "the table directory has two records for '$tag'; the specification allows a font at " +
                        "most one table of a given type, and says of more than one that \"behaviour is " +
                        "unpredictable\", so this library refuses rather than picking one"
                }
            }
            return TableDirectory(sfntVersion, file, records)
        }
    }
}
